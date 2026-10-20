package dev.tellforgeai.chat

import dev.tellforgeai.llm.ChatMessage
import dev.tellforgeai.llm.LlmClient
import dev.tellforgeai.llm.ToolCall
import dev.tellforgeai.llm.estimatedTokens
import dev.tellforgeai.model.ChatSession
import dev.tellforgeai.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle tests for [ChatEngine] against fakes. The engine's `Deps` seam
 * replaces the LlmClient, EDT marshalling, chat store and tool dispatch, so
 * these run as plain JUnit tests without the IntelliJ test fixture.
 *
 * Covered: stop-during-compaction hang regression (the summarize callback
 * must end the turn), compaction failure falling through to trimming, the
 * compaction input being budgeted, and the safe-retry gating (no retry of
 * context overflow; no retry after streamed deltas).
 */
class ChatEngineLifecycleTest {

    private class FakeClient(
        private val summarizeResults: MutableList<Result<String>> = mutableListOf(),
        private val streamEvents: MutableList<LlmClient.Event> = mutableListOf(),
    ) : LlmClient() {
        val summarizeRequests = mutableListOf<List<ChatMessage>>()
        var summarizeCall = 0
        var streamCall = 0

        /**
         * Test hook: invoked right before a summarize RESULT is delivered —
         * the callback can stop the engine to emulate the stop-during-
         * compaction race (stop arrives after the request went out, before
         * the result is processed).
         */
        var onSummarizeResult: (FakeClient.() -> Unit)? = null

        override fun summarize(
            profile: ProviderProfile,
            planMode: Boolean,
            messages: List<ChatMessage>,
            maxTokens: Int,
            onResult: (Result<String>) -> Unit,
        ) {
            summarizeCall++
            summarizeRequests.add(messages)
            onSummarizeResult?.invoke(this)
            onResult(summarizeResults.removeFirstOrNull() ?: Result.failure(IllegalStateException("no scripted result")))
        }

        override fun stream(
            profile: ProviderProfile,
            planMode: Boolean,
            history: List<ChatMessage>,
            tools: List<Map<String, Any>>,
            onEvent: (LlmClient.Event) -> Unit,
        ) {
            streamCall++
            streamEvents.forEach { onEvent(it) }
        }
    }

    /** In-memory chat store standing in for TellForgeAIChatsState. */
    private class FakeChats {
        val chats = mutableListOf<ChatSession>()
        fun get(id: String?): ChatSession? = chats.firstOrNull { it.id == id }
        fun upsert(chat: ChatSession) {
            val idx = chats.indexOfFirst { it.id == chat.id }
            if (idx >= 0) chats[idx] = chat else chats.add(chat)
        }
    }

    private class RecordingListener : ChatEngine.Listener {
        val systems = mutableListOf<String>()
        val ended = mutableListOf<Boolean>()

        override fun onAssistantDelta(delta: String) {}
        override fun onReasoningDelta(text: String) {}
        override fun onToolPending(call: ToolCall) {}
        override fun onToolResult(call: ToolCall, result: String) {}
        override fun onSystem(text: String) { systems.add(text) }
        override fun onUsageRecorded() {}
        override fun onTurnEnded(stopped: Boolean) { ended.add(stopped) }
    }

    /**
     * Runs invokeLater blocks synchronously, draining nested blocks too
     * (the retry path nests a marshal inside a background thread, which in
     * these tests runs inline after Thread.sleep — handled via drain().
     */
    private fun syncEdt(): (block: () -> Unit) -> Unit = { block ->
        val queue = ArrayDeque<() -> Unit>()
        val pump: (() -> Unit) -> Unit = { b -> queue.add(b); drain(queue) }
        // First block runs inline; nested blocks enqueue and drain.
        queue.add(block)
        drain(queue)
        Unit
    }

    private fun drain(queue: ArrayDeque<() -> Unit>) {
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }

    private fun profile() = ProviderProfile(
        id = "p1", name = "fake", baseUrl = "https://example.invalid/v1", model = "m",
    )

    /**
     * History engineered so compaction must fire and has a legal boundary:
     * 14 uniform turns (~254 tokens per message) puts the estimated prompt
     * over the 80% trigger of the 8k window, and cutting at the 11th user
     * leaves an 8-message tail (~2k tokens) under the 50% target when the
     * ~1.2k-token system prompt and the first user are accounted for.
     */
    private fun FakeClient.engineOver(chats: FakeChats, listener: RecordingListener): ChatEngine {
        val chat = ChatSession.new("t").apply {
            repeat(14) { i ->
                messages.add(ChatMessage.user("turn ${i + 1} task: " + "u".repeat(1_000)))
                messages.add(ChatMessage.assistant("turn ${i + 1} answer: " + "a".repeat(1_000)))
            }
            contextWindow = 8_000
            contextModel = "m"
        }
        chats.chats.add(chat)
        return ChatEngine(
            project = null,
            chatId = chat.id,
            listener = listener,
            requestApproval = { _, _ -> true },
            projectContext = { "" },
            instructionsLayer = { "" },
            deps = ChatEngine.Deps(
                project = null,
                clientOverride = this,
                invokeLater = syncEdt(),
                chatsGet = chats::get,
                chatsUpsert = chats::upsert,
                toolSpecs = { emptyList() },
                toolExecute = { _, _, _, _, _, _ -> "unused" },
                backoffMs = { 0 },
            ),
        )
    }

    private fun plainEngine(chats: FakeChats, client: FakeClient, listener: RecordingListener): ChatEngine {
        chats.chats.add(ChatSession.new("t"))
        val chat = chats.chats.first()
        return ChatEngine(
            project = null,
            chatId = chat.id,
            listener = listener,
            requestApproval = { _, _ -> true },
            projectContext = { "" },
            instructionsLayer = { "" },
            deps = ChatEngine.Deps(
                project = null,
                clientOverride = client,
                invokeLater = syncEdt(),
                chatsGet = chats::get,
                chatsUpsert = chats::upsert,
                toolSpecs = { emptyList() },
                toolExecute = { _, _, _, _, _, _ -> "unused" },
                backoffMs = { 0 },
            ),
        )
    }

    // --- stop during compaction: the hang regression ---

    @Test
    fun `stop fired during compaction ends the turn instead of hanging`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val client = FakeClient(
            summarizeResults = mutableListOf(Result.success("summary memory")),
            streamEvents = mutableListOf(LlmClient.Event.Done("unused — turn was stopped")),
        )
        val engine = client.engineOver(chats, listener)
        val chat = chats.chats.first()

        client.onSummarizeResult = { engine.stop() }
        engine.send("third task", planMode = false, provider = profile())

        assertTrue("turn must have ended", listener.ended.isNotEmpty())
        assertEquals(
            "compaction must have fired before the stop",
            1,
            client.summarizeCall,
        )
        assertEquals("stopped", chat.lastTurnStatus)
        assertFalse(engine.running)
        // The summary must NOT have been applied: the turn was stopped.
        assertEquals("", chat.contextSummary)
    }

    // --- compaction failure falls through to trimming ---

    @Test
    fun `compaction failure falls back to trimming and completes the turn`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val client = FakeClient(
            summarizeResults = mutableListOf(Result.failure(IllegalStateException("provider down"))),
            streamEvents = mutableListOf(LlmClient.Event.Done("working on it")),
        )
        val engine = client.engineOver(chats, listener)
        val chat = chats.chats.first()

        engine.send("third task", planMode = false, provider = profile())

        assertTrue("stream must have run after the failed compaction", client.streamCall >= 1)
        assertEquals("complete", chat.lastTurnStatus)
        assertTrue(listener.systems.any { it.contains("compact") })
    }

    // --- compaction input is pre-fitted ---

    @Test
    fun `summarize request messages are capped by the summarize budget`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val client = FakeClient(
            summarizeResults = mutableListOf(Result.success("memory")),
            streamEvents = mutableListOf(LlmClient.Event.Done("ok")),
        )
        val engine = plainEngine(chats, client, listener)
        val chat = chats.chats.first()

        repeat(50) { i ->
            chat.messages.add(ChatMessage.user("user-$i " + "x".repeat(800)))
            chat.messages.add(ChatMessage.assistant("assistant-$i " + "y".repeat(800)))
        }
        chat.contextWindow = 8_000
        chat.contextModel = "m"

        engine.send("final task", planMode = false, provider = profile())

        assertTrue("no summarize request fired", client.summarizeRequests.isNotEmpty())
        val input = client.summarizeRequests.last()
        val totalTokens = input.fold(0) { acc, m -> acc + m.estimatedTokens() }
        // window 8000 → budget = 8000 - 4000(reserve) - 960(summary headroom)
        assertTrue(
            "summarize input too large: $totalTokens tokens in ${input.size} messages",
            totalTokens <= 3_200,
        )
    }

    // --- retry gating ---

    @Test
    fun `context overflow error is not retried - turn ends with raw error`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val client = FakeClient(
            streamEvents = mutableListOf(
                LlmClient.Event.Error("HTTP 400: This model's maximum context length is 8192 tokens"),
            ),
        )
        val engine = plainEngine(chats, client, listener)
        val chat = chats.chats.first()

        engine.send("task", planMode = false, provider = profile())

        assertEquals("exactly one stream attempt for context overflow", 1, client.streamCall)
        assertEquals("error", chat.lastTurnStatus)
        assertTrue(listener.systems.any { it.startsWith("Error:") })
    }

    @Test
    fun `transient 5xx is retried up to the cap then fails`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val err = LlmClient.Event.Error("HTTP 503 from api.example.com: service unavailable")
        val client = FakeClient(streamEvents = mutableListOf(err, err, err, err))
        val engine = plainEngine(chats, client, listener)
        val chat = chats.chats.first()

        engine.send("task", planMode = false, provider = profile())

        // attempt 1 + MAX_RETRIES (2) = 3 stream calls, then the turn fails.
        assertEquals(3, client.streamCall)
        assertEquals("error", chat.lastTurnStatus)
        assertTrue(listener.systems.any { it.contains("retrying") })
    }

    @Test
    fun `no retry once deltas have streamed`() {
        val chats = FakeChats()
        val listener = RecordingListener()
        val client = FakeClient(
            streamEvents = mutableListOf(
                LlmClient.Event.Delta("partial"),
                LlmClient.Event.Error("HTTP 503 from api.example.com: service unavailable"),
            ),
        )
        val engine = plainEngine(chats, client, listener)
        val chat = chats.chats.first()

        engine.send("task", planMode = false, provider = profile())

        assertEquals("no retry once tokens streamed", 1, client.streamCall)
        assertEquals("error", chat.lastTurnStatus)
    }
}
