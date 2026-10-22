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

    /**
     * Loads state and re-prunes against a deterministic injected clock so the
     * cutoff is exact — no wall-clock skew between test setup and prune, which
     * previously flaked the boundary case on CI.
     */
    private fun pruned(updatedAt: Long, nowMillis: Long): TellForgeAIChatsState {
        val chatsState = TellForgeAIChatsState()
        chatsState.loadState(TellForgeAIChatsState.State().apply { chats.add(chat(updatedAt)) })
        chatsState.prune(nowMillis = nowMillis)
        return chatsState
    }

    @Test
    fun `chat older than the 30 day cutoff is removed`() {
        // updatedAt = exactly "now - 31 days", cutoff = "now - 30 days":
        // updatedAt < cutoff → pruned.
        val now = System.currentTimeMillis()
        assertTrue(pruned(now - 31 * day, now).all().isEmpty())
    }

    @Test
    fun `chat at exactly the 30 day cutoff survives`() {
        // updatedAt = exactly "now - 30 days", cutoff = "now - 30 days":
        // updatedAt < cutoff is false (strict) → survives.
        val now = System.currentTimeMillis()
        assertEquals(1, pruned(now - 30 * day, now).all().size)
    }

    @Test
    fun `recent chats survive loadState pruning`() {
        val now = System.currentTimeMillis()
        assertEquals(1, pruned(now - day, now).all().size)
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
