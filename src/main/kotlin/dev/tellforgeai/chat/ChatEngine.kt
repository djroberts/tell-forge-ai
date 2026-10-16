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
    private val project: Project,
    val chatId: String,
    private val listener: Listener,
    /** Approval callbacks shared with the panel (modal dialogs on EDT). */
    private val requestApproval: (message: String, title: String) -> Boolean,
    /** Builds the cached project context snapshot (non-EDT). */
    private val projectContext: () -> String,
    /** Instructions repo layer string (cached, non-EDT). */
    private val instructionsLayer: () -> String,
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

    /** Client for THIS chat only — other chats' streams are unaffected. */
    val client = LlmClient()

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
    private var lastPromptTokens = 0
    private var contextTrimNotified = false
    private var compactionInProgress = false
    private var compactionFailedForCurrentTurn = false
    private val streamingText = StringBuilder()
    private val reasoningText = StringBuilder()

    /** Convenience: the persisted chat this engine drives. */
    private fun chat(): ChatSession? = TellForgeAIChatsState.getInstance(project).get(chatId)

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
        TellForgeAIChatsState.getInstance(project).upsert(chat)
        running = true
        stopped = false
        completedPlanMode = null
        lastTurnFailed = false
        toolRounds = 0
        lastPromptTokens = 0
        contextTrimNotified = false
        compactionInProgress = false
        compactionFailedForCurrentTurn = false
        streamingText.setLength(0)
        reasoningText.setLength(0)
        startTurn()
    }

    /** Aborts the current turn; buffered partial output is kept and persisted. */
    fun stop() {
        if (!running) return
        stopped = true
        client.cancel()
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
            val summaryInput = ConversationCompactor.summarizationMessages(oldMessages, contextSummary)
            client.summarize(provider, turnPlanMode, summaryInput, (window!! * 0.12).toInt()) { result ->
                ApplicationManager.getApplication().invokeLater {
                    compactionInProgress = false
                    if (stopped) return@invokeLater
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
                            compactionFailedForCurrentTurn = true
                            lastPromptTokens = 0
                        },
                    )
                    startTurn()
                }
            }
            return
        }
        val chat = chat() ?: run { running = false; lastTurnFailed = true; listener.onTurnEnded(false); return }
        val summarizedMessages = ConversationCompactor.applySummary(
            system, chat.messages, contextSummary, compactedMessageCount,
        )
        val budget = tokenBudget(provider)
        val messages = if (budget != null) {
            val fitted = HistoryTrimmer.fit(summarizedMessages, budget)
            if (ContextGuard.wasTrimmed(summarizedMessages, fitted)) contextTrimNotice(fitted.size, summarizedMessages.size)
            fitted
        } else {
            summarizedMessages
        }
        streamingText.setLength(0)
        reasoningText.setLength(0)
        client.stream(provider, turnPlanMode, messages, ToolRegistry.specs(project, turnPlanMode)) { event ->
            // invoked on a background thread; marshal to the EDT
            ApplicationManager.getApplication().invokeLater {
                // After Stop only finalize: the cancellation surfaces as a Done
                // (or Error) event which must still end the turn, else the
                // overview would show "running" forever.
                if (stopped) {
                    if (event is LlmClient.Event.Done || event is LlmClient.Event.Error) endTurn()
                    return@invokeLater
                }
                when (event) {
                    is LlmClient.Event.Delta -> {
                        streamingText.append(event.text)
                        listener.onAssistantDelta(event.text)
                    }
                    is LlmClient.Event.Reasoning -> {
                        reasoningText.append(event.text)
                        listener.onReasoningDelta(event.text)
                    }
                    is LlmClient.Event.Done -> onTurnDone(event)
                    is LlmClient.Event.Error -> {
                        lastTurnFailed = true
                        finalizeAssistantBlock()
                        listener.onSystem("Error: ${event.message}")
                        endTurn()
                    }
                }
            }
        }
    }

    private fun contextTrimNotice(keptMessages: Int, totalMessages: Int) {
        if (contextTrimNotified) return
        contextTrimNotified = true
        listener.onSystem(ContextGuard.trimNotice(totalMessages, keptMessages))
    }

    /** Per-request prompt-token budget, null when no window is known. */
    private fun tokenBudget(provider: ProviderProfile): Int? =
        contextWindow(provider)?.let { it - ContextGuard.RESERVE_TOKENS }

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
                TellForgeAIChatsState.getInstance(project).upsert(chat)
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
                addToMessages { it += ChatMessage.assistant(event.fullText) }
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
                addToMessages { it += ChatMessage.assistant(event.fullText) }
            }
            addToMessages {
                it += ChatMessage.user(
                    "Tool loop stopped after $MAX_TOOL_ROUNDS rounds. Summarize what you have and give a final answer now."
                )
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
        addToMessages { it += ChatMessage.assistantWithToolCalls(event.fullText, event.toolCalls) }
        persistChat()
        val calls = event.toolCalls
        calls.forEach { listener.onToolPending(it) }

        // Tool execution on background thread; results feed the next turn on EDT.
        Thread {
            try {
                val results = calls.map { call ->
                    val result = ToolRegistry.execute(
                        project, call.name, call.arguments, turnPlanMode,
                        onDownloadRequested = { request ->
                            requestApproval(
                                "TellForgeAI wants to download a binary file:\n\n$request\n\nAllow?", "Download binary",
                            )
                        },
                        onCommandRequested = { command ->
                            if (!dev.tellforgeai.settings.TellForgeAISettings.getInstance().askBeforeRunCommands()) true
                            else requestApproval(
                                "TellForgeAI wants to run this command:\n\n$command\n\nAllow?", "Run command",
                            )
                        },
                        // Always-on gate for instructions-repo commits+pushes.
                        onCommitRequested = { diff ->
                            requestApproval(
                                "TellForgeAI wants to commit and push this change to the instructions repo:\n\n$diff",
                                "Push instructions change",
                            )
                        },
                    )
                    call to result
                }
                ApplicationManager.getApplication().invokeLater {
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
                            ApplicationManager.getApplication().invokeLater {
                                if (running && !stopped) startTurn()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
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
        listener.onTurnEnded(wasStopped)
    }

    /**
     * Persists the in-flight assistant message: the accumulated streaming text
     * becomes a history entry if it has content.
     */
    private fun finalizeAssistantBlock() {
        val md = streamingText.toString()
        streamingText.setLength(0)
        reasoningText.setLength(0)
        if (md.isBlank()) return
        addToMessages { it += ChatMessage.assistant(md) }
        persistChat()
    }

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
        TellForgeAIChatsState.getInstance(project).upsert(chat)
    }

    /** Provider name captured at send time (falls back to the persisted one). */
    fun providerName(): String =
        profile?.name ?: chat()?.providerName.orEmpty()

    /** Text streamed so far in the in-flight assistant message ("" when idle). */
    fun streamingTextSnapshot(): String = streamingText.toString()

    companion object {
        /** Max tool request/execute/feedback rounds per user turn. */
        private const val MAX_TOOL_ROUNDS = 200
        /** Max characters of a tool result fed back into the model history. */
        private const val MAX_TOOL_RESULT_CHARS = 8_000
    }
}
