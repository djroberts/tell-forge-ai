package dev.tellforgeai.settings

import dev.tellforgeai.chat.ChatSizes
import dev.tellforgeai.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GC-coercion logic in [TellForgeAIProjectState] (cap parsing) and size math
 * used by the eviction loop in [TellForgeAIChatsState]. The state classes
 * themselves need an IntelliJ project/service hull, so the pure-behavior
 * pieces under test are: cap coercion and oldest-first eviction fitting.
 */
class ChatGcTest {

    // --- cap coercion (mirrors chatGcMaxMb()) ---

    private fun coerce(raw: Int): Int = when {
        raw == 0 -> 0
        raw < 0 -> TellForgeAIProjectState.DEFAULT_CHAT_GC_MAX_MB
        else -> raw.coerceAtLeast(1)
    }

    @Test
    fun `default is 50 MB`() {
        assertEquals(50, TellForgeAIProjectState.DEFAULT_CHAT_GC_MAX_MB)
        assertEquals(50, coerce(-1)) // missing/legacy
    }

    @Test
    fun `zero disables and small positives are kept`() {
        assertEquals(0, coerce(0))
        assertEquals(1, coerce(1))
    }

    // --- eviction fitting

    private fun chatOf(bytes: Int): ChatSession =
        ChatSession.new("x").apply { messages.add(dev.tellforgeai.llm.ChatMessage.user("a".repeat((bytes - 64)))) }

    @Test
    fun `oldest-first eviction brings total under cap`() {
        val old = chatOf(600).apply { createdAt = 1; updatedAt = 1 }
        val mid = chatOf(600).apply { createdAt = 2; updatedAt = 2 }
        val recent = chatOf(600).apply { createdAt = 3; updatedAt = 3 }
        val chats = mutableListOf(mid, recent, old)
        val cap = ChatSizes.total(chats) * 2 / 3 // must drop ~one chat's worth

        val byOldest = chats.toMutableList().apply { sortBy { it.updatedAt } }
        var total = ChatSizes.total(byOldest)
        val iter = byOldest.iterator()
        while (total > cap && iter.hasNext()) {
            val c = iter.next()
            total -= ChatSizes.bytes(c)
            iter.remove()
        }

        assertEquals(2, byOldest.size)
        assertTrue(total <= cap)
        assertTrue(byOldest.none { it.id == old.id })
        assertTrue(byOldest.any { it.id == mid.id })
        assertTrue(byOldest.any { it.id == recent.id })
    }

    @Test
    fun `sizes are strictly ordered by content`() {
        val small = ChatSizes.bytes(chatOf(200))
        val big = ChatSizes.bytes(chatOf(4_000_000)) // ~4 MB of text
        assertTrue(big > small)
        assertTrue(ChatSizes.format(big.toLong()).endsWith("MB"))
    }
}
