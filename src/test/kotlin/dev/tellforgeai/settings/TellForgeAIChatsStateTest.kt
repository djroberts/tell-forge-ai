package dev.tellforgeai.settings

import dev.tellforgeai.model.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention behaviour: chat sessions untouched for 30 days are pruned from
 * the persistent store (TellForgeAIChatsState).
 */
class TellForgeAIChatsStateTest {

    private val day = 24L * 60 * 60 * 1000

    private fun chat(updatedAt: Long): ChatSession =
        ChatSession.new("chat").apply { this.updatedAt = updatedAt }

    @Test
    fun `loadState removes chats older than 30 days`() {
        val chatsState = TellForgeAIChatsState()
        chatsState.loadState(TellForgeAIChatsState.State().apply {
            chats.add(chat(System.currentTimeMillis() - 30 * day - 1))
        })
        assertTrue(chatsState.all().isEmpty())
    }

    @Test
    fun `chat at exactly the 30 day cutoff survives`() {
        val chatsState = TellForgeAIChatsState()
        chatsState.loadState(TellForgeAIChatsState.State().apply {
            chats.add(chat(System.currentTimeMillis() - 30 * day))
        })
        assertEquals(1, chatsState.all().size)
    }

    @Test
    fun `recent chats survive loadState pruning`() {
        val chatsState = TellForgeAIChatsState()
        chatsState.loadState(TellForgeAIChatsState.State().apply {
            chats.add(chat(System.currentTimeMillis() - day))
        })
        assertEquals(1, chatsState.all().size)
    }

    @Test
    fun `upsert prunes stale chats`() {
        val state = TellForgeAIChatsState()
        val stale = chat(System.currentTimeMillis() - 40 * day)
        state.upsert(stale)
        state.upsert(chat(System.currentTimeMillis()))
        assertFalse(state.all().any { it.id == stale.id })
        assertEquals(1, state.all().size)
    }

    @Test
    fun `fresh upserted chat is not pruned by its own upsert`() {
        val state = TellForgeAIChatsState()
        val fresh = ChatSession.new("fresh")
        state.upsert(fresh)
        assertTrue(state.all().any { it.id == fresh.id })
    }
}
