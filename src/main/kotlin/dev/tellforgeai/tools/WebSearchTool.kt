package dev.tellforgeai.tools

import dev.tellforgeai.settings.TellForgeAISettings
import kotlinx.serialization.json.JsonObject

/**
 * Web search routing. Providers implement [SearchProvider] and are registered
 * in [SearchProviders.all] — this object owns only the routing logic:
 *
 *  - the user-configured primary provider (Settings -> Tools -> TellForgeAI
 *    Web Search), with the first registered provider as the default.
 *  - two fallback layers:
 *      code: on a hard failure of the primary (missing key, HTTP error, no
 *      results), the user-chosen fallback provider is tried once — an explicit
 *      selection, "auto" (first other registered-and-available provider), or
 *      "none" (disabled).
 *      LLM: the model may re-call this tool with `provider` set to another
 *      backend when the results came back fine but don't answer the query.
 *  - a known-provider request that is unavailable (e.g. Brave without a key,
 *    SearXNG without an instance URL) is denied with an explanatory note and
 *    substituted by the primary.
 *
 * No user approval is required: this is an outbound GET/POST that only reads
 * search results, in Foretell mode too. Web content is not deleted, modified
 * or executed, so it is no more sensitive than `search_files`.
 */
object WebSearchTool {

    const val MAX_RESULTS = 5
    const val MAX_OUTPUT_CHARS = 6_000

    /** Outcome of a single attempt, as a plain (outcome, ok) pair for routing. */
    private fun run(provider: SearchProvider, query: String): Pair<SearchOutcome, Boolean> {
        val outcome = provider.search(query)
        return outcome to (outcome is SearchOutcome.OK)
    }

    fun search(args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim()?.take(400)
            ?: throw IllegalArgumentException("query required")
        if (query.isEmpty()) return "Error: query is empty"

        val settings = TellForgeAISettings.getInstance()
        val primaryId = SearchProviders.normalize(settings.webSearchProvider())
            ?: SearchProviders.all.first().id

        // LLM fallback layer: an explicit provider arg from the model.
        val requestedId = VfsTools.str(args, "provider")?.let { SearchProviders.normalize(it) }
        val (startId, overrideNotice) = resolveRequest(primaryId, requestedId) {
            SearchProviders.byId(it)?.available() == true
        }
        val start = SearchProviders.byId(startId)
            ?: return "Error: unknown search provider '$startId'"

        // Code fallback layer: on hard failure, per settings — the explicit
        // fallback provider (if other than the start and available), or "auto":
        // the first other registered-and-available provider.
        val (first, firstOk) = run(start, query)
        if (firstOk) {
            return prependNotice(overrideNotice, first.text)
        }
        val fallbackTarget: SearchProvider? = when (val sel = settings.fallbackProvider()) {
            TellForgeAISettings.FALLBACK_NONE -> null
            TellForgeAISettings.FALLBACK_AUTO ->
                SearchProviders.all.firstOrNull { it.id != startId && it.available() }
            else -> SearchProviders.byId(sel)
                ?.takeIf { it.id != startId && it.available() }
        }
        if (fallbackTarget != null) {
            val (second, secondOk) = run(fallbackTarget, query)
            val suffix = " (Fallback: $startId ${
                first.text.lines().firstOrNull {
                    it.startsWith("Error") || it.startsWith("No web results")
                } ?: "failed"
            })"
            if (secondOk) {
                return prependNotice(overrideNotice, second.text + suffix)
            }
            // Both attempts failed: return the primary failure, mention fallback.
            return first.text.replaceFirst(
                "\n",
                "\nFallback to ${fallbackTarget.id} also failed.\n",
            ).let { prependNotice(overrideNotice, it) }
        }
        // No fallback configured/possible: keep the original failure text; the
        // model can still attempt another provider via an explicit `provider` arg.
        return prependNotice(overrideNotice, first.text)
    }

    /**
     * Pure routing decision: which provider to start with and any note the
     * model should see. An explicit LLM provider request that differs from the
     * primary is honored (LLM-initiated fallback); a request for an unknown or
     * unavailable provider is denied with an explanatory note. [available]
     * supplies per-id availability so tests stay pure. Visible for tests.
     */
    internal fun resolveRequest(
        primary: String,
        requested: String?,
        available: (String) -> Boolean,
    ): Pair<String, String?> {
        if (requested == null || requested == primary) return primary to null
        if (SearchProviders.byId(requested) == null) {
            return primary to ("Note: unknown provider '$requested' ignored — available: " +
                SearchProviders.all.joinToString(", ") { it.id } + ".")
        }
        return if (available(requested)) {
            requested to "Note: the model explicitly requested provider '$requested'."
        } else {
            primary to ("Note: model requested provider '$requested' but it is unavailable — " +
                "used '$primary' instead.")
        }
    }

    private fun prependNotice(notice: String?, text: String): String =
        if (notice == null) text else notice + "\n" + text
}
