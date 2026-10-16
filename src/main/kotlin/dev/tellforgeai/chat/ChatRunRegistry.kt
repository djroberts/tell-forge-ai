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
    fun engineFor(chatId: String, create: () -> ChatEngine): ChatEngine =
        engines.getOrPut(chatId) { create() }

    /** Stops the chat's engine; buffered output is kept and persisted. */
    @Synchronized
    fun stop(chatId: String) {
        engines[chatId]?.stop()
    }

    /** Cancels everything and drops the engines — project/tool-window dispose. */
    @Synchronized
    fun disposeAll() {
        engines.values.forEach { it.dispose(cancel = true) }
        engines.clear()
    }
}
