package dev.tellforgeai.chat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.tellforgeai.llm.ChatMessage
import dev.tellforgeai.llm.ContextGuard
import dev.tellforgeai.llm.ConversationCompactor
import dev.tellforgeai.llm.estimatedTokens
import dev.tellforgeai.llm.HistoryTrimmer
import dev.tellforgeai.llm.LlmClient
import dev.tellforgeai.llm.LlmErrors
import dev.tellforgeai.llm.Prompts
import dev.tellforgeai.llm.ToolCall
import dev.tellforgeai.model.ChatSession
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.settings.TellForgeAIChatsState
import dev.tellforgeai.tools.ToolRegistry

/**
 * Drives ONE chat's model loop (request -> tool calls -> follow-up turns)
 * independently of which chat is displayed in the UI.
 *
 * The engine works on the *persisted* [ChatSession] (tellforgeai.chats.xml) as
 * its source of truth: history, context summary and token accounting are read
 * from and written to the chat record — so a turn keeps applying even when the
 * user navigates away, and reopening the chat re-renders it from persistence.
 *
 * Rendering is delegated to [Listener]; the panel subscribes and filters by
 * chat id. One [ChatEngine] per chat id per session — created by
 * [ChatRunRegistry].
 */
class ChatEngine(
    private val project: Project?,
    val chatId: String,
    private val listener: Listener,
    /** Approval callbacks shared with the panel (modal dialogs on EDT). */
    private val requestApproval: (message: String, title: String) -> Boolean,
    /** Builds the cached project context snapshot (non-EDT). */
    private val projectContext: () -> String,
    /** Instructions repo layer string (cached, non-EDT). */
    private val instructionsLayer: () -> String,
    /**
     * Injectable dependencies for tests. All accessors are lazy so a test can
     * supply fakes without touching IntelliJ services; production behavior is
     * identical to the previous direct-construction form.
     */
    private val deps: Deps,
) {
    interface Listener {
        /** Called on EDT. */
        fun onAssistantDelta(delta: String)

        /** Called on EDT. */
        fun onReasoningDelta(text: String)

        /** Called on EDT when a tool call starts executing. */
        fun onToolPending(call: ToolCall)

        /** Called on EDT when a tool finished. */
        fun onToolResult(call: ToolCall, result: String)

        /** Called on EDT for transcript system notices. */
        fun onSystem(text: String)

        /** Called on EDT when token usage was recorded; refreshes usage displays. */
        fun onUsageRecorded()

        /** Called on EDT when the whole user turn ended (final answer/error/stop). */
        fun onTurnEnded(stopped: Boolean)
    }

    private val LOG = Logger.getInstance(ChatEngine::class.java)

    /**
     * Engine seams for tests. Everything is a `by lazy` accessor so fakes can
     * be wired before first use, and the production defaults preserve the
     * exact previous wiring (direct `LlmClient()`, `ApplicationManager`,
     * `TellForgeAIChatsState`).
     */
    class Deps(
        /**
         * IntelliJ project for the production default accessors. Tests may
         * pass null ONLY when they override [chats]/[toolSpecs]/[toolExecute]
         * — in production the engine always supplies a real project.
         */
        private val project: Project? = null,
        clientFactory: () -> LlmClient = { LlmClient() },
        /** Marshal to the EDT; tests substitute a synchronous runner. */
        val invokeLater: (block: () -> Unit) -> Unit =
            { block -> ApplicationManager.getApplication().invokeLater(block) },
        /** Chat store access: [ChatStore.get]/[ChatStore.upsert]. Faked in tests. */
        val chatsGet: (id: String?) -> ChatSession? = { id -> TellForgeAIChatsState.getInstance(project!!).get(id) },
        val chatsUpsert: (ChatSession) -> Unit = { chat ->
            TellForgeAIChatsState.getInstance(project!!).upsert(chat)
        },
        /** Tool specs offered to the model for this turn (faked in tests). */
        val toolSpecs: (planMode: Boolean) -> List<Map<String, Any>> =
            { planMode -> ToolRegistry.specs(project!!, planMode) },
        /** Tool execution (faked in tests so no real VFS/commands run). */
        val toolExecute: (
            name: String, argumentsJson: String, planMode: Boolean,
            onDownloadRequested: (String) -> Boolean,
            onCommandRequested: (String) -> Boolean,
            onCommitRequested: (String) -> Boolean,
        ) -> String = { name, argumentsJson, planMode, dl, cmd, commit ->
            ToolRegistry.execute(project!!, name, argumentsJson, planMode, dl, cmd, commit)
        },
        clientOverride: LlmClient? = null,
        /**
         * Backoff for stream retries (attempt is 1-based). Defaults to the
         * production [LlmErrors.backoffMs]; tests inject zero-wait to avoid
         * real Thread.sleep in the retry loop.
         */
        val backoffMs: (attempt: Int) -> Long = { LlmErrors.backoffMs(it) },
    ) {
        /** Client for THIS chat only — other chats' streams are unaffected. */
        val client: LlmClient by lazy { clientOverride ?: clientFactory() }
    }

    private val invokeLater: (block: () -> Unit) -> Unit = deps.invokeLater

    /** Client for THIS chat only — other chats' streams are unaffected. */
    val client: LlmClient = deps.client

    @Volatile
    var running: Boolean = false
        private set

    /** True when the current/last turn was aborted by the user. */
    @Volatile
    var stopped: Boolean = false
        private set

    /** Plan/Foretell mode the (last) turn ran in. */
    @Volatile
    var turnPlanMode: Boolean = false
        private set

    /** Plan mode of the last finished turn, for the overview "complete" indicator. */
    @Volatile
    var completedPlanMode: Boolean? = null
        private set

    /** Set when the current turn ends with a failure (stream/tool/set-up error). */
    @Volatile
    private var lastTurnFailed: Boolean = false

    private var profile: ProviderProfile? = null
    private var contextSummary = ""
    private var compactedMessageCount = 0
    private var toolRounds = 0
    /** Estimated current-turn prompt tokens (see [send]). */
    @Volatile
    var lastPromptTokens = 0
        private set
    private var contextTrimNotified = false
    private var compactionInProgress = false
    private var compactionFailedForCurrentTurn = false
    private val streamingText = StringBuilder()
    private val reasoningText = StringBuilder()

    /** Convenience: the persisted chat this engine drives. */
    private fun chat(): ChatSession? = deps.chatsGet(chatId)

    /**
     * Starts a user turn: appends to the persisted history and begins the
     * loop. [planMode] and [provider] are frozen for the whole turn.
     */
    fun send(text: String, planMode: Boolean, provider: ProviderProfile) {
        val chat = chat() ?: return
        turnPlanMode = planMode
        profile = provider
        contextSummary = chat.contextSummary
        compactedMessageCount = chat.compactedMessageCount.coerceIn(0, chat.messages.size)
        chat.messages.add(ChatMessage.user(text))
        chat.mode = if (planMode) "plan" else "act"
        chat.providerProfileId = provider.id
        chat.providerName = provider.name
        chat.updatedAt = System.currentTimeMillis()
        deps.chatsUpsert(chat)
        // Prime the context meter with an estimate of the actual prompt
        // (history + summary): a freshly created engine starts here at 0 and the
        // provider-reported value only arrives on the first Done event. Lifetime
        // usage is NOT used.
        lastPromptTokens = chat.messages.fold(0) { acc, m -> acc + m.estimatedTokens() } +
            chat.contextSummary.length / 4
        running = true
        stopped = false
        completedPlanMode = null
        lastTurnFailed = false
        toolRounds = 0
        contextTrimNotified = false
        compactionInProgress = false
        compactionFailedForCurrentTurn = false
        streamingText.setLength(0)
        reasoningText.setLength(0)
        pendingReasoning = ""
        startTurn()
    }

    /** Aborts the current turn; buffered partial output is kept and persisted. */
    fun stop() {
        if (!running) return
        stopped = true
        client.cancel()
    }

    /**
     * Manual "Compact now": forces a compaction run even when the automatic
     * (80% of window) gate hasn't triggered — for chats the user knows are
     * bloated or whose provider is rejecting large prompts. Runs offline
     * relative to turns: refuses while a turn is streaming, persists the
     * same way auto-compaction does.
     */
    fun compactNow(provider: ProviderProfile) {
        if (running || compactionInProgress) return
        val chat = chat() ?: return
        turnPlanMode = false
        profile = provider
        contextSummary = chat.contextSummary
        compactedMessageCount = chat.compactedMessageCount.coerceIn(0, chat.messages.size)
        running = false
        stopped = false
        lastTurnFailed = false
        toolRounds = 0
        lastPromptTokens = Int.MAX_VALUE / 2 // force shouldCompact() in startTurn
        contextTrimNotified = false
        compactionInProgress = false
        compactionFailedForCurrentTurn = false
        val system = ChatMessage.system(
            Prompts.system(false, projectContext(), instructionsLayer()),
        )
        val window = contextWindow(provider) ?: (ContextGuard.RESERVE_TOKENS * 8)
        val boundary = ConversationCompactor.boundary(
            chat.messages,
            compactedMessageCount,
            system.estimatedTokens() + contextSummary.length / 4 +
                (chat.messages.firstOrNull { it.role == "user" }?.estimatedTokens() ?: 0),
            window,
        ) ?: run {
            listener.onSystem("Nothing to compact — the history already fits below the compaction target.")
            return
        }
        if (boundary <= compactedMessageCount) {
            listener.onSystem("Nothing new to compact — all completed turns are already summarized.")
            return
        }
        compactionInProgress = true
        listener.onSystem("ℹ Compacting older conversation turns…")
        val oldMessages = chat.messages.subList(compactedMessageCount, boundary).map { it.copy() }
        runCompaction(provider, boundary, oldMessages)
    }

    /** Releases connector jobs; called on project/tool-window dispose. */
    fun dispose(cancel: Boolean) {
        if (cancel) client.cancel()
        running = false
    }

    private fun startTurn() {
        val provider = profile ?: run { running = false; lastTurnFailed = true; listener.onTurnEnded(false); return }
        // system prompt (with project context) is supplied fresh each request
        val system = ChatMessage.system(
            Prompts.system(turnPlanMode, projectContext(), instructionsLayer()),
        )
        val window = contextWindow(provider)
        val boundary = if (!compactionInProgress && !compactionFailedForCurrentTurn && window != null &&
            ConversationCompactor.shouldCompact(lastPromptTokens, window)) {
            val chat = chat() ?: run { running = false; lastTurnFailed = true; listener.onTurnEnded(false); return }
            ConversationCompactor.boundary(
                chat.messages,
                compactedMessageCount,
                system.estimatedTokens() + contextSummary.length / 4 +
                    (chat.messages.firstOrNull { it.role == "user" }?.estimatedTokens() ?: 0),
                window,
            )
        } else null
        if (boundary != null) {
            compactionInProgress = true
            val oldMessages = (chat()?.messages ?: emptyList())
                .subList(compactedMessageCount.coerceIn(0, boundary), boundary)
            runCompaction(provider, boundary, oldMessages)
            return
        }
        val chat = chat() ?: run { running = false; lastTurnFailed = true; listener.onTurnEnded(false); return }
        val summarizedMessages = ConversationCompactor.applySummary(
            system, chat.messages, contextSummary, compactedMessageCount,
        )
        val budget = tokenBudget(provider)
        val messages = if (budget != null) {
            val fitted = HistoryTrimmer.fitResult(summarizedMessages, budget)
            if (ContextGuard.wasTrimmed(summarizedMessages, fitted.messages)) contextTrimNotice(fitted.messages.size, summarizedMessages.size)
            if (!fitted.fitsFully) {
                // Protected remainder (system prompt + project context +
                // original task + current turn) alone exceeds the budget —
                // the provider will likely reject this request. Warn instead
                // of sending blindly; the turn still runs in case the
                // estimated window was pessimistic.
                listener.onSystem(
                    "⚠ Prompt still exceeds the estimated context window after trimming " +
                        "(${fitted.messages.sumOf { it.estimatedTokens() }} > $budget tokens estimated). " +
                        "The provider may reject this request — consider a new chat or a larger-window model.",
                )
                LOG.warn("TellForgeAI: outgoing prompt estimated above the token budget after trimming")
            }
            fitted.messages
        } else {
            summarizedMessages
        }
        streamingText.setLength(0)
        reasoningText.setLength(0)
        streamWithRetry(provider, messages, budget, toolRound = null)
    }

    /**
     * Streams one request with the safe-retry policy:
     *
     *  - classified TRANSIENT/GATEWAY/NETWORK errors are retried with
     *    bounded exponential backoff (max [MAX_RETRIES]), surfaced to the
     *    user between attempts;
     *  - CONTEXT_TOO_LARGE triggers exactly one content fix: the same
     *    messages re-trimmed with a 25% smaller budget (never a blind
     *    resend of the identical prompt);
     *  - INVALID/OTHER surface unchanged;
     *  - no retry ever fires once a delta arrived (a mid-stream retry would
     *    duplicate tokens), and every attempt stops when the user did.
     *
     * [budget] is the token budget used to fit [messages]; needed to
     * re-trim on a context overflow. Null when no window is known (no
     * content fix is then possible).
     *
     * [toolRound] is null for the first request of a turn and the round
     * index for follow-up turns after tool results: a retry on a follow-up
     * round is allowed too (the state is persisted and idempotent), but the
     * content fix is not — re-trimming mid-turn could orphan the tool calls
     * the round replied to.
     */
    private fun streamWithRetry(
        provider: ProviderProfile,
        messages: List<ChatMessage>,
        budget: Int?,
        toolRound: Int?,
        attempt: Int = 1,
    ) {
        var sawDelta = false
        client.stream(provider, turnPlanMode, messages, deps.toolSpecs(turnPlanMode)) { event ->
            // invoked on a background thread; marshal to the EDT
            invokeLater {
                // After Stop only finalize: the cancellation surfaces as a Done
                // (or Error) event which must still end the turn, else the
                // overview would show "running" forever.
                if (stopped) {
                    if (event is LlmClient.Event.Done || event is LlmClient.Event.Error) endTurn()
                    return@invokeLater
                }
                when (event) {
                    is LlmClient.Event.Delta -> {
                        sawDelta = true
                        streamingText.append(event.text)
                        listener.onAssistantDelta(event.text)
                    }
                    is LlmClient.Event.Reasoning -> {
                        reasoningText.append(event.text)
                        listener.onReasoningDelta(event.text)
                    }
                    is LlmClient.Event.Done -> onTurnDone(event)
                    is LlmClient.Event.Error -> {
                        val kind = LlmErrors.classify(event.message)
                        val canContentFix = kind == LlmErrors.Kind.CONTEXT_TOO_LARGE &&
                            budget != null && toolRound == null && attempt == 1
                        val canRetry = LlmErrors.retryable(kind) &&
                            attempt <= MAX_RETRIES && !sawDelta
                        when {
                            canRetry -> {
                                val wait = deps.backoffMs(attempt)
                                listener.onSystem(
                                    "⏳ ${kindLabel(kind)} (attempt $attempt failed) — retrying in ${wait / 1000}s…",
                                )
                                LOG.info("TellForgeAI: retrying stream after ${kindLabel(kind)}; attempt $attempt, wait ${wait}ms")
                                if (wait == 0L) {
                                    // Nothing to wait for — no thread hop; runs
                                    // through the same marshal as a delay would.
                                    invokeLater {
                                        if (running && !stopped) {
                                            streamWithRetry(provider, messages, budget, toolRound, attempt + 1)
                                        } else {
                                            endTurn()
                                        }
                                    }
                                } else Thread {
                                    Thread.sleep(wait)
                                    invokeLater {
                                        if (running && !stopped) {
                                            streamWithRetry(provider, messages, budget, toolRound, attempt + 1)
                                        } else {
                                            endTurn()
                                        }
                                    }
                                }.start()
                            }
                            canContentFix -> {
                                val smaller = (budget * 0.75).toInt()
                                listener.onSystem(
                                    "⚠ Context overflow reported by the provider — re-trimming the history to ~$smaller tokens and retrying once.",
                                )
                                LOG.warn("TellForgeAI: provider reported context overflow; re-trimming once (budget $budget -> $smaller)")
                                val refitted = HistoryTrimmer.fitResult(messages, smaller)
                                if (refitted.messages.size == messages.size &&
                                    refitted.messages.sumOf { it.estimatedTokens() } >= messages.sumOf { it.estimatedTokens() }
                                ) {
                                    // Already at the floor — re-trimming cannot help.
                                    finishError(event)
                                } else {
                                    streamWithRetry(provider, refitted.messages, smaller, toolRound, attempt + 1)
                                }
                            }
                            else -> finishError(event)
                        }
                    }
                }
            }
        }
    }

    /** Ends the turn with the raw error, after persisting accumulated reasoning. */
    private fun finishError(event: LlmClient.Event.Error) {
        lastTurnFailed = true
        finalizeAssistantBlock()
        // finalizeAssistantBlock keeps reasoning in pendingReasoning
        // when its answer text was blank; a failed round's thinking
        // must still reach persistence (nothing lost).
        if (pendingReasoning.isNotBlank()) {
            addToMessages {
                it += ChatMessage.assistant("").copy(reasoning = pendingReasoning)
            }
            pendingReasoning = ""
            persistChat()
        }
        listener.onSystem("Error: ${event.message}")
        endTurn()
    }

    private fun kindLabel(kind: LlmErrors.Kind): String = when (kind) {
        LlmErrors.Kind.TRANSIENT_RATE_LIMIT -> "Provider rate limit"
        LlmErrors.Kind.TRANSIENT_OVERLOADED -> "Provider overloaded"
        LlmErrors.Kind.GATEWAY -> "Provider gateway error"
        LlmErrors.Kind.NETWORK -> "Network error"
        else -> "Request failed"
    }

    private fun contextTrimNotice(keptMessages: Int, totalMessages: Int) {
        if (contextTrimNotified) return
        contextTrimNotified = true
        listener.onSystem(ContextGuard.trimNotice(totalMessages, keptMessages))
    }

    /** Per-request prompt-token budget, null when no window is known. */
    private fun tokenBudget(provider: ProviderProfile): Int? =
        contextWindow(provider)?.let { it - ContextGuard.RESERVE_TOKENS }

    /**
     * Fits the summarization input into its own budget (the summarize call is
     * itself an LLM request and must fit the window), then sends it.
     *
     * Inputs are trimmed keeping the TAIL of the region (fitFromEnd semantics
     * are inside the per-message truncation of [ConversationCompactor] plus
     * [HistoryTrimmer]): the newest old turns are the most valuable. If the
     * fitted input was still over budget and the summarize call failed, one
     * additional reduced pass is attempted before degrading to normal
     * trimming — never an infinite retry.
     */
    private fun runCompaction(
        provider: ProviderProfile,
        boundary: Int,
        oldMessages: List<ChatMessage>,
        passesLeft: Int = 1,
    ) {
        val window = contextWindow(provider)
        val summarizeBudget = summarizeBudgetSafe(window)
        val maxTokens = window?.let { (it * 0.12).toInt() }?.coerceAtLeast(512) ?: 3_000
        val summaryInput = ConversationCompactor.summarizationMessages(oldMessages, contextSummary)
        val fitted = HistoryTrimmer.fitResult(summaryInput, summarizeBudget)
        // The transcript inside the summarize input is BOTH the first and the
        // last user message, so HistoryTrimmer's protection rules (never drop
        // the original task / current turn) make it untrimmable — fit returns
        // it whole even when it alone exceeds the budget. The summarize call
        // must fit the window, so as a final measure head-truncate the
        // transcript content to the remaining budget (chars ≈ tokens/4).
        val out = if (!fitted.fitsFully) {
            var remaining = summarizeBudget - (fitted.messages.size - 1) * 8 - fitted.messages
                .dropLast(1).sumOf { it.estimatedTokens() }
            if (remaining < 128) remaining = 128
            fitted.messages.dropLast(1) + fitted.messages.last()
                .copy(content = fitted.messages.last().content.take(remaining * 4))
        } else {
            fitted.messages
        }
        client.summarize(provider, turnPlanMode, out, maxTokens) { result ->
            invokeLater {
                compactionInProgress = false
                if (stopped) {
                    endTurn()
                    return@invokeLater
                }
                result.fold(
                    onSuccess = { summary ->
                        contextSummary = summary
                        compactedMessageCount = boundary
                        compactionFailedForCurrentTurn = false
                        persistChat()
                        listener.onSystem("ℹ Older conversation turns were compacted into a summary for future model requests; the full chat transcript is unchanged.")
                        lastPromptTokens = 0
                    },
                    onFailure = { error ->
                        LOG.warn("TellForgeAI conversation compaction failed; using normal history trimming", error)
                        if (passesLeft > 0 && !fitted.fitsFully) {
                            // The summarize request itself was likely still
                            // over the window. One reduced retry: only the
                            // first half of the region is summarized.
                            listener.onSystem("ℹ Compaction retrying with a smaller history chunk.")
                            runCompaction(
                                provider, boundary, oldMessages.take(oldMessages.size / 2),
                                passesLeft = 0,
                            )
                            return@invokeLater
                        }
                        compactionFailedForCurrentTurn = true
                        lastPromptTokens = 0
                        listener.onSystem(
                            "⚠ Conversation compaction failed (${error.message ?: error.javaClass.simpleName}) — " +
                                "falling back to history trimming for this turn.",
                        )
                    },
                )
                startTurn()
            }
        }
    }

    /** Budget for the summarize request when no window is known (≈ 32k tokens). */
    private val DEFAULT_SUMMARIZE_BUDGET = 32_000

    private fun summarizeBudgetSafe(window: Int?): Int =
        window?.let { it - ContextGuard.RESERVE_TOKENS - (it * 0.12).toInt() }
            ?: DEFAULT_SUMMARIZE_BUDGET

    private fun contextWindow(provider: ProviderProfile): Int? {
        val chat = chat()
        val activeModel = provider.modelFor(turnPlanMode).trim()
        return ContextGuard.contextWindow(
            chatWindow = chat?.contextWindow,
            chatModel = chat?.contextModel,
            activeModel = activeModel,
            profileWindow = provider.contextWindow,
        )
    }

    private fun onTurnDone(event: LlmClient.Event.Done) {
        event.usage?.let { usage ->
            lastPromptTokens = usage.promptTokens
            val chat = chat()
            if (chat != null) {
                val usedModel = profile?.modelFor(turnPlanMode).orEmpty()
                if (chat.contextModel.isBlank()) chat.contextModel = usedModel
                else if (usedModel.isNotBlank() && chat.contextModel != usedModel) chat.mixedModelsUsage = true
                chat.incomingTokens += usage.promptTokens.coerceAtLeast(0).toLong()
                chat.cachedInputTokens += (usage.cachedPromptTokens ?: 0).coerceIn(0, usage.promptTokens.coerceAtLeast(0)).toLong()
                chat.outgoingTokens += usage.completionTokens.coerceAtLeast(0).toLong()
                deps.chatsUpsert(chat)
                listener.onUsageRecorded()
            }
        }
        // Near-full warning, once per chat.
        if (event.usage != null && !contextTrimNotified) {
            val provider = profile ?: return
            val window = contextWindow(provider)
            if (window != null && ContextGuard.nearFull(event.usage.promptTokens, window)) {
                contextTrimNotified = true
                listener.onSystem(
                    "⚠ Context is nearly full (${event.usage.promptTokens} / $window tokens). " +
                        "Long-term accuracy degrades past this point — consider starting a new chat."
                )
            }
        }
        if (event.toolCalls.isEmpty()) {
            // Plain final answer.
            // finalizeAssistantBlock persists the accumulated streamed text as the
            // assistant message. Only fall back to event.fullText when nothing was
            // streamed (e.g. non-streaming connector), otherwise the message is
            // stored twice and the transcript renders the answer two times.
            val streamedSomething = streamingText.isNotEmpty()
            finalizeAssistantBlock()
            if (event.fullText.isNotBlank() && !streamedSomething) {
                addToMessages {
                    it += ChatMessage.assistant(event.fullText).copy(reasoning = pendingReasoning)
                }
                pendingReasoning = ""
            }
            persistChat()
            endTurn()
            return
        }
        // Loop guard: a model that keeps requesting tools would otherwise loop
        // (and re-send the whole history each round) indefinitely.
        if (toolRounds >= MAX_TOOL_ROUNDS) {
            val streamedSomething = streamingText.isNotEmpty()
            finalizeAssistantBlock()
            listener.onSystem("Stopped after $MAX_TOOL_ROUNDS tool rounds to avoid an endless loop. " +
                "Press Send to let it continue.")
            if (event.fullText.isNotBlank() && !streamedSomething) {
                addToMessages {
                    it += ChatMessage.assistant(event.fullText).copy(reasoning = pendingReasoning)
                }
                pendingReasoning = ""
            }
            addToMessages {
                it += ChatMessage.user(
                    "Tool loop stopped after $MAX_TOOL_ROUNDS rounds. Summarize what you have and give a final answer now."
                ).copy(synthetic = true)
            }
            persistChat()
            endTurn()
            return
        }
        toolRounds++
        // Defensive guard: some providers (e.g. GLM on deepinfra) occasionally
        // finish a tool call with a blank function name (arguments intact).
        // Executing it would return "unknown tool ''" and the model would retry
        // the same broken call until the round limit — surface it and stop here.
        val nameless = event.toolCalls.filter { it.name.isBlank() }
        if (nameless.isNotEmpty()) {
            LOG.warn(
                "TellForgeAI: provider returned ${nameless.size} tool call(s) with a blank name; " +
                    "arguments: ${nameless.joinToString("; ") { it.arguments.take(200) }}"
            )
            finalizeAssistantBlock()
            lastTurnFailed = true
            listener.onSystem(
                "Error: provider sent ${nameless.size} tool call(s) without a function name " +
                    "(arguments: ${nameless.joinToString("; ") { it.arguments.take(80) }}). " +
                    "The turn was stopped instead of retrying — see idea.log for the raw stream."
            )
            endTurn()
            return
        }
        // The assistant tool-call item MUST be persisted even when the model
        // streamed no preamble text, otherwise the next round sends a
        // function_call_output with no matching function_call (HTTP 400,
        // "No tool call found for function call output", on /v1/responses).
        // The round's reasoning travels with it: captured here when the
        // preamble finalize didn't run for this path.
        if (reasoningText.isNotEmpty() && pendingReasoning.isBlank()) {
            pendingReasoning = reasoningText.toString()
            reasoningText.setLength(0)
        }
        addToMessages {
            it += ChatMessage.assistantWithToolCalls(event.fullText, event.toolCalls)
                .copy(reasoning = pendingReasoning)
        }
        pendingReasoning = ""
        persistChat()
        val calls = event.toolCalls
        calls.forEach { listener.onToolPending(it) }

        // Tool execution on background thread; results feed the next turn on EDT.
        Thread {
            try {
                val results = calls.map { call ->
                    val result = deps.toolExecute(
                        call.name, call.arguments, turnPlanMode,
                        { request ->
                            requestApproval(
                                "TellForgeAI wants to download a binary file:\n\n$request\n\nAllow?", "Download binary",
                            )
                        },
                        { command ->
                            if (!dev.tellforgeai.settings.TellForgeAISettings.getInstance().askBeforeRunCommands()) true
                            else requestApproval(
                                "TellForgeAI wants to run this command:\n\n$command\n\nAllow?", "Run command",
                            )
                        },
                        { diff ->
                            requestApproval(
                                "TellForgeAI wants to commit and push this change to the instructions repo:\n\n$diff",
                                "Push instructions change",
                            )
                        },
                    )
                    call to result
                }
                invokeLater {
                    for ((call, result) in results) {
                        listener.onToolResult(call, result)
                        addToMessages { it += ChatMessage.toolResult(call.id, result.take(MAX_TOOL_RESULT_CHARS)) }
                    }
                    persistChat()
                    if (stopped) {
                        endTurn()
                    } else {
                        // The previous stream's job may still be finishing when Done
                        // was delivered; defer until the client is actually idle so
                        // stream()'s isRunning() guard doesn't drop this turn.
                        client.whenIdle {
                            invokeLater {
                                if (running && !stopped) startTurn()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                invokeLater {
                    lastTurnFailed = true
                    listener.onSystem("Tool execution failed: ${e.message ?: e.javaClass.simpleName}")
                    endTurn()
                }
            }
        }.start()
    }

    /** Ends the turn: clears the live state, records the persisted turn status and notifies the listener. */
    private fun endTurn() {
        val wasStopped = stopped
        running = false
        stopped = wasStopped
        if (!wasStopped) completedPlanMode = turnPlanMode
        // Persist the end-of-turn marker so the transcript can render "✓ Task
        // complete" / "Stopped." / an error note after reopening the chat, not
        // just live. Also drives the overview's "complete" indicator.
        val chat = chat()
        if (chat != null) {
            chat.lastTurnStatus = when {
                wasStopped -> "stopped"
                lastTurnFailed -> "error"
                else -> "complete"
            }
            chat.lastTurnPlanMode = turnPlanMode
            persistChat()
        }
        streamingText.setLength(0)
        reasoningText.setLength(0)
        pendingReasoning = ""
        listener.onTurnEnded(wasStopped)
    }

    /**
     * Persists the in-flight assistant message: the accumulated streaming text
     * becomes a history entry if it has content. The accumulated reasoning
     * travels with it (kept even when the answer text is blank — an assistant
     * block that requested tool calls may still have streamed thinking first,
     * and dropping it there lost the KB counter for that round).
     */
    private fun finalizeAssistantBlock() {
        val md = streamingText.toString()
        streamingText.setLength(0)
        val reasoning = reasoningText.toString()
        reasoningText.setLength(0)
        // Blank answer text: nothing to persist here — the reasoning stays in
        // [reasoning] and is re-exposed to onTurnDone via [pendingReasoning]
        // so the tool-call message can still carry it.
        pendingReasoning = reasoning
        if (md.isBlank()) return
        pendingReasoning = ""
        addToMessages { it += ChatMessage.assistant(md).copy(reasoning = reasoning) }
        persistChat()
    }

    /** Reasoning captured by [finalizeAssistantBlock] that has not yet been persisted. */
    private var pendingReasoning = ""

    private fun addToMessages(block: (MutableList<ChatMessage>) -> Unit) {
        val chat = chat() ?: return
        block(chat.messages)
    }

    /** Writes context bookkeeping into the persisted chat record. */
    private fun persistChat() {
        val chat = chat() ?: return
        chat.contextSummary = contextSummary
        chat.compactedMessageCount = compactedMessageCount.coerceIn(0, chat.messages.size)
        chat.updatedAt = System.currentTimeMillis()
        chat.title = chat.autoTitle()
        deps.chatsUpsert(chat)
    }

    /** Provider name captured at send time (falls back to the persisted one). */
    fun providerName(): String =
        profile?.name ?: chat()?.providerName.orEmpty()

    /** Text streamed so far in the in-flight assistant message ("" when idle). */
    fun streamingTextSnapshot(): String = streamingText.toString()

    /** Reasoning text streamed so far in the in-flight assistant message. */
    fun reasoningTextSnapshot(): String = reasoningText.toString()

    companion object {
        /** Max tool request/execute/feedback rounds per user turn. */
        private const val MAX_TOOL_ROUNDS = 200
        /** Max characters of a tool result fed back into the model history. */
        private const val MAX_TOOL_RESULT_CHARS = 8_000
        /** Max automatic retries for transient stream errors (1s/2s backoff). */
        private const val MAX_RETRIES = 2
    }
}
