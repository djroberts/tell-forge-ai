package dev.tellforgeai.tools

import dev.tellforgeai.settings.ApiKeyStore
import dev.tellforgeai.settings.TellForgeAISettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Web search. One tool, two providers:
 *
 *  - **duckduckgo** (default primary): keyless scraping of the DDG HTML endpoint.
 *    Zero setup, but unofficial — rate-limited and can break if the markup
 *    changes. Good enough for occasional "what is this error?" searches.
 *  - **brave**: the official Brave Search API, requires the user's own API
 *    key (Settings -> Tools -> TellForgeAI Providers, key stored in PasswordSafe).
 *
 * Two-layer fallback (when enabled in settings):
 *  - code: if the primary provider hard-fails (missing key, HTTP error, empty
 *    results), the other provider is retried automatically, once.
 *  - LLM: the model may re-call this tool with `provider` set to the *other*
 *    backend when the results came back fine but don't answer the query.
 *
 * No user approval is required: this is an outbound GET/POST that only reads
 * search results, in Foretell mode too. Web content is not deleted, modified
 * or executed, so it is no more sensitive than `search_files`.
 */
object WebSearchTool {

    const val MAX_RESULTS = 5
    const val MAX_OUTPUT_CHARS = 6_000

    private const val BRAVE_KEY_ID = ApiKeyStore.BRAVE_KEY_ID

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val BRAVE_KEY_HEADER = "X-Subscription-Token"

    /** A browser-like UA; DDG (and most sites) block missing/odd defaults. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0 Safari/537.36"

    /** Outcome of a single provider attempt; drives the fallback decision. */
    private sealed class Outcome {
        abstract val text: String
        class OK(override val text: String) : Outcome()
        /** Hard failure: error or zero results. [reason] is safe to show. */
        class Fail(override val text: String) : Outcome()
    }

    /** Whether a provider can be used at all right now (no network involved). */
    private fun available(provider: String): Boolean =
        provider != "brave" || !ApiKeyStore.get(BRAVE_KEY_ID).isNullOrBlank()

    fun search(args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim()?.take(400)
            ?: throw IllegalArgumentException("query required")
        if (query.isEmpty()) return "Error: query is empty"

        val settings = TellForgeAISettings.getInstance()
        val primary = normalizeProvider(settings.webSearchProvider()) ?: "duckduckgo"

        // LLM fallback layer: an explicit provider arg from the model.
        val requested = VfsTools.str(args, "provider")?.let(::normalizeProvider)
        val (start, overrideNotice) = resolveRequest(
            primary, requested,
            settings.webSearchFallbackEnabled(),
            available("brave"),
        )

        // Code fallback layer: primary attempt; on hard failure try the other
        // provider once (when fallback is enabled and it is usable).
        val first = attempt(start, query)
        if (first is Outcome.OK) {
            return prependNotice(overrideNotice, first.text)
        }
        val other = otherOf(start)
        if (settings.webSearchFallbackEnabled() && available(other)) {
            val second = attempt(other, query)
            val suffix = " (Fallback: $start ${first.text.lines().firstOrNull { it.startsWith("Error") || it.startsWith("No web results") } ?: "failed"})"
            if (second is Outcome.OK) {
                return prependNotice(overrideNotice, second.text + suffix)
            }
            // Both attempts failed: return the primary failure, mention fallback.
            return first.text.replaceFirst(
                "\n",
                "\nFallback to $other also failed.\n",
            ).let { prependNotice(overrideNotice, it) }
        }
        // No fallback possible: keep the original failure text; the model can
        // still attempt the other provider itself if fallback is enabled.
        return prependNotice(overrideNotice, first.text)
    }

    /** Normalizes a provider string; anything unknown maps to null (not usable). */
    internal fun normalizeProvider(value: String?): String? = when (value?.lowercase()) {
        "brave" -> "brave"
        "duckduckgo" -> "duckduckgo"
        else -> null
    }

    internal fun otherOf(provider: String): String =
        if (provider == "brave") "duckduckgo" else "brave"

    /**
     * Pure routing decision: which provider to start with and any note the
     * model should see. An explicit LLM provider request that differs from the
     * primary is honored (LLM-initiated fallback); a request for an unavailable
     * provider is denied with an explanatory note. Visible for tests.
     */
    internal fun resolveRequest(
        primary: String,
        requested: String?,
        fallbackEnabled: Boolean,
        braveAvailable: Boolean,
    ): Pair<String, String?> {
        if (requested == null || requested == primary) return primary to null
        val usable = requested != "brave" || braveAvailable
        return if (usable) requested to "Note: the model explicitly requested provider '$requested'."
        else primary to ("Note: model requested provider '$requested' but it is unavailable " +
            "(no Brave API key configured) — used '$primary' instead.")
    }

    private fun prependNotice(notice: String?, text: String): String =
        if (notice == null) text else notice + "\n" + text

    /** Runs one provider and wraps the result in an outcome. */
    private fun attempt(provider: String, query: String): Outcome =
        if (provider == "brave") searchBrave(query) else searchDuckDuckGo(query)

    // ---------------------------------------------------------------- DDG

    private fun searchDuckDuckGo(query: String): Outcome {
        val url = "https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode(query, Charsets.UTF_8)
        return try {
            val resp = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html")
                    .get()
                    .build()
            ).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    return Outcome.Fail(
                        "Error: DuckDuckGo returned HTTP ${r.code}. Web search is unavailable right now."
                    )
                }
                val html = r.body?.string().orEmpty()
                if (html.isBlank()) {
                    return Outcome.Fail("Error: empty response from DuckDuckGo.")
                }
                val results = parseDdg(html)
                if (results.isEmpty()) {
                    return Outcome.Fail(
                        "No web results for '$query'. (Possible rate-limit or markup change.)"
                    )
                }
                Outcome.OK(format("duckduckgo", query, results))
            }
        } catch (e: Exception) {
            Outcome.Fail("Error: web search failed (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    /**
     * Minimal regex parsing of the DDG HTML result page: result links are
     * `<a class="result__a" href="...">Title</a>` and snippets
     * `<a class="result__snippet"...>snippet</a>`. Link hrefs are DDG redirects
     * containing the real URL in the `uddg=` query parameter.
     */
    private fun parseDdg(html: String): List<Pair<String, String>> {
        val linkRe = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("""<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        val out = mutableListOf<Pair<String, String>>()
        for ((i, m) in links.withIndex()) {
            val realUrl = extractDdgUrl(m.groupValues[1]) ?: continue
            val title = stripTags(m.groupValues[2])
            if (title.isBlank()) continue
            out.add(title to realUrl + (snippets.getOrNull(i)?.let { " — $it" } ?: ""))
            if (out.size >= MAX_RESULTS) break
        }
        return out.take(MAX_RESULTS)
    }

    /** DDG wraps results in `//duckduckgo.com/l/?uddg=<encoded>&...` — unwrap. */
    private fun extractDdgUrl(href: String): String? {
        val href_ = href.takeIf { it.isNotBlank() } ?: return null
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href_)?.groupValues?.get(1)
            ?: return if (href_.startsWith("http")) href_ else null
        return runCatching {
            java.net.URLDecoder.decode(uddg, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun stripTags(htmlFragment: String): String = htmlFragment
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&nbsp;", " ").trim()

    private fun format(provider: String, query: String, results: List<Pair<String, String>>): String =
        buildString {
            append("Provider: ").append(provider).append("\n")
            append("Web results for '").append(query).append("':\n")
            for ((title, urlWithSnippet) in results.take(MAX_RESULTS)) {
                append("\n- ").append(title).append(" ").append(urlWithSnippet)
            }
        }.let { if (it.length > MAX_OUTPUT_CHARS) it.take(MAX_OUTPUT_CHARS) + "..." else it }

    // -------------------------------------------------------------- Brave

    private fun searchBrave(query: String): Outcome {
        val key = ApiKeyStore.get(BRAVE_KEY_ID)
        if (key.isNullOrBlank()) {
            return Outcome.Fail(
                "Error: no Brave API key is configured (Settings -> Tools -> TellForgeAI Providers)."
            )
        }
        val url = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(query, Charsets.UTF_8) + "&count=$MAX_RESULTS"
        return try {
            val resp = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header(BRAVE_KEY_HEADER, key)
                    .get()
                    .build()
            ).execute()
            resp.use { r ->
                if (!r.isSuccessful) {
                    return Outcome.Fail(
                        "Error: Brave Search returned HTTP ${r.code}" +
                            (if (r.code == 401 || r.code == 403) " (invalid API key)" else "")
                    )
                }
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) return Outcome.Fail("Error: empty response from Brave.")
                val results = parseBrave(body)
                if (results.isEmpty()) {
                    return Outcome.Fail("No web results for '$query'.")
                }
                Outcome.OK(format("brave", query, results))
            }
        } catch (e: Exception) {
            Outcome.Fail("Error: web search failed (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    private fun parseBrave(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val web = Json.parseToJsonElement(body)
                .jsonObject["web"]?.jsonObject ?: return emptyList()
            val arr = web["results"]?.jsonArray ?: return emptyList()
            for (el in arr) {
                val o = el.jsonObject
                val title = o["title"]?.jsonPrimitive?.content ?: continue
                val url = o["url"]?.jsonPrimitive?.content ?: continue
                val desc = o["description"]?.jsonPrimitive?.content?.let { " — $it" } ?: ""
                out.add(title to url + desc)
                if (out.size >= MAX_RESULTS) break
            }
        } catch (_: Exception) {
            // Malformed JSON: fall through with whatever we collected.
        }
        return out
    }
}
