package dev.tellforgeai.llm

/** Exact model-name matching for pricing evidence; avoids mixing prices across model revisions. */
internal object ModelPricingEvidence {
    /** Router IDs are often namespaced (for example openai/gpt-6-luna); compare the model part. */
    fun canonicalModelName(model: String): String = model.trim().substringAfterLast('/').trim()

    fun matchesModel(model: String, vararg evidence: String): Boolean {
        val canonicalName = canonicalModelName(model)
        val modelTokens = tokens(canonicalName)
        if (modelTokens.isEmpty()) return false

        // Providers and pricing sites commonly use either an ID (gpt-6-luna) or a
        // display name (GPT 6 Luna). Treat punctuation/spacing as separators, but
        // require the complete token sequence so gpt-5.6-luna cannot match gpt-6-luna.
        val modelPattern = modelTokens.joinToString("[\\s._:/-]+") { Regex.escape(it) }
        // Block suffixes that usually indicate another revision/variant such as
        // -preview, .2025-01-01, or _instruct; ordinary prose after whitespace is fine.
        val pattern = Regex("(?i)(?<![a-z0-9])$modelPattern(?![a-z0-9._-])")
        return evidence.any { pattern.containsMatchIn(it) }
    }

    /** Exact catalog-ID comparison allowing an optional provider namespace on either side. */
    fun sameModelId(configured: String, catalogId: String?): Boolean {
        if (catalogId.isNullOrBlank()) return false
        val configuredId = configured.trim().trimEnd('/')
        val returnedId = catalogId.trim().trimEnd('/')
        return configuredId.equals(returnedId, ignoreCase = true) ||
            canonicalModelName(configuredId).equals(canonicalModelName(returnedId), ignoreCase = true)
    }

    private fun tokens(value: String): List<String> =
        value.lowercase().split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
}
