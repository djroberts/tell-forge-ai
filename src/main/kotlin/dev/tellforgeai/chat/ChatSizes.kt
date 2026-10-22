package dev.tellforgeai.chat

import dev.tellforgeai.llm.ChatMessage
import dev.tellforgeai.model.ChatSession
import java.nio.charset.StandardCharsets

/**
 * Disk-usage estimates for persisted chats (tellforgeai.chats.xml).
 *
 * The XML payload is dominated by the text fields — message content,
 * reasoning, tool-call arguments and base64 image payloads — so summing
 * their UTF-8 byte lengths (plus small per-object overhead) is a close
 * approximation of what serialization writes. Used for the per-chat size
 * badge and the overview total, to spot chats that are getting out of hand.
 */
object ChatSizes {

    /** UTF-8 byte length of a string (0 for null/empty). */
    private fun bytes(s: String?): Int = s?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0

    /** Approximate persisted size, in bytes, of a single message. */
    fun bytes(m: ChatMessage): Int =
        bytes(m.content) + bytes(m.reasoning) + bytes(m.toolCallId) +
            m.toolCalls.sumOf { bytes(it.arguments) + bytes(it.name) + bytes(it.id) + 16 } +
            m.images.sumOf { bytes(it) } +
            m.referencedPaths.sumOf { bytes(it) } + 32

    /** Approximate persisted size, in bytes, of a chat session. */
    fun bytes(chat: ChatSession): Int =
        chat.messages.sumOf { bytes(it) } + bytes(chat.contextSummary) +
            bytes(chat.title) + bytes(chat.providerName) + 128

    /** Approximate total persisted size, in bytes, of many chats. */
    fun total(chats: Collection<ChatSession>): Long = chats.sumOf { bytes(it).toLong() }

    /** Human-readable size: B, KB, MB or GB with one decimal above 10 units. */
    fun format(bytes: Long): String {
        // Locale-independent decimal point — "%.1f" with the default locale can
        // render a comma ("5,0 KB"), which looks odd mid-sentence.
        val dec = { v: Double -> java.util.Locale.ROOT.let { String.format(it, "%.1f", v) } }
        val int = { v: Double -> java.util.Locale.ROOT.let { String.format(it, "%.0f", v) } }
        if (bytes < 1_000) return "$bytes B"
        val kb = bytes / 1_024.0
        if (kb < 1_000) return (if (kb >= 10) int(kb) else dec(kb)) + " KB"
        val mb = kb / 1_024.0
        if (mb < 1_000) return (if (mb >= 10) int(mb) else dec(mb)) + " MB"
        val gb = mb / 1_024.0
        return (if (gb >= 10) int(gb) else dec(gb)) + " GB"
    }
}
