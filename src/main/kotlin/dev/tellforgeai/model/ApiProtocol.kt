package dev.tellforgeai.model

/**
 * Wire protocol a profile talks. This is about the *endpoint shape*, not the
 * vendor — e.g. Gemini is served over [OPENAI_COMPATIBLE] via its OpenAI
 * compatibility base URL (`https://generativelanguage.googleapis.com/v1beta/openai/`).
 *
 * [modelsPath] is the path appended to the profile's base URL to list models
 * (used by Test connection, the model-fetch dropdown and context-window
 * detection). Not every compatible backend exposes it at the same path, so it
 * lives in the protocol config rather than being hardcoded.
 */
enum class ApiProtocol(
    val displayName: String,
    val modelsPath: String,
    /** Pre-filled base URL shown as a placeholder when the user picks this protocol. */
    val defaultBaseUrl: String,
) {
    /**
     * Classic chat/completions wire format. Covers OpenAI (via /v1),
     * Ollama, LM Studio, vLLM, Together, Gemini (via its /openai/
     * compatibility endpoint) and basically every OpenAI-compatible proxy.
     */
    OPENAI_COMPATIBLE(
        displayName = "OpenAI-compatible (chat/completions)",
        modelsPath = "/models",
        defaultBaseUrl = "https://api.openai.com/v1",
    ),

    /**
     * OpenAI's newer `/v1/responses` endpoint (required by reasoning models
     * like gpt-5.x/6.x when using function tools with reasoning_effort, which
     * /chat/completions rejects).
     */
    OPENAI_RESPONSES(
        displayName = "OpenAI Responses (/responses)",
        modelsPath = "/models",
        defaultBaseUrl = "https://api.openai.com/v1",
    ),

    /**
     * Gemini preset — resolves to [OPENAI_COMPATIBLE] with the Gemini
     * OpenAI-compatibility base URL; exists purely to prefill sensible
     * defaults for users, no separate connector class.
     */
    GEMINI_OPENAI_COMPATIBLE(
        displayName = "Gemini (OpenAI-compat endpoint)",
        modelsPath = "/models",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
    ),

    /**
     * OpenRouter preset — uses the same [OPENAI_COMPATIBLE] wire format but
     * adds OpenRouter-specific HTTP headers (HTTP-Referer, X-Title) and the
     * `provider` routing object to the request body. The preset pre-fills the
     * base URL and exposes OpenRouter-specific form fields in the settings UI.
     */
    OPENROUTER(
        displayName = "OpenRouter",
        modelsPath = "/models",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
    );

    /** Protocols backed by the same connector; presets resolve to their real protocol. */
    val connector: ApiProtocol get() = when (this) {
        GEMINI_OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE
        OPENROUTER -> OPENAI_COMPATIBLE
        else -> this
    }

    companion object {
        fun fromName(name: String?): ApiProtocol =
            entries.firstOrNull { it.name == name } ?: OPENAI_COMPATIBLE

        /** Resolves a combo-box display name back to the enum (null-safe). */
        fun fromDisplayName(display: String?): ApiProtocol =
            entries.firstOrNull { it.displayName == display } ?: OPENAI_COMPATIBLE
    }
}
