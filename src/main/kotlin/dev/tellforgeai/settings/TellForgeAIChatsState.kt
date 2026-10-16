package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.tellforgeai.model.ChatSession

/**
 * Per-project persistent store of chat sessions (tellforgeai.chats.xml).
 */
@State(name = "TellForgeAIChats", storages = [Storage("tellforgeai.chats.xml")])
@Service(Service.Level.PROJECT)
class TellForgeAIChatsState : PersistentStateComponent<TellForgeAIChatsState.State> {

    class State {
        var chats: MutableList<ChatSession> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        prune()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        state.chats.removeAll { it.updatedAt < cutoff }
    }

    /** All chats, most recently updated first. */
    fun all(): List<ChatSession> = state.chats.sortedByDescending { it.updatedAt }

    fun get(id: String?): ChatSession? = state.chats.firstOrNull { it.id == id }

    fun upsert(chat: ChatSession) {
        val idx = state.chats.indexOfFirst { it.id == chat.id }
        if (idx >= 0) state.chats[idx] = chat else state.chats.add(chat)
        prune()
    }

    fun delete(id: String) {
        state.chats.removeAll { it.id == id }
    }

    companion object {
        /**
         * Retention window: chat sessions whose [ChatSession.updatedAt] is
         * older than this are deleted. Always enforced; not user-configurable.
         */
        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        @JvmStatic
        fun getInstance(project: Project): TellForgeAIChatsState =
            project.getService(TellForgeAIChatsState::class.java)
    }
}
