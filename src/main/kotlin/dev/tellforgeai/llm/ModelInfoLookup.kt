package dev.tellforgeai.llm

import com.intellij.openapi.diagnostic.Logger
import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.settings.ApiKeyStore
import dev.tellforgeai.settings.TellForgeAISettings
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Best-effort model lookup through web search, optionally interpreted by the selected LLM. */
object ModelInfoLookup {
    private val LOG = Logger.getInstance(ModelInfoLookup::class.java)

    data class Pricing(
        val inputPerMillion: Double?,
        val outputPerMillion: Double?,
        val cachedInputPerMillion: Double? = null,
        val sourceTitle: String,
        val sourceUrl: String,
    )

    data class Info(
        val contextWindow: Int?,
        val pricing: Pricing?,
        val lookupInputTokens: Int = 0,
        val lookupOutputTokens: Int = 0,
        val status: String = "",
    )

    private data class Result(val title: String, val url: String, val text: String)
    private data class Interpreted(val contextWindow: Int?, val pricing: Pricing?, val inputTokens: Int, val outputTokens: Int)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private const val UA = "Mozilla/5.0 (compatible; TellForgeAI model info lookup)"

    /** Network work runs on a daemon thread; callback also runs there. */
    fun lookup(profile: ProviderProfile, model: String, useAi: Boolean, onResult: (Info) -> Unit) {
        Thread({
            val trace = mutableListOf<String>()
            // Buffer silently; only log on failure (dump below).
            fun step(msg: String) {
                trace.add(msg)
            }
            step("lookup started (useAi=$useAi)")
            val domain = searchDomain(profile.baseUrl)
            step("search domain: $domain")
            // Two complementary queries: site-scoped (hits the provider's own pricing/docs
            // pages) and a generic one (aggregators, benchmarks). Merge by URL.
            val results = LinkedHashMap<String, Result>()
            val problems = mutableListOf<String>()
            val queries = listOf(
                "site:$domain $model pricing per million tokens",
                "$domain $model API pricing input output per million tokens",
            )
            for ((index, query) in queries.withIndex()) {
                val (found, error) = search(query, ::step)
                step("query ${index + 1}/${queries.size} \"$query\" → ${found.size} results${error?.let { " (error: $it)" } ?: ""}")
                found.forEach { results.putIfAbsent(it.url, it) }
                if (found.isEmpty()) error?.let { problems.add(it) }
                if (results.size >= 6) break
            }
            step("total unique search results: ${results.size} (${results.keys.joinToString()})")
            // Some OpenAI-compatible routers expose rates on /models — try that first-hand.
            // It is first-hand provider data, so put it first in the evidence queue.
            val hint = providerModelsHint(profile, model, ::step)
            hint?.let { results.putIfAbsent(it.url, it) }
            // First-hand provider /models data beats search snippets for context; the
            // hint text is the extracted single-model JSON entry when available.
            val contextFromProvider = ContextLimitParser.fromModelEntryText(hint?.text)
                ?: hint?.text?.let { ContextLimitParser.fromModelsJson(it, model) }
            step("context from provider /models hint: ${contextFromProvider ?: "none"}")
            // Snippets alone rarely contain explicit rates; fetch the top pages and feed
            // their text to the interpreter / regex pass as well.
            val urls = (listOfNotNull(hint) + results.values.filterNot { hint != null && it.url == hint.url }).distinctBy { it.url }
            // API endpoints (e.g. /models) already carry their data in the snippet and
            // typically require auth — refetching without the header fails (HTTP 401).
            val (apiResults, webResults) = urls.partition { it.url.startsWith(profile.trimmedBaseUrl()) }
            val pages = apiResults + webResults.take(3).mapIndexedNotNull { index, result ->
                step("fetching page ${index + 1}/3: ${result.url}")
                fetchPageText(result.url, ::step)?.let { text -> result.copy(text = "${result.text}\n${text}".trim()) }
            }
            webResults.drop(3).forEach { step("page not fetched (limit of 3): ${it.url}") }
            val enriched = if (pages.isEmpty()) urls else pages
            step("enriched evidence pages: ${enriched.size}")
            val interpreted = if (useAi && enriched.isNotEmpty()) {
                step("AI interpretation enabled, calling model")
                interpret(profile, model, enriched, ::step)
            } else {
                step("AI interpretation skipped (useAi=$useAi, evidence=${enriched.size})")
                null
            }
            // Priority: first-hand provider /models → AI interpretation → snippet regex.
            val context = contextFromProvider ?: interpreted?.contextWindow ?: parseContext(enriched, model, ::step)
            // Never fall back to broad search snippets when AI returned evidence it
            // could not verify. Both paths must establish the exact configured model.
            val pricing = interpreted?.pricing ?: run {
                if (useAi && interpreted != null) {
                    step("regex pricing skipped: AI interpretation returned but did not verify pricing")
                    null
                } else {
                    parsePricing(enriched, model, ::step)
                }
            }
            val status = when {
                pricing != null -> "Price found"
                problems.size == queries.size && problems.isNotEmpty() -> problems.first()
                enriched.isEmpty() -> "Search unavailable or no results"
                else -> "Pricing could not be verified"
            }
            step("RESULT: $status; model=$model; pricing=${pricing?.let { "input=${it.inputPerMillion}, cached=${it.cachedInputPerMillion}, output=${it.outputPerMillion}, source=${it.sourceUrl}" } ?: "none"}")
            // Log the trace on success too: wrong positive matches must be diagnosable.
            trace.forEach { LOG.info("[ModelInfo] ${profile.name}/$model: $it") }
            if (pricing == null) {
                LOG.warn("[ModelInfo] ${profile.name}/$model FAILED: $status")
            }
            onResult(Info(context, pricing, interpreted?.inputTokens ?: 0, interpreted?.outputTokens ?: 0, status))
        }, "TellForgeAI-model-info").apply { isDaemon = true; start() }
    }

    /**
     * Primary provider per settings; on hard failure (error / empty / missing
     * key) the configured fallback provider is retried — same policy as
     * WebSearchTool minus the LLM layer (this path is not agent-driven).
     */
    private fun search(query: String, step: (String) -> Unit): Pair<List<Result>, String?> {
        val settings = TellForgeAISettings.getInstance()
        val primaryId = dev.tellforgeai.tools.SearchProviders.normalize(settings.webSearchProvider())
            ?: dev.tellforgeai.tools.SearchProviders.all.first().id
        val fallbackId = when (val sel = settings.fallbackProvider()) {
            TellForgeAISettings.FALLBACK_NONE -> null
            TellForgeAISettings.FALLBACK_AUTO ->
                dev.tellforgeai.tools.SearchProviders.all
                    .firstOrNull { it.id != primaryId && it.available() }?.id
            else -> sel.takeIf { it != primaryId }
        }
        val primary = runProviderSearch(primaryId, query, step)
        val (resA, errA) = primary ?: (null to null)
        val useFallback = fallbackId != null && (resA.isNullOrEmpty() || errA != null)
        val (resB, errB) = if (useFallback) runProviderSearch(fallbackId!!, query, step) ?: (null to null)
        else (emptyList<Result>() to null)
        return ((resA.orEmpty() + resB) as List<Result>) to (errA ?: errB)
    }

    /** Runs one engine by registry id via [WebSearchTool]-style shared providers. */
    private fun runProviderSearch(
        providerId: String,
        query: String,
        step: (String) -> Unit,
    ): Pair<List<Result>?, String?>? = when (providerId) {
        "brave" -> searchBrave(query, step)
        "searxng" -> searchSearxng(query, step)
        else -> searchDdg(query, step)
    }

    private fun searchSearxng(query: String, step: (String) -> Unit): Pair<List<Result>?, String?>? {
        val endpoint = dev.tellforgeai.tools.SearchProviders.SearxngProvider.url()
        if (endpoint.isEmpty()) {
            step("SearXNG skipped: no instance URL configured")
            return null
        }
        return try {
            val httpUrl = endpoint.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("format", "json")
                ?.build()
                ?: run {
                    step("SearXNG invalid URL: $endpoint")
                    return null to "SearXNG search failed (invalid URL)"
                }
            val request = Request.Builder().url(httpUrl).header("Accept", "application/json").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    step("SearXNG HTTP ${response.code} for query \"$query\"")
                    return@use null to "SearXNG search failed (HTTP ${response.code})"
                }
                val body = response.body?.string().orEmpty()
                val array = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
                if (array == null) {
                    step("SearXNG returned no results in body (${body.length} chars) for query \"$query\"")
                    return@use null to "SearXNG returned no results"
                }
                val parsed = array.mapNotNull { element ->
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val urlValue = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Result(title, urlValue, obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
                step("SearXNG parsed ${parsed.size} results (${parsed.joinToString { it.url }})")
                parsed to parsed.takeIf(List<Result>::isEmpty)?.let { "SearXNG returned no results" }
            }
        } catch (e: Exception) {
            step("SearXNG exception: ${e.message ?: e.javaClass.simpleName}")
            null to "SearXNG search failed (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /** Extracts the bare host for site-scoped queries: "https://hostyourai.com/api/v1" → "hostyourai.com". */
    private fun searchDomain(baseUrl: String): String = try {
        val withScheme = if (!baseUrl.contains("://")) "https://$baseUrl" else baseUrl
        java.net.URI(withScheme.trim()).host?.removePrefix("www.") ?: baseUrl.trim().removeSuffix("/").substringAfterLast("/")
    } catch (_: Exception) { baseUrl.trim().removeSuffix("/") }

    private fun searchDdg(query: String, step: (String) -> Unit): Pair<List<Result>?, String?>? = try {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, Charsets.UTF_8)
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                step("DuckDuckGo HTTP ${response.code} for query \"$query\"")
                return@use null to "DuckDuckGo search failed (HTTP ${response.code})"
            }
            val html = response.body?.string().orEmpty()
            step("DuckDuckGo response ${html.length} chars for query \"$query\"")
            val blocks = Regex("""(?s)<div class="result[^>]*>.*?</div>\s*</div>""").findAll(html).map { it.value }.toList()
            val linkRe = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRe = Regex("""class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL)
            val links = linkRe.findAll(html).take(8).toList()
            val parsed = links.mapIndexedNotNull { index, match ->
                val href = match.groupValues[1]
                val target = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
                    ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
                    ?: href.takeIf { it.startsWith("http") }
                val block = blocks.getOrNull(index) ?: html.substring(match.range.first, (match.range.last + 1400).coerceAtMost(html.length))
                val snippet = snippetRe.find(block)?.groupValues?.get(1).orEmpty()
                target?.let { Result(clean(match.groupValues[2]), it, clean(snippet)) }
            }
            step("DuckDuckGo parsed ${parsed.size} results (${parsed.joinToString { it.url }})")
            parsed to parsed.takeIf(List<Result>::isEmpty)?.let { "DuckDuckGo returned no results" }
        }
    } catch (e: Exception) {
        step("DuckDuckGo exception: ${e.message ?: e.javaClass.simpleName}")
        null to "DuckDuckGo search failed (${e.message ?: e.javaClass.simpleName})"
    }

    private fun searchBrave(query: String, step: (String) -> Unit): Pair<List<Result>?, String?>? {
        val key = ApiKeyStore.get(ApiKeyStore.BRAVE_KEY_ID).orEmpty()
        if (key.isBlank()) {
            step("Brave skipped: no API key configured")
            return null
        }
        return try {
            val url = "https://api.search.brave.com/res/v1/web/search?q=" + URLEncoder.encode(query, Charsets.UTF_8) + "&count=8"
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("X-Subscription-Token", key).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    step("Brave HTTP ${response.code} for query \"$query\"")
                    return@use null to "Brave search failed (HTTP ${response.code})"
                }
                val body = response.body?.string().orEmpty()
                val array = Json.parseToJsonElement(body).jsonObject["web"]?.jsonObject?.get("results")?.jsonArray
                if (array == null) {
                    step("Brave returned no web results in body (${body.length} chars) for query \"$query\"")
                    return@use null to "Brave returned no results"
                }
                val parsed = array.mapNotNull { element ->
                    val obj = element.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val urlValue = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Result(title, urlValue, obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
                step("Brave parsed ${parsed.size} results (${parsed.joinToString { it.url }})")
                parsed to parsed.takeIf(List<Result>::isEmpty)?.let { "Brave returned no results" }
            }
        } catch (e: Exception) {
            step("Brave exception: ${e.message ?: e.javaClass.simpleName}")
            null to "Brave search failed (${e.message ?: e.javaClass.simpleName})"
        }
    }

    /** Accepts a complete JSON object or a single fenced JSON object, never arbitrary prose. */
    private fun extractJsonObject(text: String, step: (String) -> Unit): JsonObject? =
        ModelInfoResponseParser.parse(text).also { parsed ->
            step(if (parsed == null) "interpret: response is not a valid structured JSON object" else "interpret: valid structured JSON object")
        }

    /** Fetches a currency → USD rate from frankfurter.app (ECB data, no API key). Returns null on failure. */
    private fun fxRateToUsd(currency: String, step: (String) -> Unit): Double? = try {
        if (currency == "USD") 1.0 else {
            val url = "https://api.frankfurter.app/latest?from=$currency&to=USD"
            val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    step("FX lookup HTTP ${response.code} for $currency")
                    return@use null
                }
                val rate = Json.parseToJsonElement(response.body?.string().orEmpty())
                    .jsonObject["rates"]?.jsonObject?.get("USD")?.jsonPrimitive?.doubleOrNull
                if (rate == null) step("FX lookup: no USD rate in response for $currency")
                rate
            }
        }
    } catch (e: Exception) {
        step("FX lookup exception for $currency: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /** Pulls the JSON object for one model out of a large /models catalog response. */
    private fun extractModelsEntry(body: String, model: String, step: (String) -> Unit): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val models = root["data"]?.jsonArray ?: root["models"]?.jsonArray ?: return@runCatching null
        val match = models.firstOrNull { entry ->
            val id = entry.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ModelPricingEvidence.sameModelId(model, id)
        }?.jsonObject
        if (match == null) {
            step("/models catalog: no entry for model \"$model\" (${models.size} entries)")
            return@runCatching null
        }
        step("/models catalog: extracted pricing entry for \"$model\" (${match.toString().length} chars)")
        match.toString()
    }.getOrNull()

    /** Some OpenAI-compatible routers expose per-model rates on /models. Try the provider itself first. */
    private fun providerModelsHint(profile: ProviderProfile, model: String, step: (String) -> Unit): Result? = try {
        val url = profile.trimmedBaseUrl() + "/models"
        val key = ApiKeyStore.get(profile.id).orEmpty()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key").header("User-Agent", UA).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                step("provider /models HTTP ${response.code} at $url")
                return@use null
            }
            val body = response.body?.string().orEmpty()
            val hasPricing = body.contains("pricing", ignoreCase = true)
            step("provider /models reachable (${body.length} chars), pricing=${if (hasPricing) "present" else "absent"}")
            // Extract the model entry even when it contains no pricing: context-window
            // fields are useful independently and should not be gated on a pricing key.
            val relevant = extractModelsEntry(body, model) { step(it) }
            if (relevant == null && !hasPricing) return@use null
            Result("${profile.name} model catalog", url, relevant ?: body.take(4000))
        }
    } catch (e: Exception) {
        step("provider /models exception: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /** GETs a page and returns cleaned readable text (title, meta description, headings, paragraphs). */
    private fun fetchPageText(url: String, step: (String) -> Unit): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                step("page fetch HTTP ${response.code}: $url")
                return@use null
            }
            val html = response.body?.string().orEmpty()
            if (html.length > 2_000_000) {
                step("page too large (${html.length} chars), skipped: $url")
                return@use null
            }
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::clean)
            val description = Regex("(?is)<meta[^>]+name=[\"']description[\"'][^>]+content=[\"'](.*?)[\"']").find(html)
                ?.groupValues?.get(1)?.let(::clean)
            val body = Regex("(?is)<(h[1-6]|p|li)[^>]*>(.*?)</\\1>").findAll(html)
                .map { clean(it.groupValues[2]) }
                .filter { it.length in 3..600 }
                .joinToString(" ")
                .take(6000)
            step("page fetched: ${html.length} chars HTML, title=${title?.take(80)}, text=${body.length} chars: $url")
            listOfNotNull(title, description, body).joinToString("\n").takeIf { it.isNotBlank() }
                ?: run { step("page yielded no extractable text (possibly JS-rendered): $url"); null }
        }
    } catch (e: Exception) {
        step("page fetch exception for $url: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /** Ask the configured model to synthesize search evidence into strict JSON. */
    private fun interpret(profile: ProviderProfile, model: String, results: List<Result>, step: (String) -> Unit): Interpreted? {        return try {
        val key = ApiKeyStore.get(profile.id).orEmpty()
        if (key.isBlank()) { step("interpret skipped: no API key for provider"); return null }
        val evidence = results.take(8).joinToString("\n") { "TITLE: ${it.title}\nURL: ${it.url}\nSNIPPET: ${it.text.take(2000)}" }
        step("interpret evidence: ${results.size} pages, ${evidence.length} chars sent")
        val prompt = """You are extracting API pricing facts for a specific provider and model from the supplied evidence (search snippets and page excerpts). Rules:
- Use ONLY the evidence below. Do not use your own knowledge or infer from similar model names.
- Rates must be USD per million tokens. If the evidence states a per-token or per-1K rate, convert it (per-1K rate × 1000, per-token rate × 1,000,000).
- If the evidence states prices in a non-USD currency, report the original numbers and set "currency" to the ISO code (e.g. "EUR"); do NOT convert the rates yourself.
- Cached-input rates are often listed separately ("cache hit", "cached input") — report them when present.
- The exact configured model name to verify is the part after the final slash in the model ID. The configured ID may be namespaced (for example, openai/gpt-6-luna); treat that as gpt-6-luna, not as a different model.
- Report a price only when the same evidence source explicitly identifies that exact model name, including its version, and supports the rate. Do not conflate similarly named models or revisions (for example, gpt-5.6-luna is NOT gpt-6-luna). Partial data is fine: report input-only or output-only if that is all the evidence supports.
- If the evidence does not explicitly identify the exact model name after the slash, return null prices and low confidence rather than guessing.
- source_url must be one of the supplied evidence URLs.
- Set confidence "high" only when the evidence unambiguously states the rate for this provider/model. For unclear or mismatched evidence use null prices and "low".
Return ONLY minified JSON, no prose before or after: {"context_window": integer|null, "context_source_url": string|null, "input_usd_per_million": number|null, "cached_input_usd_per_million": number|null, "output_usd_per_million": number|null, "currency": string, "source_title": string|null, "source_url": string|null, "confidence": "high"|"low"}. For context_window, use only an explicit value in the supplied evidence for the exact configured model; context_source_url must be the URL of that evidence item, otherwise set both fields to null.

Provider name: ${profile.name}
Provider base URL: ${profile.trimmedBaseUrl()}
Configured model ID: $model
Exact model name that pricing evidence must identify: ${ModelPricingEvidence.canonicalModelName(model)}

Search evidence:
$evidence"""
        val raw = if (profile.apiProtocol.connector == ApiProtocol.OPENAI_RESPONSES) {
            callResponses(profile, model, key, prompt, step)
        } else {
            callChatCompletions(profile, model, key, prompt, step)
        } ?: run { step("interpret: model call returned nothing"); return null }
        val text = raw.first
        val usage = raw.second
        step("interpret model response (${text.length} chars, ${usage.first}+${usage.second} tokens): ${text.take(500)}")
        val obj = extractJsonObject(text) { step(it) } ?: run { step("interpret: no JSON object in model response"); return null }
        val confidence = obj["confidence"]?.jsonPrimitive?.contentOrNull
        val currency = obj["currency"]?.jsonPrimitive?.contentOrNull?.uppercase()?.takeIf { it.length == 3 } ?: "USD"
        var input = obj["input_usd_per_million"]?.jsonPrimitive?.doubleOrNull
        var output = obj["output_usd_per_million"]?.jsonPrimitive?.doubleOrNull
        var cached = obj["cached_input_usd_per_million"]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 0.0..100_000.0 }
        if (currency != "USD" && (input != null || output != null)) {
            val rate = fxRateToUsd(currency) { step(it) }
            if (rate != null) {
                step("converting $currency → USD at rate $rate")
                fun round(v: Double?) = v?.let { Math.round(it * rate * 1000.0) / 1000.0 }
                input = round(input)
                output = round(output)
                cached = round(cached)
            } else {
                step("no FX rate for $currency; keeping original numbers and marking source")
            }
        }
        val sourceUrl = obj["source_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceEvidence = results.firstOrNull { it.url == sourceUrl }
        val validSource = sourceEvidence != null
        val matchingModelEvidence = sourceEvidence?.let { result ->
            ModelPricingEvidence.matchesModel(model, result.title, result.url, result.text)
        } == true
        step("interpret parsed: confidence=$confidence, input=$input, output=$output, cached=$cached, currency=$currency, sourceUrl=$sourceUrl (matchesEvidence=$validSource, exactModelMention=$matchingModelEvidence)")
        val pricing = if (confidence == "high" && matchingModelEvidence && (input != null || output != null) &&
            (input ?: 0.0) in 0.0..100_000.0 && (output ?: 0.0) in 0.0..100_000.0 && validSource
        ) {
            Pricing(
                inputPerMillion = input,
                outputPerMillion = output,
                cachedInputPerMillion = cached,
                sourceTitle = (obj["source_title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Pricing source" })
                    .let { if (currency != "USD") "$it ($currency, converted to USD)" else it },
                sourceUrl = sourceUrl,
            )
        } else {
            val reasons = mutableListOf<String>()
            if (confidence != "high") reasons.add("confidence=$confidence")
            if (!matchingModelEvidence) reasons.add("evidence does not mention exact model ID")
            if (input == null && output == null) reasons.add("no prices in response")
            if ((input ?: 0.0) !in 0.0..100_000.0 || (output ?: 0.0) !in 0.0..100_000.0) reasons.add("price out of range")
            if (!validSource) reasons.add("source_url not among evidence URLs")
            step("interpret rejected pricing: ${reasons.joinToString("; ")}")
            null
        }
        val contextSourceUrl = obj["context_source_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contextSource = results.firstOrNull { it.url == contextSourceUrl }
        val contextHasExactModel = contextSource?.let { result ->
            ModelPricingEvidence.matchesModel(model, result.title, result.url, result.text)
        } == true
        val window = if (contextHasExactModel) {
            ContextLimitParser.valid(obj["context_window"]?.jsonPrimitive?.intOrNull)
        } else {
            null
        }
        step("interpret context parsed: window=${window ?: "none"}, sourceUrl=$contextSourceUrl, exactModelMention=$contextHasExactModel")
        Interpreted(window, pricing, usage.first, usage.second)
        } catch (e: Exception) {
            step("interpret exception: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun callChatCompletions(profile: ProviderProfile, model: String, key: String, prompt: String, step: (String) -> Unit): Pair<String, Pair<Int, Int>>? {
        val payload = { jsonMode: Boolean ->
            buildJsonObject {
                put("model", model)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
                put("temperature", 0)
                put("max_tokens", 1500)
                if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            }.toString()
        }
        val url = profile.trimmedBaseUrl() + "/chat/completions"
        return postChatCompletions(url, key, payload(true), step) ?: run {
            step("interpret chat/completions: retrying without response_format")
            postChatCompletions(url, key, payload(false), step)
        }
    }

    private fun postChatCompletions(url: String, key: String, body: String, step: (String) -> Unit): Pair<String, Pair<Int, Int>>? {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                step("interpret chat/completions HTTP ${response.code} at $url")
                return null
            }
            val rawBody = response.body?.string().orEmpty()
            val obj = runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrElse {
                step("interpret chat/completions: unparseable body (${rawBody.length} chars): ${rawBody.take(300)}")
                return null
            }
            val message = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            val text = message?.get("content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: run {
                    step("interpret chat/completions: no content in message: ${message.toString().take(500)}")
                    return null
                }
            val usage = obj["usage"]?.jsonObject
            text to ((usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0) to
                (usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0))
        }
    }

    private fun callResponses(profile: ProviderProfile, model: String, key: String, prompt: String, step: (String) -> Unit): Pair<String, Pair<Int, Int>>? {
        val body = buildJsonObject {
            put("model", model)
            put("input", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", prompt) }) })
            put("max_output_tokens", 700)
        }.toString()
        val url = profile.trimmedBaseUrl() + "/responses"
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                step("interpret responses HTTP ${response.code} at $url")
                return null
            }
            val obj = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            val text = obj["output"]?.jsonArray?.flatMap { item ->
                (item as? JsonObject)?.get("content")?.jsonArray.orEmpty().mapNotNull { part ->
                    (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }
            }?.joinToString("\n") ?: obj["output_text"]?.jsonPrimitive?.contentOrNull ?: run {
                step("interpret responses: no text in response")
                return null
            }
            val usage = obj["usage"]?.jsonObject
            text to ((usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0) to
                (usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0))
        }
    }

    private fun parseContext(results: List<Result>, model: String, step: (String) -> Unit): Int? {
        fun num(value: String): Int? = ContextWindowNumberParser.parse(value)
        val patterns = listOf(
            Regex("(?i)(?:context window|context length|context size|maximum context)[^0-9kKmM]{0,50}([0-9][0-9,.]*\\s*[kKmM]?)(?:\\s*[-–]?\\s*token)?"),
            Regex("(?i)([0-9][0-9,.]*\\s*[kKmM]?)\\s*(?:token|tokens)[^.;]{0,50}(?:context window|context length|context size)"),
        )
        val found = results.asSequence().mapNotNull { result ->
            val text = "${result.title}. ${result.text}"
            if (!ModelPricingEvidence.matchesModel(model, result.title, result.url, result.text)) null
            else patterns.firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.get(1)?.let(::num)
            }
        }.firstOrNull()
        step("regex context parse: ${found ?: "no match"}")
        return found
    }

    private fun parsePricing(results: List<Result>, model: String, step: (String) -> Unit): Pricing? {
        for (result in results) {
            if (!ModelPricingEvidence.matchesModel(model, result.title, result.url, result.text)) {
                step("regex pricing skipped: result does not mention exact model ID (${result.url})")
                continue
            }
            val text = "${result.title}. ${result.text}".replace(",", "")
            val input = rate(text, "input")
            val output = rate(text, "output")
            if (input == null && output == null) continue
            if ((input ?: 0.0) in 0.0..100_000.0 && (output ?: 0.0) in 0.0..100_000.0) {
                step("regex pricing parse hit for exact model $model: input=$input, output=$output from ${result.url}")
                return Pricing(input, output, sourceTitle = result.title, sourceUrl = result.url)
            }
        }
        step("regex pricing parse: no exact-model match in ${results.size} pages")
        return null
    }

    private fun rate(text: String, direction: String): Double? {
        val patterns = listOf(
            Regex("(?i)\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/|per)\\s*(?:1\\s*m|million)\\s*${direction}\\s*tokens?"),
            Regex("(?i)${direction}\\s*tokens?[^$]{0,60}\\$\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(?:/|per)\\s*(?:1\\s*m|million)"),
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toDoubleOrNull() }
    }

    private fun clean(html: String): String = html.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
}
