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
 *  - **duckduckgo** (default): keyless scraping of the DDG HTML endpoint.
 *    Zero setup, but unofficial — rate-limited and can break if the markup
 *    changes. Good enough for occasional "what is this error?" searches.
 *  - **brave**: the official Brave Search API, requires the user's own API
 *    key (Settings -> Tools -> TellForgeAI Providers, key stored in PasswordSafe).
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

    fun search(args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim()?.take(400)
            ?: throw IllegalArgumentException("query required")
        if (query.isEmpty()) return "Error: query is empty"

        return when (TellForgeAISettings.getInstance().webSearchProvider().lowercase()) {
            "brave" -> searchBrave(query)
            else -> searchDuckDuckGo(query)
        }
    }

    // ---------------------------------------------------------------- DDG

    private fun searchDuckDuckGo(query: String): String {
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
                    return "Error: DuckDuckGo returned HTTP ${r.code}. " +
                        "Web search is unavailable right now — answer from your own " +
                        "knowledge and say so, or suggest the user configure the Brave " +
                        "provider (Settings -> Tools -> TellForgeAI Providers)."
                }
                val html = r.body?.string().orEmpty()
                if (html.isBlank()) {
                    return "Error: empty response from DuckDuckGo — answer from your own " +
                        "knowledge and say so."
                }
                val results = parseDdg(html)
                if (results.isEmpty()) {
                    return "No web results for '$query'. " +
                        "(Possible rate-limit or markup change — answer from your own " +
                        "knowledge and say so rather than retrying repeatedly.)"
                }
                format(query, results)
            }
        } catch (e: Exception) {
            "Error: web search failed (${e.message ?: e.javaClass.simpleName}) — " +
                "answer from your own knowledge and say so."
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

    private fun format(query: String, results: List<Pair<String, String>>): String =
        buildString {
            append("Web results for '").append(query).append("':\n")
            for ((title, urlWithSnippet) in results.take(MAX_RESULTS)) {
                append("\n- ").append(title).append(" ").append(urlWithSnippet)
            }
        }.let { if (it.length > MAX_OUTPUT_CHARS) it.take(MAX_OUTPUT_CHARS) + "..." else it }

    // -------------------------------------------------------------- Brave

    private fun searchBrave(query: String): String {
        val key = ApiKeyStore.get(BRAVE_KEY_ID)
        if (key.isNullOrBlank()) {
            return "Error: web_search provider is set to Brave but no Brave API key is " +
                "configured. Ask the user to add one in Settings -> Tools -> TellForgeAI " +
                "Providers (Web search section), or answer from your own knowledge."
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
                    return "Error: Brave Search returned HTTP ${r.code}" +
                        (if (r.code == 401 || r.code == 403) " (invalid API key)" else "") +
                        ". Answer from your own knowledge and say so."
                }
                val body = r.body?.string().orEmpty()
                if (body.isBlank()) return "Error: empty response from Brave — answer from your own knowledge."
                val results = parseBrave(body)
                if (results.isEmpty()) {
                    return "No web results for '$query'. Answer from your own knowledge and say so."
                }
                format(query, results)
            }
        } catch (e: Exception) {
            "Error: web search failed (${e.message ?: e.javaClass.simpleName}) — " +
                "answer from your own knowledge and say so."
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
