package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence-compatibility checks for the [ChatMessage.reasoning] field:
 * reasoning text must round-trip through the settings XML model object and
 * default to empty so older tellforgeai.chats.xml files load unchanged.
 */
class ChatMessageReasoningTest {

    @Test
    fun `reasoning defaults to empty`() {
        assertEquals("", ChatMessage.assistant("hello").reasoning)
        assertEquals("", ChatMessage.user("hi").reasoning)
        assertEquals("", ChatMessage.assistantWithToolCalls("", emptyList()).reasoning)
    }

    @Test
    fun `reasoning is var for the XML serializer`() {
        val prop = ChatMessage::class.java.getDeclaredField("reasoning")
        assertFalse("reasoning must not be final (val)", java.lang.reflect.Modifier.isFinal(prop.modifiers))
    }

    @Test
    fun `assistant reasoning survives copy`() {
        val msg = ChatMessage.assistant("answer").copy(reasoning = "thinking in 0,9 KB chunks…\nline two")
        assertEquals("thinking in 0,9 KB chunks…\nline two", msg.reasoning)
        assertEquals("answer", msg.content)
    }

    @Test
    fun `assistantWithToolCalls carries reasoning`() {
        val calls = listOf(ToolCall(id = "1", name = "read_file", arguments = "{}"))
        val msg = ChatMessage.assistantWithToolCalls("preamble", calls, reasoning = "thought hard")
        assertEquals("thought hard", msg.reasoning)
        assertEquals(calls, msg.toolCalls)
    }

    @Test
    fun `large reasoning bodies are kept verbatim - nothing lost`() {
        val text = "x".repeat(300_000) + "≠EOF"
        val msg = ChatMessage.assistant("done").copy(reasoning = text)
        assertEquals(text, msg.reasoning)
        assertTrue(msg.reasoning.length > 300_000)
    }
}
