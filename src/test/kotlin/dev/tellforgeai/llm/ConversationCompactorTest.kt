package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {
    private fun turns(count: Int, chars: Int = 400): List<ChatMessage> = buildList {
        repeat(count) { index ->
            add(ChatMessage.user("user-$index " + "u".repeat(chars)))
            add(ChatMessage.assistant("assistant-$index " + "a".repeat(chars)))
        }
    }

    @Test
    fun `threshold activates at eighty percent`() {
        assertFalse(ConversationCompactor.shouldCompact(799, 1_000))
        assertTrue(ConversationCompactor.shouldCompact(800, 1_000))
        assertFalse(ConversationCompactor.shouldCompact(10_000, 0))
    }

    @Test
    fun `selects completed old turns but retains initial task and latest two user turns`() {
        val messages = turns(8)
        val boundary = ConversationCompactor.boundary(messages, 0, 10, 2_000)
        assertNotNull(boundary)
        val cutoff = boundary!!
        assertTrue(cutoff > 0)
        assertEquals("user", messages[cutoff].role)
        assertTrue(messages[cutoff].content.startsWith("user-4 "))
        assertTrue(messages.drop(cutoff).any { it.content.startsWith("user-6 ") })
        assertTrue(messages.drop(cutoff).any { it.content.startsWith("user-7 ") })
    }

    @Test
    fun `does not compact if history cannot be reduced under target`() {
        val messages = listOf(ChatMessage.user("task"), ChatMessage.assistant("answer"), ChatMessage.user("now"))
        assertNull(ConversationCompactor.boundary(messages, 0, 10, 100))
    }

    @Test
    fun `summary transformation retains original history and adds memory ahead of recent turns`() {
        val source = turns(4)
        val original = source.map { it.copy() }
        val system = ChatMessage.system("system")
        val sent = ConversationCompactor.applySummary(system, source, "important choice", 4)

        assertEquals(original, source)
        assertEquals("system", sent.first().role)
        assertTrue(sent.first().content.contains("important choice"))
        assertEquals(source.first(), sent[1])
        assertEquals(source.drop(4), sent.drop(2))
        assertEquals(6, sent.size)
    }

    @Test
    fun `summary input includes prior summary and transcript without image base64`() {
        val image = ChatMessage.user("inspect").apply { images = listOf("data:image/png;base64,secret-payload") }
        val input = ConversationCompactor.summarizationMessages(
            listOf(image, ChatMessage.toolResult("call", "tool output")), "prior memory",
        )

        assertEquals(2, input.size)
        assertTrue(input[1].content.contains("prior memory"))
        assertTrue(input[1].content.contains("tool output"))
        assertTrue(input[1].content.contains("[1 image(s) attached]"))
        assertFalse(input[1].content.contains("secret-payload"))
    }

    @Test
    fun `compaction only advances after a full user boundary and never selects inside tool loop`() {
        val messages = listOf(
            ChatMessage.user("initial"),
            ChatMessage.assistantWithToolCalls("", listOf(ToolCall("c", "read_file", "{}"))),
            ChatMessage.toolResult("c", "result"),
            ChatMessage.assistant("done"),
            ChatMessage.user("second"),
            ChatMessage.assistant("done"),
            ChatMessage.user("current"),
        )
        // Only two user turns exist, so there's no safe prior completed turn to compact.
        assertNull(ConversationCompactor.boundary(messages, 0, 1, 10_000))
    }
}
