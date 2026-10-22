package dev.tellforgeai.model

import kotlinx.serialization.Serializable

/**
 * A single persisted chat conversation.
 *
 * NOTE: properties must be `var` (not `val`) — IntelliJ's settings XML
 * serializer cannot populate immutable properties, which silently drops
 * them from tellforgeai.chats.xml (same constraint as ProviderProfile).
 */
@Serializable
data class ChatSession(
    var id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "New chat",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var messages: MutableList<dev.tellforgeai.llm.ChatMessage> = mutableListOf(),
    /** Lossy model-generated summary of messages before compactedMessageCount; full messages remain intact. */
    var contextSummary: String = "",
    /** Number of leading messages represented by contextSummary. */
    var compactedMessageCount: Int = 0,
    /** Provider-reported cumulative prompt/input tokens across every API round. */
    var incomingTokens: Long = 0,
    /** Subset of incoming tokens reported as cache reads by the provider. */
    var cachedInputTokens: Long = 0,
    /** Provider-reported cumulative completion/output tokens across every API round. */
    var outgoingTokens: Long = 0,
    /** Context window associated with this chat's model, when discoverable. */
    var contextWindow: Int = 0,
    /** Rates in USD per million tokens; null unless a web lookup resolved them. */
    var inputPricePerMillion: Double? = null,
    var cachedInputPricePerMillion: Double? = null,
    var outputPricePerMillion: Double? = null,
    var pricingSource: String = "",
    var pricingModel: String = "",
    var pricingCheckedAt: Long = 0,
    var contextModel: String = "",
    /** Provider profile id used for this chat's turns; empty = project default. */
    var providerProfileId: String = "",
    /** Display name of [providerProfileId], cached for the overview list. */
    var providerName: String = "",
    /** Persisted Foretell/Forge mode for this chat ("plan" or "act"). */
    var mode: String = "",
    /** True if this chat accumulated token usage under more than one model. */
    var mixedModelsUsage: Boolean = false,
    /** Status of the chat's last finished turn: "complete" | "stopped" | "error",
     *  "" while running / before the first turn. Rendered as the end-of-turn
     *  marker in the transcript and the overview "complete" indicator. */
    var lastTurnStatus: String = "",
    /** Whether that last turn ran in Foretell (plan) mode, for the marker label. */
    var lastTurnPlanMode: Boolean = false,
) {
    // --- memoized size estimate (transient, never serialized) ---

    @kotlinx.serialization.Transient
    private var sizeMemoAt: Long = -1

    @kotlinx.serialization.Transient
    private var sizeMemoBytes: Long = 0

    /**
     * Memoized payload-byte estimate, valid while [updatedAt] is unchanged.
     * Every mutation path (engine persistChat, panel edits) bumps updatedAt,
     * so the estimate is recomputed exactly when the content changed and
     * cached otherwise — prune()/GC run O(all chats × messages) work per
     * upsert, which became noticeable with many large chats.
     */
    fun memoizedBytes(): Long? = if (sizeMemoAt == updatedAt) sizeMemoBytes else null

    fun putMemoizedBytes(bytes: Long) {
        sizeMemoBytes = bytes
        sizeMemoAt = updatedAt
    }

    companion object {
        fun new(title: String): ChatSession {
            val now = System.currentTimeMillis()
            return ChatSession(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /** Derives a list title from the first user message, trimmed to ~48 chars. */
    fun autoTitle(): String =
        messages.firstOrNull { it.role == "user" }?.content
            ?.replace(Regex("\\s+"), " ")?.trim()
            ?.take(48)?.ifEmpty { "New chat" }
            ?: title.ifEmpty { "New chat" }
}
