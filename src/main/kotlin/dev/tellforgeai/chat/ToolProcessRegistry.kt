package dev.tellforgeai.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Runtime-only registry of OS processes and pending approval gates owned by a
 * chat's current turn, so Stop can kill in-flight commands and dismiss stuck
 * dialogs instead of waiting out their timeouts (up to 300 s per command, 120 s
 * per unanswered dialog). Keyed by chat id; cleared when each entry is finished.
 */
object ToolProcessRegistry {

    private val processes = ConcurrentHashMap<String, MutableList<Process>>()
    private val approvalLatches = ConcurrentHashMap<String, MutableList<CountDownLatch>>()
    private val abortedProcesses = ConcurrentHashMap<String, MutableList<Process>>()

    /** Registers an OS process for [chatId]; remove via [unregister] or [abort]. */
    fun register(chatId: String, process: Process) {
        processes.computeIfAbsent(chatId) { CopyOnWriteArrayList() }.add(process)
    }

    fun unregister(chatId: String, process: Process) {
        processes[chatId]?.remove(process)
    }

    /**
     * Registers a latched approval wait for [chatId]; [abort] counts it down so
     * a modal left unanswered is skipped as "declined" instead of blocking 120 s.
     */
    fun registerApprovalWait(chatId: String, latch: CountDownLatch) {
        approvalLatches.computeIfAbsent(chatId) { CopyOnWriteArrayList() }.add(latch)
    }

    /** Kills all registered processes and releases approval gates for [chatId]. */
    fun abort(chatId: String) {
        approvalLatches.remove(chatId)?.forEach { it.countDown() }
        processes.remove(chatId)?.forEach {
            try {
                abortedProcesses.computeIfAbsent(chatId) { CopyOnWriteArrayList() }.add(it)
                it.destroyForcibly()
            } catch (_: Exception) {
                // Process already exited between unregister and destroy — harmless.
            }
        }
    }

    /**
     * True when [abort] force-killed [process] and the owner hasn't reported it
     * via [clearAborted] yet; lets the caller say "aborted by user" not "timed out".
     */
    fun wasAborted(chatId: String, process: Process): Boolean =
        abortedProcesses[chatId]?.contains(process) == true

    /** Drops [latch] from the approval-wait set once it has been served. */
    fun clearApprovalWait(chatId: String, latch: CountDownLatch) {
        approvalLatches[chatId]?.remove(latch)
    }

    /** Drops [process] from the aborted set once its owner has reported it. */
    fun clearAborted(chatId: String, process: Process) {
        abortedProcesses[chatId]?.remove(process)
    }
}
