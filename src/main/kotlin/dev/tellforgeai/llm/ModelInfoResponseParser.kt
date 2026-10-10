package dev.tellforgeai.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Strict parsing for the structured JSON requested from the model-info interpreter. */
internal object ModelInfoResponseParser {
    private val fencedJson = Regex("^\\s*```(?:json)?\\s*(\\{[\\s\\S]*})\\s*```\\s*$", RegexOption.IGNORE_CASE)
    private val expectedFields = setOf(
        "context_window",
        "input_usd_per_million",
        "cached_input_usd_per_million",
        "output_usd_per_million",
        "confidence",
    )

    fun parse(text: String): JsonObject? {
        val trimmed = text.trim()
        val direct = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        val objectValue = direct ?: run {
            val candidate = fencedJson.matchEntire(trimmed)?.groupValues?.get(1) ?: return null
            runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: return null
        }
        return objectValue.takeIf { obj -> obj.keys.any(expectedFields::contains) }
    }
}

/** Parses context-window quantities found in provider documentation/search excerpts. */
internal object ContextWindowNumberParser {
    fun parse(value: String): Int? {
        val normalized = value.replace(",", "").replace(Regex("\\s+"), "").trim()
        val quantity = when {
            normalized.endsWith("k", ignoreCase = true) ->
                normalized.dropLast(1).toDoubleOrNull()?.times(1_000.0)
            normalized.endsWith("m", ignoreCase = true) ->
                normalized.dropLast(1).toDoubleOrNull()?.times(1_000_000.0)
            normalized.matches(Regex("[0-9]+")) -> normalized.toDoubleOrNull()
            else -> null
        } ?: return null
        if (!quantity.isFinite() || quantity > Int.MAX_VALUE || quantity < Int.MIN_VALUE) return null
        return ContextLimitParser.valid(quantity.toInt())
    }
}
