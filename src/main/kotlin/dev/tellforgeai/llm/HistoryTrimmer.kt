package dev.tellforgeai.llm

/**
 * Fits the outgoing message list (system prompt + full persisted history) into a
 * per-request token budget so long agentic chats don't exceed the model's
 * context window (hard 400 errors on strict providers, silent front-truncation
 * on lenient ones — e.g. Ollama's ~8k default).
 *
 * Strategy (cheapest information first):
 *  1. Replace the *content* of old tool-result messages with a short
 *     placeholder. Tool results (file dumps, command output) are usually the
 *     bulk of long chats and are safe to omit once the model has acted on them.
 *  2. If still over budget, drop the oldest droppable units outright:
 *     plain user/assistant messages, or an assistant-with-tool-calls together
 *     with all of its tool-result replies (dropped as one unit so
 *     tool_call_id references stay valid — strict OpenAI-compatible servers
 *     reject dangling assistant tool calls).
 *
 * Protection rules, in all cases:
 *  - the system message (index 0) is never touched;
 *  - the first user message (the original task) is never dropped;
 *  - everything from the last user message onward (the current turn,
 *    including its in-flight tool loop) is never touched.
 *
 * IMPORTANT: [ChatMessage] uses `var` fields and is persisted by IntelliJ's
 * settings serializer. [fit] therefore always works on copies — it must never
 * mutate the input, or persistence would write trimmed content back to
 * tellforgeai.chats.xml and destroy the real history.
 */
object HistoryTrimmer {

    /** Estimated tokens of the omitted-content placeholder. */
    private const val PLACEHOLDER_TOKENS = 24

    private val placeholderContent =
        "[older tool result omitted to fit the context window]"

    /**
     * Returns a message list whose estimated token count is within [budget]
     * when possible. Never mutates the input list or its messages.
     */
    fun fit(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (messages.sumOf { it.estimatedTokens() } <= budget) return messages

        val protected = protectedIndices(messages)
        val out = messages.map { it.copy() }.toMutableList()
        var total = out.sumOf { it.estimatedTokens() }

        // Pass 1: blank out old tool-result contents, oldest first.
        for (i in out.indices) {
            if (total <= budget) break
            if (i in protected) continue
            val m = out[i]
            if (m.role == "tool") {
                val before = out[i].estimatedTokens()
                out[i] = m.copy(content = placeholderContent)
                total += PLACEHOLDER_TOKENS - before
            }
        }

        // Pass 2: drop droppable units, oldest first. Old user messages are
        // kept (small, and they anchor each turn), so after each removal the
        // protected set — system prompt, first user message, and everything
        // from the last user message onward — is recomputed against the
        // shrinking list by structural scan.
        while (total > budget) {
            val prot = reindexedProtection(out)
            val drop = firstDroppableUnit(out, prot)
            if (drop == null) break // nothing more can be removed
            val removed = (drop.first until drop.last + 1)
                .sumOf { out[it].estimatedTokens() }
            out.subList(drop.first, drop.last + 1).clear()
            total -= removed
        }
        return out
    }

    /** Indices that must never be trimmed: system prompt, first user message, current turn. */
    private fun protectedIndices(messages: List<ChatMessage>): Set<Int> {
        val set = mutableSetOf(0)
        if (messages.isNotEmpty() && messages[0].role == "system") {
            messages.indexOfFirst { it.role == "user" }.takeIf { it >= 0 }?.let { set.add(it) }
        }
        messages.indexOfLast { it.role == "user" }.takeIf { it >= 0 }?.let { last ->
            (last until messages.size).forEach { set.add(it) }
        }
        return set
    }

    /**
     * Protection against the shrinking output list, by structural scan:
     * index 0 (system prompt), the first user message, and everything
     * from the last user message onward (the current turn).
     */
    private fun reindexedProtection(out: List<ChatMessage>): Set<Int> {
        val prot = mutableSetOf(0, out.indexOfFirst { it.role == "user" }.coerceAtLeast(0))
        out.indexOfLast { it.role == "user" }.takeIf { it >= 0 }?.let { last ->
            (last until out.size).forEach { prot.add(it) }
        }
        return prot
    }

    /**
     * First index range that is a droppable unit and touches no protected
     * message, or null if nothing can be removed.
     */
    private fun firstDroppableUnit(
        messages: List<ChatMessage>,
        protected: Set<Int>,
    ): IntRange? {
        var i = 0
        while (i < messages.size) {
            val unit = droppableUnit(messages, i)
            if (unit > 0) {
                val range = i until i + unit
                if (range.none { it in protected }) return range
                // Skip past this (protected) unit.
                i += unit
                continue
            }
            i++
        }
        return null
    }

    /**
     * Size of the droppable unit starting at [start]: 0 if not droppable;
     * 1 for a plain user/assistant message; for an assistant message with
     * tool calls, 1 + the count of its directly-following tool-result messages
     * (they must be dropped together).
     */
    private fun droppableUnit(messages: List<ChatMessage>, start: Int): Int {
        val m = messages[start]
        return when {
            m.role == "assistant" && m.toolCalls.isEmpty() -> 1
            m.role == "assistant" -> {
                var n = 1
                while (start + n < messages.size && messages[start + n].role == "tool") n++
                n
            }
            else -> 0 // user (never directive, handled via protection) or orphan "tool"
        }
    }
}

/** Rough token estimate for a message; no tokenizer, so chars/4 plus overhead. */
fun ChatMessage.estimatedTokens(): Int {
    var t = content.length / 4 + 4
    for (c in toolCalls) t += c.arguments.length / 4 + c.name.length / 4 + 8
    // Vision cost scales with image payload: base64 bytes ≈ 4/3 of raw PNG
    // bytes; providers tile images (~750 tokens per 512–1024 px tile on OpenAI).
    // ~750 tokens per 300 KB of encoded payload is a conservative heuristic —
    // small pastes floor at the old 1,300-token estimate.
    for (img in images) {
        val bytes = img.length // data-URL chars ≈ base64 bytes
        t += maxOf(1_300, bytes / 400)
    }
    return t
}

/**
 * Shared context-window bookkeeping for the trimming + near-full warning UI.
 */
object ContextGuard {
    /** Tokens held back from the prompt budget for the model's answer. */
    const val RESERVE_TOKENS = 4_000

    /** Warn the user once past this fraction of the window in use. */
    const val NEAR_FULL_FRACTION = 0.85

    /**
     * Resolve a context window for the active model. A chat-specific value is
     * usable only when it is explicitly associated with that same model;
     * otherwise prefer the currently selected profile's value.
     */
    fun contextWindow(
        chatWindow: Int?,
        chatModel: String?,
        activeModel: String,
        profileWindow: Int?,
    ): Int? {
        val chatMatchesModel = activeModel.isNotBlank() &&
            chatModel.equals(activeModel, ignoreCase = true)
        return if (chatMatchesModel && chatWindow != null && chatWindow > 0) {
            chatWindow
        } else {
            profileWindow?.takeIf { it > 0 }
        }
    }

    /** True when the last known prompt usage is close to the (given) window. */
    fun nearFull(promptTokens: Int, window: Int): Boolean =
        window > 0 && promptTokens >= (window * NEAR_FULL_FRACTION).toInt()

    /** True when trimming altered the outgoing messages by omission or removal. */
    fun wasTrimmed(original: List<ChatMessage>, fitted: List<ChatMessage>): Boolean =
        original != fitted

    /** Once-per-chat transcript note that older tool results/turns were trimmed. */
    fun trimNotice(originalCount: Int, fittedCount: Int): String =
        "ℹ Context near the model's window: older tool results were omitted and/or " +
            "older turns were removed from this request ($fittedCount of $originalCount " +
            "messages sent). The full content remains in this chat's history."
}
