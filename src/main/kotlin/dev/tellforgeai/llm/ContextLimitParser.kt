package dev.tellforgeai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared context-window extraction for OpenAI-compatible `/models` payloads
 * (and single model entries). Used by both [LlmClient.fetchContextWindow]
 * (first-hand provider data) and [ModelInfoLookup] (the provider-hint search
 * evidence), so both paths agree on which field keys and model matches count.
 */
internal object ContextLimitParser {

    /** Smallest window accepted as real data; keeps spinner values out. */
    const val MIN_WINDOW = 2_000

    /** Upper sanity bound (a current context beyond 10M tokens is a parse error, not a spec). */
    const val MAX_WINDOW = 10_000_000

    /** Smallest raw value trusted from a `/models` field (1024 means "unset/experimental"). */
    private const val MIN_RAW_FIELD = 1_024

    /** Nonstandard context-length field keys recognized across OpenAI-compatible backends. */
    private val MODEL_FIELD_KEYS = listOf("context_length", "max_model_len", "context_window", "max_input_tokens")

    fun valid(window: Int?): Int? = window?.takeIf { it in MIN_WINDOW..MAX_WINDOW }

    /**
     * Extracts the context window for [model] from a full `/models` response body.
     * Model-entry matching tiers: exact `id`/`name` (case-insensitive), then
     * namespace-tolerant comparison via [ModelPricingEvidence.sameModelId]
     * (handles `models/gemini-2.5-pro` vs `gemini-2.5-pro`), then substring
     * match (some providers use suffixed deployment names).
     */
    fun fromModelsJson(modelsJson: String, model: String): Int? = try {
        if (model.isBlank()) null
        else {
            val root = Json.parseToJsonElement(modelsJson).jsonObject
            val entries = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return null
            val objects = entries.mapNotNull { pkg -> pkg as? JsonObject }
            val id = { entry: JsonObject ->
                entry["id"]?.jsonPrimitive?.contentOrNull
                    ?: entry["name"]?.jsonPrimitive?.contentOrNull
            }
            val entry = objects.firstOrNull { id(it).equals(model, ignoreCase = true) }
                ?: objects.firstOrNull { ModelPricingEvidence.sameModelId(model, id(it)) }
                ?: objects.firstOrNull { id(it)?.contains(model, ignoreCase = true) == true }
                ?: return null
            valid(fromFields(entry))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Extracts the context window from the text of a single model entry (the
     * JSON object string [ModelInfoLookup.extractModelsEntry] hands to the
     * interpreter). Returns null when the text is not a parseable object or
     * carries no recognized field.
     */
    fun fromModelEntryText(text: String?): Int? = runCatching {
        if (text.isNullOrBlank()) return null
        val element = Json.parseToJsonElement(text)
        valid(fromFields(element.jsonObject))
    }.getOrNull()

    private fun fromFields(entry: JsonObject): Int? =
        MODEL_FIELD_KEYS.firstNotNullOfOrNull { key ->
            entry[key]?.jsonPrimitive?.intOrNull?.takeIf { it > MIN_RAW_FIELD }
        }
}
