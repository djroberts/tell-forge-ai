package dev.tellforgeai.model

import com.intellij.util.xmlb.annotations.Transient

/**
 * A named OpenAI-compatible provider configuration.
 *
 * NOTE: properties must be `var` (not `val`) — IntelliJ's settings XML
 * serializer writes an empty element for immutable Kotlin data classes,
 * which silently drops saved profiles.
 *
 * SECURITY: [apiKey] is @Transient — it is never serialized into tellforgeai.xml.
 * The secret lives in the IDE PasswordSafe ([dev.tellforgeai.settings.ApiKeyStore]),
 * keyed by profile id.
 */
data class ProviderProfile(
    var id: String = "",
    var name: String = "",
    /**
     * Which wire protocol to use ([ApiProtocol]). Defaults to the classic
     * chat/completions shape so previously-serialized profiles load unchanged.
     */
    var apiProtocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE,
    var baseUrl: String = "",
    @Transient var apiKey: String = "",
    var model: String = "",
    /** Optional model used specifically in Foretell mode; falls back to [model]. */
    var planModel: String? = null,
    /**
     * Context window size in tokens, used for the context usage meter.
     * Auto-detected from `/models` on "Test connection" when the provider
     * exposes it; otherwise falls back to the user's value or the default.
     */
    var contextWindow: Int = 128_000,
    /**
     * Optional reasoning effort hint sent as `reasoning_effort` on the request
     * (e.g. "low", "medium", "high"). Only honored by OpenAI-style reasoning
     * models (OpenAI o-series/GPT-5, OpenRouter, vLLM/SGLang...); other
     * providers ignore it, so it is omitted entirely when null/blank.
     */
    var reasoningEffort: String? = null,
) {
    fun modelFor(planMode: Boolean): String {
        val pm = planModel
        return if (planMode && !pm.isNullOrBlank()) pm else model
    }

    /** Base URL with any trailing slash removed — safe to append a path. */
    fun trimmedBaseUrl(): String = baseUrl.trim().removeSuffix("/")
}
