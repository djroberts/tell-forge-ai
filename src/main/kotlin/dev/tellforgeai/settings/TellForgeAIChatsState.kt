package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.tellforgeai.chat.ChatRunRegistry
import dev.tellforgeai.chat.ChatSizes
import dev.tellforgeai.model.ChatSession

/**
 * Per-project persistent store of chat sessions (tellforgeai.chats.xml).
 */
@State(name = "TellForgeAIChats", storages = [Storage("tellforgeai.chats.xml")])
@Service(Service.Level.PROJECT)
class TellForgeAIChatsState(private val project: Project? = null) : PersistentStateComponent<TellForgeAIChatsState.State> {

    class State {
        var chats: MutableList<ChatSession> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        prune()
    }

    /**
     * [nowMillis] is injectable so tests can compare against a single clock
     * snapshot; production callers use the default (wall clock at call time).
     */
    internal fun prune(nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = nowMillis - RETENTION_MILLIS
        state.chats.removeAll { it.updatedAt < cutoff }
        gcByTotalSize()
    }
    /**
     * Size-based garbage collection: when the project's persisted chats exceed
     * the per-project cap ([TellForgeAIProjectState.chatGcMaxMb], default
     * 50 MB, 0 = disabled), delete the OLDEST chats first until the store fits.
     * Sizes are estimated with [ChatSizes.bytes] (sum of text-field byte
     * lengths ≈ the serialized XML payload). Chats with a running turn are
     * never evicted — GC would otherwise resurrect-free delete a chat whose
     * engine is still appending to it.
     */
    private fun gcByTotalSize() {
        if (project == null) return // direct/test construction: no project hull, no GC
        val capMb = TellForgeAIProjectState.getInstance(project).chatGcMaxMb()
        val capBytes = capMb.toLong() * 1_024 * 1_024
        if (capBytes <= 0) return
        val byOldest = state.chats.toMutableList()
        byOldest.sortBy { it.updatedAt }
        var total = ChatSizes.total(byOldest)
        if (total <= capBytes) return
        val running = ChatRunRegistry.runningIds(project).toSet()
        val iter = byOldest.iterator()
        while (total > capBytes && iter.hasNext()) {
            val chat = iter.next()
            if (chat.id in running) continue
            total -= ChatSizes.bytes(chat)
            iter.remove()
        }
        state.chats = byOldest
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
