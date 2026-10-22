package dev.tellforgeai.chat

import com.intellij.openapi.project.Project

/**
 * Per-chat runtime turn registry.
 *
 * A turn starts when the user sends a message and ends on the final answer, an
 * error, the round limit, or an explicit Stop. Engines are tracked PER CHAT,
 * so leaving a chat (back to the overview or into another chat) no longer
 * cancels its run — it keeps going in the background and shows up as
 * "running" (animated spinner) in the chat overview, with the provider that
 * started the turn.
 *
 * Runtime-only: nothing here is persisted.
 */
class ChatRunRegistry(private val project: Project) {

    private val engines = HashMap<String, ChatEngine>()

    @Synchronized
    fun engine(chatId: String?): ChatEngine? = chatId?.let { engines[it] }

    @Synchronized
    fun isRunning(chatId: String?): Boolean =
        chatId != null && engines[chatId]?.running == true

    /** Ids of chats with an active turn — snapshot for the overview ticker. */
    @Synchronized
    fun runningIds(): List<String> =
        engines.entries.filter { it.value.running }.map { it.key }

    companion object {
        /**
         * Process-wide map of project → running chat-id snapshots. Each panel
         * owns its own registry instance (no project-level service exists), so
         * consumers outside the panel — chat-store GC — need this aggregation
         * to protect actively-running chats from eviction. Entries are
         * refreshed by each registry on every state change and dropped on
         * dispose; empties are harmless.
         */
        private val runningByProject = java.util.concurrent.ConcurrentHashMap<Project, Set<String>>()

        /** Union of all running chat ids for [project], across panel registries. */
        @JvmStatic
        fun runningIds(project: Project): List<String> =
            runningByProject[project].orEmpty().sorted()
    }

    /** True when the chat's last turn ended normally (for the "complete" icon). */
    @Synchronized
    fun hasCompleted(chatId: String?): Boolean =
        chatId?.let { engines[it] }?.let { !it.running && it.completedPlanMode != null } == true

    /** True when the chat's last turn was stopped by the user. */
    @Synchronized
    fun wasStopped(chatId: String?): Boolean =
        chatId != null && engines[chatId]?.stopped == true

    /**
     * Creates and registers the engine for [chatId] if it doesn't exist yet;
     * keeps the existing engine otherwise, so background state survives
     * repeated panel interactions.
     */
    @Synchronized
    fun engineFor(chatId: String, create: () -> ChatEngine): ChatEngine {
        val engine = engines.getOrPut(chatId) { create() }
        refreshRunningSnapshot()
        return engine
    }

    /** Stops the chat's engine; buffered output is kept and persisted. */
    @Synchronized
    fun stop(chatId: String) {
        engines[chatId]?.stop()
        refreshRunningSnapshot()
    }

    /** Cancels everything and drops the engines — project/tool-window dispose. */
    @Synchronized
    fun disposeAll() {
        engines.values.forEach { it.dispose(cancel = true) }
        engines.clear()
        runningByProject.remove(project)
    }

    /** Pushes this registry's running-id set into the process-wide map. */
    private fun refreshRunningSnapshot() {
        runningByProject[project] = runningIds().toSet()
    }
}
