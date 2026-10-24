package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.WebFetchState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * URL -> markdown fetch via a self-hosted webfetch service (tiered fetch:
 * static HTTP first, optional Playwright browser render for SPAs;
 * Anthropic web_fetch semantics, Jina-style REST shape).
 *
 * Configured per project in the Configure Integrations dialog
 * (see [WebFetchState]); the optional bearer token lives in PasswordSafe.
 *
 * Exposes one READ-ONLY tool:
 *  - `web_fetch` — POST /fetch with the agent's options; returns the
 *    markdown/html content, title, truncation info and which render tier
 *    served it. Available in both modes, no approval (the service itself
 *    enforces http/https-only and domain allow/block lists).
 */
object WebFetchTool {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Browser-rendered pages can legitimately take a while.
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private const val MAX_OUTPUT_CHARS = 24_000

    // ------------------------------------------------------------ tool entry

    /** Fetch a URL through the webfetch service and return its content. */
    fun fetch(project: Project, args: JsonObject): String {
        val state = WebFetchState.getInstance(project)
        if (!state.enabled()) return "Error: web_fetch is disabled for this project."
        if (!state.configured(project)) {
            return "Error: web_fetch is not configured for this project — set the service URL " +
                "in the Configure Integrations dialog."
        }
        val url = VfsTools.str(args, "url")?.trim().orEmpty()
        if (url.isEmpty()) return "Error: web_fetch requires a 'url' argument."

        // POST /fetch carries the full option set as JSON.
        val bodyJson = buildString {
            append("{")
            append("\"url\":").append(jsonString(url))
            VfsTools.str(args, "format")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(",\"format\":").append(jsonString(it))
            }
            VfsTools.str(args, "render")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(",\"render\":").append(jsonString(it))
            }
            VfsTools.str(args, "max_length")?.trim()?.toIntOrNull()?.let {
                append(",\"max_length\":$it")
            }
            VfsTools.str(args, "start_index")?.trim()?.toIntOrNull()?.let {
                append(",\"start_index\":$it")
            }
            VfsTools.str(args, "bypass_cache")?.trim()?.let {
                when (it.lowercase()) {
                    "true", "1", "yes" -> append(",\"bypass_cache\":true")
                    "false", "0", "no" -> append(",\"bypass_cache\":false")
                }
            }
            append("}")
        }

        val base = state.baseUrl()
        val req = Request.Builder()
            .url("$base/fetch")
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .apply {
                // Optional bearer token; a local instance usually has none.
                state.token(project)?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return "Error: web_fetch failed — HTTP ${resp.code}: ${raw.take(300)}"
                }
                val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
                    return "Error: web_fetch returned a non-JSON response: ${raw.take(300)}"
                }
                formatResult(obj)
            }
        } catch (e: Exception) {
            "Error: web_fetch failed — ${describe(e)} (service: $base)"
        }
    }

    // -------------------------------------------------------------- formatting

    /** Render the FetchResult payload as a compact, agent-friendly block. */
    private fun formatResult(obj: JsonObject): String {
        val content = obj["content"]?.jsonPrimitive?.content.orEmpty()
        if (content.isBlank()) return "The page returned no extractable content."
        return buildString {
            obj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                append("Title: ").append(it).append("\n\n")
            }
            val tier = obj["tier"]?.jsonPrimitive?.content
            val truncated = obj["truncated"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
            val remaining = obj["remaining"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            append(content.take(MAX_OUTPUT_CHARS))
            if (truncated || remaining > 0) {
                append("\n\n[truncated — ")
                append(if (remaining > 0) "$remaining characters remain; " else "")
                append("re-call web_fetch with start_index=")
                append((content.length).coerceAtLeast(0))
                append(" to continue]")
            }
            if (tier != null) append("\n[render tier: ").append(tier).append("]")
        }
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    // ---------------------------------------------------------- connection test

    /** Used by the Configure Integrations dialog: GET /health. */
    fun testConnection(baseUrl: String, token: String): String {
        val url = baseUrl.trim().removeSuffix("/").ifEmpty { "http://localhost:4937" }
        if (url.toHttpUrlOrNull() == null) return "✗ Not a valid URL: '$url'."
        val req = Request.Builder().url("$url/health")
            .header("Accept", "application/json")
            .apply {
                token.trim().takeIf { it.isNotEmpty() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    when (resp.code) {
                        401, 403 -> "✗ Rejected (HTTP ${resp.code}). Check the bearer token " +
                            "(leave it empty if the service needs none)."
                        else -> "✗ HTTP ${resp.code}: ${raw.take(200)}"
                    }
                } else {
                    val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    val tier2 = obj?.get("tier2Ready")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    "✓ Connected to webfetch at $url" +
                        (if (tier2 == false) " (static-only: no browser render / Tier 2)" else "")
                }
            }
        } catch (e: Exception) {
            "✗ ${describe(e)} — is the webfetch service running at $url?"
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "unknown host '${e.message}'"
        is java.net.ConnectException -> "could not connect — is the webfetch service running?"
        is java.net.SocketTimeoutException -> "connection timed out"
        else -> e.message ?: e.javaClass.simpleName
    }
}
