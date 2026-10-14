package dev.tellforgeai.model

import com.intellij.util.xmlb.annotations.Transient
import kotlinx.serialization.json.*

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

    // ── OpenRouter-specific fields (only used when apiProtocol == OPENROUTER) ──

    /** Sent as HTTP-Referer header for OpenRouter app rankings. */
    var openRouterSiteUrl: String? = null,
    /** Sent as X-Title header for OpenRouter app rankings. */
    var openRouterAppTitle: String? = null,
    /** Comma-separated provider slugs to try in order → provider.order[]. */
    var openRouterProviderOrder: String? = null,
    /** Whether to allow fallback providers when the ordered list is exhausted. */
    var openRouterAllowFallbacks: Boolean? = null,
    /** Provider slugs to skip → provider.ignore[]. */
    var openRouterProviderIgnore: String? = null,
    /** Allowlist of provider slugs → provider.only[]. */
    var openRouterProviderOnly: String? = null,
    /** Sort providers by "price", "throughput", or "latency". */
    var openRouterProviderSort: String? = null,
    /** Comma-separated quantization levels to filter by, e.g. "fp8,bf16". */
    var openRouterQuantizations: String? = null,
    /** Data-collection policy: "allow" or "deny". */
    var openRouterDataCollection: String? = null,
    /** ZDR (Zero Data Retention) flag. */
    var openRouterZdr: Boolean? = null,
    /** Require providers to support all request parameters. */
    var openRouterRequireParameters: Boolean? = null,
    /** Max price per million prompt tokens ($). */
    var openRouterMaxPricePrompt: Double? = null,
    /** Max price per million completion tokens ($). */
    var openRouterMaxPriceCompletion: Double? = null,
    /** Preferred min throughput (tokens/sec) — e.g. {"p50":100,"p90":50}. */
    var openRouterPreferredMinThroughput: String? = null,
    /** Preferred max latency (seconds) — e.g. {"p50":1,"p90":3,"p99":5}. */
    var openRouterPreferredMaxLatency: String? = null,
    /**
     * Raw JSON array for OpenRouter plugins, e.g.
     * [{"id":"web"},{"id":"response_healing"}].
     */
    var openRouterPluginsJson: String? = null,
    /**
     * Catch-all raw JSON object merged into the request body for any
     * OpenRouter-specific field without a dedicated profile property.
     */
    var openRouterExtraBodyJson: String? = null,
) {
    fun modelFor(planMode: Boolean): String {
        val pm = planModel
        return if (planMode && !pm.isNullOrBlank()) pm else model
    }

    /** Base URL with any trailing slash removed — safe to append a path. */
    fun trimmedBaseUrl(): String = baseUrl.trim().removeSuffix("/")

    /** True when this profile uses the OpenRouter protocol. */
    val isOpenRouter: Boolean get() = apiProtocol == ApiProtocol.OPENROUTER
}

/** JSON instance used for parsing extra body / plugins / throughput / latency fields. */
private val PROFILE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Adds OpenRouter-specific fields to the request-body [JsonObjectBuilder].
 * No-op when [profile.isOpenRouter] is false.
 */
fun JsonObjectBuilder.injectOpenRouterBody(profile: ProviderProfile) {
    if (!profile.isOpenRouter) return
    val providerObj = buildJsonObject {
        profile.openRouterProviderOrder?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("order", JsonArray(it.split(",").map { s -> JsonPrimitive(s.trim()) })) }
        profile.openRouterAllowFallbacks?.let { put("allow_fallbacks", it) }
        profile.openRouterProviderIgnore?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("ignore", JsonArray(it.split(",").map { s -> JsonPrimitive(s.trim()) })) }
        profile.openRouterProviderOnly?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("only", JsonArray(it.split(",").map { s -> JsonPrimitive(s.trim()) })) }
        profile.openRouterProviderSort?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("sort", it) }
        profile.openRouterQuantizations?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("quantizations", JsonArray(it.split(",").map { s -> JsonPrimitive(s.trim()) })) }
        profile.openRouterDataCollection?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { put("data_collection", it) }
        profile.openRouterZdr?.let { put("zdr", it) }
        profile.openRouterRequireParameters?.let { put("require_parameters", it) }
        val maxPrompt = profile.openRouterMaxPricePrompt
        val maxCompletion = profile.openRouterMaxPriceCompletion
        if (maxPrompt != null || maxCompletion != null) {
            put("max_price", buildJsonObject {
                maxPrompt?.let { put("prompt", it) }
                maxCompletion?.let { put("completion", it) }
            })
        }
        profile.openRouterPreferredMinThroughput?.trim()?.takeIf { it.isNotEmpty() }?.let { jsonStr ->
            try { put("preferred_min_throughput", PROFILE_JSON.parseToJsonElement(jsonStr)) } catch (_: Exception) { }
        }
        profile.openRouterPreferredMaxLatency?.trim()?.takeIf { it.isNotEmpty() }?.let { jsonStr ->
            try { put("preferred_max_latency", PROFILE_JSON.parseToJsonElement(jsonStr)) } catch (_: Exception) { }
        }
    }
    if (providerObj.isNotEmpty()) put("provider", providerObj)

    // Plugins (raw JSON array)
    profile.openRouterPluginsJson?.trim()?.takeIf { it.isNotEmpty() }?.let { pluginsStr ->
        try { put("plugins", PROFILE_JSON.parseToJsonElement(pluginsStr)) } catch (_: Exception) { }
    }

    // Catch-all extra body JSON
    profile.openRouterExtraBodyJson?.trim()?.takeIf { it.isNotEmpty() }?.let { extra ->
        try {
            val extraObj = PROFILE_JSON.parseToJsonElement(extra).jsonObject
            extraObj.forEach { (k, v) -> put(k, v) }
        } catch (_: Exception) { }
    }
}
