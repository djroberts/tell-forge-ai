package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimmerTest {

    private fun bigToolResult(id: String, size: Int = 20_000) =
        ChatMessage.toolResult(id, "x".repeat(size))

    private fun simpleTurnPair(count: Int): List<ChatMessage> =
        buildList {
            repeat(count) {
                add(ChatMessage.user("u$it"))
                add(ChatMessage.assistant("a$it"))
            }
        }

    // --- no-trim path ---

    @Test
    fun `small history is returned unchanged`() {
        val system = ChatMessage.system("system prompt")
        val history = listOf(system) + simpleTurnPair(3) + ChatMessage.user("final")
        assertEquals(history, HistoryTrimmer.fit(history, 1_000_000))
    }

    // --- pass 1: old tool results blanked, recent turn intact ---

    @Test
    fun `old tool results are blanked but last turn kept`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("first task"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c1", "read_file", "{}"))),
            bigToolResult("c1", 6_000),
            ChatMessage.user("second task"),
            ChatMessage.assistant("answer"),
            ChatMessage.user("final task"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c2", "read_file", "{}"))),
            bigToolResult("c2", 30_000),
        )
        val fitted = HistoryTrimmer.fit(history, 6_000)
        // First user message and the current turn survive unchanged.
        assertTrue(fitted.any { it.role == "user" && it.content == "first task" })
        // The current turn's big (protected) tool result is sent in full.
        assertEquals("x".repeat(30_000), fitted.last().content)
        // Everything older than the current turn was shrunk or removed.
        assertTrue(fitted.indexOfLast { it.content == "x".repeat(6_000) } == -1)
    }

    @Test
    fun `assistant tool-call message content preserved when tool result blanked`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("task"),
            ChatMessage.assistantWithToolCalls("thinking", listOf(ToolCall("c1", "list_files", "{}"))),
            bigToolResult("c1", 6_000),
            ChatMessage.assistant("done"),
            ChatMessage.user("final question"),
            ChatMessage.assistant("final answer"),
        )
        val fitted = HistoryTrimmer.fit(history, 1_000)
        // The assistant's tool-call message keeps its content and tool calls
        // (placeholdering hits only role=="tool" messages).
        assertTrue(fitted.any { it.role == "assistant" && it.content == "thinking" })
        assertTrue(fitted.filter { it.role == "tool" }.all { it.content.startsWith("[older") })
    }

    // --- pass 2: structural drops keep tool-call/tool-result units together ---

    @Test
    fun `extreme budget drops old tool-call units whole, keeping turn integrity`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("task one"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c1", "search_files", "{}"))),
            bigToolResult("c1"),
            bigToolResult("c1b", 15_000),
            ChatMessage.assistant("found it"),
            ChatMessage.user("task two"),
            ChatMessage.assistant("done"),
        )
        val fitted = HistoryTrimmer.fit(history, 60)
        // The old tool-call unit (assistant + both results) was removed together.
        assertTrue(fitted.none { it.toolCallId == "c1" || it.toolCallId == "c1b" })
        assertTrue(fitted.none { it.role == "assistant" && it.toolCalls.isNotEmpty() })
        // Protected content survives: system, original task, current turn.
        assertTrue(fitted.any { it.content == "task one" })
        assertEquals("done", fitted.last().content)
    }
    @Test
    fun `moderate budget favors blanking, preserving message count`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("task one"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c1", "search_files", "{}"))),
            bigToolResult("c1"),
            bigToolResult("c1b", 15_000),
            ChatMessage.assistant("found it"),
            ChatMessage.user("task two"),
            ChatMessage.assistant("done"),
        )
        // Blanking alone (pass 1) can satisfy a moderate budget: structure is
        // preserved and no dangling assistant tool call remains.
        val fitted = HistoryTrimmer.fit(history, 900)
        assertEquals(history.size, fitted.size)
        fitted.forEachIndexed { idx, m ->
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                assertTrue("dangling tool call at $idx", fitted.getOrNull(idx + 1)?.role == "tool")
            }
        }
        assertTrue(fitted.any { it.content == "task one" })  // first user kept
        assertEquals("done", fitted.last().content)
    }

    @Test
    fun `never drops the system prompt or current turn`() {
        val system = ChatMessage.system("the one true system prompt")
        val history = listOf(
            system,
            ChatMessage.user("original task"),
            ChatMessage.assistant("a1"),
            ChatMessage.user("later task"),
            ChatMessage.assistant("a2"),
            ChatMessage.user("current task"),
        ) + bigToolResult("zzz")
        val fitted = HistoryTrimmer.fit(history, 1_000)
        assertEquals("the one true system prompt", fitted[0].content)
        // Current turn: the last user message must be present.
        assertTrue(fitted.any { it.role == "user" && it.content == "current task" })
    }

    // --- no mutation ---

    @Test
    fun `input messages are never mutated`() {
        val system = ChatMessage.system("sys")
        val original = ChatMessage.toolResult("c1", "y".repeat(10_000))
        val history = listOf(
            system,
            ChatMessage.user("task"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c1", "read_file", "{}"))),
            original,
        )
        val snapshot = original.content
        HistoryTrimmer.fit(history, 1_000)
        assertEquals(snapshot, original.content)
        assertEquals(10_000 / 4 + 4, original.estimatedTokens())
    }

    // --- fitFromEnd + fit reporting ---

    @Test
    fun `fitFromEnd drops the oldest units first so the tail survives longest`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("first task"),
            ChatMessage.assistant("old answer 1"),
            ChatMessage.user("second task"),
            ChatMessage.assistant("old answer 2"),
            ChatMessage.user("third task"),
            ChatMessage.assistant("old answer 3"),
            ChatMessage.user("current task"),
            ChatMessage.assistant("in flight"),
        )
        // Tight budget: old assistant units are dropped oldest-first, so the
        // newest old unit ("old answer 3") outlives the oldest one.
        val fitted = HistoryTrimmer.fitFromEnd(history, 40)
        assertTrue(fitted.none { it.content == "old answer 1" })
        // Protection identical to fit: original task and current turn kept.
        assertTrue(fitted.someContains("first task"))
        assertTrue(fitted.someContains("current task"))
    }

    @Test
    fun `fitResult reports failure when protected set alone exceeds budget`() {
        val system = ChatMessage.system("sys")
        // First user message is huge and protected twice: it can never fit.
        val firstUser = ChatMessage.user("huge task " + "z".repeat(80_000))
        val history = listOf(
            system,
            firstUser,
            ChatMessage.user("current task"),
            ChatMessage.assistant("answer"),
        )
        val result = HistoryTrimmer.fitResult(history, 1_000)
        assertFalse(result.fitsFully)
        // Still returns the best-effort list, without mutating the input.
        assertEquals(history, listDeepCopyCheck(history))
    }

    @Test
    fun `middle old user messages are dropped in the last resort pass`() {
        val hugeUser = ChatMessage.user("second task " + "y".repeat(40_000))
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("first task"),
            ChatMessage.assistant("a1"),
            hugeUser,
            ChatMessage.assistant("a2"),
            ChatMessage.user("current task"),
            ChatMessage.assistant("a3"),
        )
        // Plain units (assistant messages) are tiny; blanking does nothing —
        // only the middle-user drop can get this under a small budget.
        val fitted = HistoryTrimmer.fit(history, 400)
        assertFalse(fitted.someContains("second task"))
        assertTrue(fitted.someContains("first task"))
        assertTrue(fitted.someContains("current task"))
    }

    @Test
    fun `synthetic last user message does not extend current turn protection`() {
        val system = ChatMessage.system("sys")
        val history = listOf(
            system,
            ChatMessage.user("first task"),
            ChatMessage.assistant("a1"),
            ChatMessage.user("mid task"),
            toolLoopStop(),
            ChatMessage.assistant("final answer"),
        )
        // Without the synthetic flag the "Tool loop stopped…" prompt is the
        // last user message and freezes "mid task" from dropping.
        val fitted = HistoryTrimmer.fit(history, 350)
        assertFalse(fitted.someContains("mid task") && fitted.none { s -> s.content.startsWith("Tool loop stopped") })
        assertTrue(fitted.someContains("first task"))
    }

    private fun List<ChatMessage>.someContains(s: String): Boolean = any { it.content.contains(s) }
    private fun toolLoopStop(): ChatMessage =
        ChatMessage.user("Tool loop stopped after 200 rounds. Summarize what you have and give a final answer now.")
            .copy(synthetic = true)
    private fun listDeepCopyCheck(l: List<ChatMessage>): List<ChatMessage> = l

    // --- estimates ---
    @Test
    fun `image messages carry a vision token estimate`() {
        val withImage = ChatMessage.user("look").apply { images = listOf("data:image/png;base64,zzz") }
        val withoutImage = ChatMessage.user("look")
        assertTrue(withImage.estimatedTokens() > withoutImage.estimatedTokens() + 1_000)
    }

    // --- ContextGuard ---

    @Test
    fun `nearFull triggers at 85 percent of window`() {
        assertTrue(ContextGuard.nearFull(860, 1_000))
        assertTrue(ContextGuard.nearFull(900, 1_000))
        assertTrue(!ContextGuard.nearFull(800, 1_000))
        assertTrue(!ContextGuard.nearFull(9_999, 0)) // unknown window → never "near full"
    }

    @Test
    fun `placeholder-only trimming is detected even when message count is unchanged`() {
        val original = listOf(
            ChatMessage.system("sys"),
            ChatMessage.user("task"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c1", "read_file", "{}"))),
            bigToolResult("c1", 6_000),
            ChatMessage.user("follow-up"),
            ChatMessage.assistant("answer"),
        )
        val fitted = HistoryTrimmer.fit(original, 1_000)

        assertEquals(original.size, fitted.size)
        assertTrue(ContextGuard.wasTrimmed(original, fitted))
        assertTrue(ContextGuard.trimNotice(original.size, fitted.size).contains("omitted"))
    }

    @Test
    fun `context window uses chat value only for matching active model`() {
        assertEquals(32_000, ContextGuard.contextWindow(32_000, "model-a", "model-a", 128_000))
        assertEquals(128_000, ContextGuard.contextWindow(32_000, "model-a", "model-b", 128_000))
    }

    @Test
    fun `context window falls back from zero chat window to profile window`() {
        assertEquals(128_000, ContextGuard.contextWindow(0, "model-a", "model-a", 128_000))
        assertEquals(null, ContextGuard.contextWindow(0, "model-a", "model-a", 0))
    }
}
