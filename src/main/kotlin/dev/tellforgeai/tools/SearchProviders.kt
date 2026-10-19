package dev.tellforgeai.tools

import dev.tellforgeai.settings.ApiKeyStore
import dev.tellforgeai.settings.TellForgeAISettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Outcome of a single search-provider attempt; drives the fallback decision
 * in [WebSearchTool]. Top-level so every [SearchProvider] implementation
 * returns it directly.
 */
sealed class SearchOutcome {
    abstract val text: String

    class OK(override val text: String) : SearchOutcome()

    /** Hard failure: error message or zero results. [text] is safe to show. */
    class Fail(override val text: String) : SearchOutcome()
}

/**
 * One web-search backend. Implementations own their availability check (e.g.
 * an API key present in PasswordSafe) and their query path; [WebSearchTool]
 * supplies only routing (primary, LLM override, code fallback) and output
 * formatting, so adding a provider is: implement this interface + register it
 * in [SearchProviders.all] + (if keyed) a dedicated PasswordSafe id.
 */
interface SearchProvider {
    /** Stable machine id ("duckduckgo", "brave", …) used in settings + tool args. */
    val id: String

    /** Human-readable name for settings UI (combo items, labels). */
    val displayName: String

    /** PasswordSafe key id for this provider's API key, or null if keyless. */
    val keyId: String?

    /** Label shown above the key field in the Web Search settings tab. */
    val keyLabel: String?

    /** Whether a search can be attempted right now (no network involved). */
    fun available(): Boolean

    fun search(query: String): SearchOutcome
}

/**
 * Registry of web-search backends. Order matters: it is the fallback order
 * used by [WebSearchTool] (first entry is the natural default primary).
 */
object SearchProviders {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** All registered providers, in fallback order. */
    val all: List<SearchProvider> = listOf(DuckDuckGoProvider, BraveProvider, SearxngProvider)

    fun byId(id: String?): SearchProvider? = all.firstOrNull { it.id == id }

    /** Normalized id or null when unknown/blank — settings + tool arg validation. */
    fun normalize(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { v -> all.any { it.id == v } }

    // ------------------------------------------------------------ DuckDuckGo

    /**
     * Keyless scraping of the DDG HTML endpoint. Zero setup, but unofficial —
     * rate-limited and can break if the markup changes.
     */
    object DuckDuckGoProvider : SearchProvider {
        override val id = "duckduckgo"
        override val displayName = "DuckDuckGo (keyless)"
        override val keyId: String? = null
        override val keyLabel: String? = null
        override fun available(): Boolean = true

        /** A browser-like UA; DDG (and most sites) block missing/odd defaults. */
        private val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0 Safari/537.36"

        override fun search(query: String): SearchOutcome {
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
                        return SearchOutcome.Fail(
                            "Error: DuckDuckGo returned HTTP ${r.code}. Web search is unavailable right now."
                        )
                    }
                    val html = r.body?.string().orEmpty()
                    if (html.isBlank()) {
                        return SearchOutcome.Fail("Error: empty response from DuckDuckGo.")
                    }
                    val results = parseDdg(html)
                    if (results.isEmpty()) {
                        return SearchOutcome.Fail(
                            "No web results for '$query'. (Possible rate-limit or markup change.)"
                        )
                    }
                    SearchOutcome.OK(format(query, results))
                }
            } catch (e: Exception) {
                SearchOutcome.Fail("Error: web search failed (${e.message ?: e.javaClass.simpleName}).")
            }
        }

        /**
         * Minimal regex parsing of the DDG HTML result page: result links are
         * `<a class="result__a" href="...">Title</a>` and snippets
         * `<a class="result__snippet"...>snippet</a>`. Link hrefs are DDG
         * redirects containing the real URL in the `uddg=` query parameter.
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
                if (out.size >= WebSearchTool.MAX_RESULTS) break
            }
            return out.take(WebSearchTool.MAX_RESULTS)
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
    }

    // ------------------------------------------------------------ Brave

    /** The official Brave Search API; requires the user's own API key. */
    object BraveProvider : SearchProvider {
        override val id = "brave"
        override val displayName = "Brave Search (requires API key)"
        override val keyId: String? = ApiKeyStore.BRAVE_KEY_ID
        override val keyLabel: String? = "Brave Search API key (stored in PasswordSafe)"
        override fun available(): Boolean = !ApiKeyStore.get(keyId!!).isNullOrBlank()

        private const val BRAVE_KEY_HEADER = "X-Subscription-Token"

        override fun search(query: String): SearchOutcome {
            val key = ApiKeyStore.get(keyId!!)
            if (key.isNullOrBlank()) {
                return SearchOutcome.Fail(
                    "Error: no Brave API key is configured (Settings -> Tools -> TellForgeAI Web Search)."
                )
            }
            val url = "https://api.search.brave.com/res/v1/web/search?q=" +
                URLEncoder.encode(query, Charsets.UTF_8) + "&count=${WebSearchTool.MAX_RESULTS}"
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
                        return SearchOutcome.Fail(
                            "Error: Brave Search returned HTTP ${r.code}" +
                                (if (r.code == 401 || r.code == 403) " (invalid API key)" else "")
                        )
                    }
                    val body = r.body?.string().orEmpty()
                    if (body.isBlank()) return SearchOutcome.Fail("Error: empty response from Brave.")
                    val results = parse(body)
                    if (results.isEmpty()) {
                        return SearchOutcome.Fail("No web results for '$query'.")
                    }
                    SearchOutcome.OK(format(query, results))
                }
            } catch (e: Exception) {
                SearchOutcome.Fail("Error: web search failed (${e.message ?: e.javaClass.simpleName}).")
            }
        }

        private fun parse(body: String): List<Pair<String, String>> {
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
                    if (out.size >= WebSearchTool.MAX_RESULTS) break
                }
            } catch (_: Exception) {
                // Malformed JSON: fall through with whatever we collected.
            }
            return out
        }
    }

    /** Shared "title + url — snippet" output block used by every provider. */
    internal fun format(query: String, results: List<Pair<String, String>>): String =
        buildString {
            append("Web results for '").append(query).append("':\n")
            for ((title, urlWithSnippet) in results.take(WebSearchTool.MAX_RESULTS)) {
                append("\n- ").append(title).append(" ").append(urlWithSnippet)
            }
        }

    // ------------------------------------------------------------ SearXNG

    /**
     * Searches a user-configured SearXNG instance (metasearch engine) via its
     * JSON API. Needs no API key — only an instance base URL; the instance
     * must have `format=json` enabled (`search.formats` in its settings.yml),
     * otherwise it replies HTTP 403. Availability = non-blank base URL.
     */
    object SearxngProvider : SearchProvider {
        override val id = "searxng"
        override val displayName = "SearXNG instance (no API key, needs instance URL)"
        override val keyId: String? = null
        override val keyLabel: String? = null

        override fun available(): Boolean = url().isNotEmpty()

        /** Normalized `…/search` endpoint URL from the user's base URL setting. */
        internal fun url(): String {
            val base = TellForgeAISettings.getInstance().searxngBaseUrl()
            if (base.isBlank()) return ""
            // `localhost` resolves to IPv6 [::1] first on many systems; instances
            // commonly listen on IPv4 only (dialing ::1 failed with
            // "Failed to connect to localhost/[0:0:0:0:0:0:0:1]:3000"). Rewrite it
            // to the IPv4 loopback so a plain "http://localhost:8888" works.
            return base.trimEnd('/').replace("://localhost", "://127.0.0.1") + "/search"
        }

        override fun search(query: String): SearchOutcome {
            val endpoint = url()
            if (endpoint.isEmpty()) {
                return SearchOutcome.Fail(
                    "Error: no SearXNG instance URL is configured " +
                        "(Settings -> Tools -> TellForgeAI Web Search)."
                )
            }
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?: return SearchOutcome.Fail("Error: invalid SearXNG URL '$endpoint'.")
            val url = httpUrl.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .build()
            return try {
                val resp = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                ).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val hint = if (r.code == 403) {
                            " (the instance must allow the JSON format: `format=json` in search.formats)"
                        } else ""
                        return SearchOutcome.Fail(
                            "Error: SearXNG returned HTTP ${r.code}$hint."
                        )
                    }
                    val body = r.body?.string().orEmpty()
                    if (body.isBlank()) return SearchOutcome.Fail("Error: empty response from SearXNG.")
                    val results = parse(body)
                    if (results.isEmpty()) {
                        return SearchOutcome.Fail("No web results for '$query'.")
                    }
                    SearchOutcome.OK(format(query, results))
                }
            } catch (e: Exception) {
                SearchOutcome.Fail("Error: web search failed (${e.message ?: e.javaClass.simpleName}).")
            }
        }

        /** SearXNG JSON: `{"results":[{"title":..,"url":..,"content":..}, …]}`. */
        private fun parse(body: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            try {
                val arr = Json.parseToJsonElement(body)
                    .jsonObject["results"]?.jsonArray ?: return emptyList()
                for (el in arr) {
                    val o = el.jsonObject
                    val title = o["title"]?.jsonPrimitive?.content ?: continue
                    val url = o["url"]?.jsonPrimitive?.content ?: continue
                    val desc = o["content"]?.jsonPrimitive?.content?.let { " — $it" } ?: ""
                    out.add(title to url + desc)
                    if (out.size >= WebSearchTool.MAX_RESULTS) break
                }
            } catch (_: Exception) {
                // Malformed JSON (e.g. HTML from an instance without format=json):
                // fall through with whatever we collected.
            }
            return out
        }
    }
}
