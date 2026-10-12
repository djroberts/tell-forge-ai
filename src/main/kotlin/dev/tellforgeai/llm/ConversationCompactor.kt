package dev.tellforgeai.llm

/** Pure conversation-compaction policy; persistence and network orchestration live in ChatPanel. */
object ConversationCompactor {
    const val TRIGGER_FRACTION = 0.80
    const val TARGET_FRACTION = 0.50

    /** Finds a completed-turn boundary that leaves recent context below the target size. */
    fun boundary(
        messages: List<ChatMessage>,
        alreadyCompacted: Int,
        systemTokens: Int,
        contextWindow: Int,
    ): Int? {
        if (contextWindow <= 0 || messages.size < 4) return null
        val lastUsers = messages.indices.filter { messages[it].role == "user" }.takeLast(2).toSet()
        val firstUser = messages.indexOfFirst { it.role == "user" }
        if (firstUser < 0) return null
        val targetTokens = (contextWindow * TARGET_FRACTION).toInt()
        val firstUserTokens = messages[firstUser].estimatedTokens()
        val candidates = messages.indices.filter { index ->
            index > firstUser && index > alreadyCompacted &&
                messages[index].role == "user" && index !in lastUsers
        }
        return candidates.firstOrNull { cutoff ->
            systemTokens + firstUserTokens + messages.drop(cutoff).sumOf { it.estimatedTokens() } <= targetTokens
        }
    }

    fun shouldCompact(promptTokens: Int, contextWindow: Int): Boolean =
        contextWindow > 0 && promptTokens >= (contextWindow * TRIGGER_FRACTION).toInt()

    /** Builds a compact, text-only input; image payloads are never duplicated into the summary request. */
    fun summarizationMessages(
        oldMessages: List<ChatMessage>,
        priorSummary: String,
    ): List<ChatMessage> {
        val transcript = buildString {
            if (priorSummary.isNotBlank()) {
                appendLine("Existing summary (preserve and update it):")
                appendLine(priorSummary)
                appendLine()
            }
            appendLine("Conversation to incorporate:")
            oldMessages.forEachIndexed { index, message ->
                append("[${index + 1}] ${message.role}: ")
                append(message.content.take(if (message.role == "tool") 6_000 else 12_000))
                if (message.images.isNotEmpty()) append(" [${message.images.size} image(s) attached]")
                if (message.toolCalls.isNotEmpty()) {
                    append(" [tool calls: ")
                    append(message.toolCalls.joinToString { "${it.name}(${it.arguments})" })
                    append("]")
                }
                appendLine()
            }
        }
        return listOf(
            ChatMessage.system(
                "You are compacting an ongoing coding-assistant conversation. Produce a concise, " +
                    "faithful memory for future turns. Preserve the original user goal, constraints, " +
                    "decisions, unresolved questions, important discoveries and file paths, completed " +
                    "changes, and any errors or pending work. Keep exact identifiers and commands " +
                    "when important. Do not invent facts. This memory replaces only the supplied " +
                    "older messages; recent conversation will still be provided separately."
            ),
            ChatMessage.user(transcript),
        )
    }

    /** Adds summary to the system prompt and omits only the represented leading chat messages. */
    fun applySummary(
        system: ChatMessage,
        messages: List<ChatMessage>,
        summary: String,
        compactedCount: Int,
    ): List<ChatMessage> {
        val count = compactedCount.coerceIn(0, messages.size)
        if (summary.isBlank() || count == 0) return listOf(system) + messages
        val summarySystem = system.copy(
            content = system.content + "\n\nConversation memory (summary of earlier turns; trust as prior context):\n" + summary
        )
        val firstUserIndex = messages.indexOfFirst { it.role == "user" }
        val originalTask = messages.getOrNull(firstUserIndex)?.takeIf { firstUserIndex in 0 until count }
        return listOf(summarySystem) + listOfNotNull(originalTask) + messages.drop(count)
    }
}
