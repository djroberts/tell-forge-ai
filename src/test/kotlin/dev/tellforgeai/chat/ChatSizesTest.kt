package dev.tellforgeai.chat

import dev.tellforgeai.llm.ChatMessage
import dev.tellforgeai.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estimates for the persisted size of chats ([ChatSizes]); the numbers drive
 * the overview's per-chat badge and project-total label, so they must scale
 * monotonically and format readably.
 */
class ChatSizesTest {

    @Test
    fun `message size tracks text length`() {
        val small = ChatSizes.bytes(ChatMessage.user("hi"))
        val big = ChatSizes.bytes(ChatMessage.user("x".repeat(10_000)))
        assertTrue(big > small + 9_000)
        assertTrue(small > 0 && small < 200)
    }

    @Test
    fun `chat size is at least the sum of messages plus overhead`() {
        val chat = ChatSession.new("test")
        chat.messages.add(ChatMessage.user("hello"))
        chat.messages.add(ChatMessage.assistant("world".repeat(100)))
        val expected = ChatSizes.bytes(chat.messages[0]) + ChatSizes.bytes(chat.messages[1])
        assertTrue(ChatSizes.bytes(chat) > expected - 16)
    }

    @Test
    fun `total sums individual chat sizes`() {
        val chats = listOf(ChatSession.new("a"), ChatSession.new("b"))
        assertEquals(
            chats.sumOf { ChatSizes.bytes(it).toLong() },
            ChatSizes.total(chats),
        )
    }

    @Test
    fun `chat size memo follows updatedAt`() {
        val chat = ChatSession.new("memo")
        chat.messages.add(ChatMessage.user("payload " + "z".repeat(2_000)))
        val first = ChatSizes.bytes(chat)
        // Same updatedAt → memo hit; values equal.
        assertEquals(first, ChatSizes.bytes(chat))
        // Content changed with a bumped updatedAt → recompute.
        chat.messages.add(ChatMessage.user("more " + "z".repeat(2_000)))
        chat.updatedAt += 1
        val second = ChatSizes.bytes(chat)
        assertTrue("size must grow after mutation", second > first)
        assertTrue(second - first >= 2_000)
        // Memo validity across ticks: bump updatedAt without content change → same value.
        chat.updatedAt += 5
        assertEquals(second, ChatSizes.bytes(chat))
    }

    @Test
    fun `format uses KB MB GB`() {
        assertEquals("500 B", ChatSizes.format(500))
        assertTrue(ChatSizes.format(5 * 1_024L).startsWith("5.0 KB"))
        assertTrue(ChatSizes.format(5L * 1_024 * 1_024).endsWith("MB"))
        assertTrue(ChatSizes.format(3L * 1_024 * 1_024 * 1_024) == "3.0 GB")
        assertTrue(ChatSizes.format(500 * 1_024L).startsWith("500"))
        assertTrue(ChatSizes.format(998 * 1_024L).startsWith("998"))
        // Cross the 1000 threshold: 1025 KB formats as "1.0 MB".
        assertTrue(ChatSizes.format(1_025L * 1_024) == "1.0 MB")
    }
}
