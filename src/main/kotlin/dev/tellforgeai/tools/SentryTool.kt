package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.SentryState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sentry query integration via the public REST API
 * (`{baseUrl}/api/0/...`), authenticated with a Sentry auth token
 * ("sntrys_…") as a bearer header. The token is stored in PasswordSafe per
 * project (see [SentryState]); org/project/baseUrl roam with the project but
 * nothing secret does.
 *
 * This is the INVESTIGATE side of the story: the user's application pushes
 * logs/errors to Sentry itself (standard Sentry SDK + DSN). These tools let
 * the agent query that data and explain what went wrong. All tools are
 * READ-ONLY, available in both modes, no approval.
 *
 * Exposes four TellForgeAI tools:
 *  - `sentry_list_projects` — GET /api/0/organizations/{org}/projects/
 *                             discover org + project slugs (so the agent can
 *                             find where to look)
 *  - `sentry_search_issues` — GET /api/0/projects/{org}/{project}/issues/
 *                             the workhorse: Sentry search syntax, filters,
 *                             statsPeriod, sort → grouped issues (patterns)
 *  - `sentry_issue_events`  — GET /api/0/issues/{id}/events/ (paged) → the
 *                             raw log/error events + stacktraces for one issue
 *  - `sentry_event_detail`  — GET /api/0/projects/{org}/{project}/events/{id}/
 *                             one full event (stacktrace, breadcrumbs, context)
 *
 * Search syntax is made tolerant: a messy free-text query from the agent is
 * translated into Sentry's `query=` grammar rather than rejected.
 */
object SentryTool {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MAX_OUTPUT_CHARS = 12_000

    // ------------------------------------------------------------ tool entry

    /** List the org's projects: slugs + ids, so the agent learns where to look. */
    fun listProjects(project: Project, args: JsonObject): String {
        val org = requireOrg(project)
        return get(project, "/api/0/organizations/${enc(org)}/projects/").fold(
            onSuccess = { body ->
                val projects = body.safeArray()
                if (projects.isEmpty()) return@fold "No Sentry projects found for org '$org'."
                buildString {
                    append("Sentry projects in org '$org':\n")
                    for (p in projects) {
                        val slug = p["slug"]?.jsonPrimitive?.content.orEmpty()
                        val name = p["name"]?.jsonPrimitive?.content.orEmpty()
                        val id = p["id"]?.jsonPrimitive?.content.orEmpty()
                        append("\n- ").append(name.ifEmpty { slug }).append("  (slug: ").append(slug)
                        append(", id: ").append(id).append(")")
                        val status = p["status"]?.jsonPrimitive?.content
                        if (status != null && status != "active") append("  [").append(status).append("]")
                    }
                    append("\n\nUse sentry_search_issues with a project slug to investigate errors.")
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: sentry_list_projects failed — ${describe(project, e)}" },
        )
    }

    /**
     * Search grouped issues (patterns) across a project (or the whole org when
     * no project slug is configured). Accepts Sentry search syntax in [query]:
     * free text plus filters like `is:unresolved`, `level:error`,
     * `browser.name:Chrome`, `release:`, `environment:`. Returns the issues
     * with their counts and first/last seen so the agent sees patterns, not
     * noise.
     */
    fun searchIssues(project: Project, args: JsonObject): String {
        val org = requireOrg(project)
        val projectSlug = effectiveProject(project)
        val query = VfsTools.str(args, "query")?.trim() ?: ""
        val statsPeriod = VfsTools.str(args, "statsPeriod")?.trim()?.takeIf { it.isNotEmpty() } ?: "14d"
        val sort = VfsTools.str(args, "sort")?.trim()?.takeIf { it.isNotEmpty() } ?: "freq"
        val limit = VfsTools.str(args, "limit")?.trim()?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val translated = translateQuery(query)
        val params = buildList {
            if (translated.isNotEmpty()) add("query=" + enc(translated))
            add("statsPeriod=" + enc(statsPeriod))
            add("sort=" + enc(sort))
            add("per_page=$limit")
        }.joinToString("&")

        // Whole-org search uses the org-level endpoint; project-scoped uses the
        // project endpoint. Sentry supports org-level /issues/?project=<slug>.
        val path = if (projectSlug.isNotEmpty()) {
            "/api/0/projects/${enc(org)}/${enc(projectSlug)}/issues/?$params"
        } else {
            "/api/0/organizations/${enc(org)}/issues/?$params"
        }
        return get(project, path).fold(
            onSuccess = { body ->
                val issues = body.safeArray()
                if (issues.isEmpty()) {
                    return@fold "No Sentry issues match" +
                        (query.takeIf { it.isNotEmpty() }?.let { " '$query'" } ?: "") +
                        " in " + scopeLabel(org, projectSlug) + " (last $statsPeriod)."
                }
                buildString {
                    append("Sentry issues matching")
                    if (query.isNotEmpty()) append(" '").append(query).append("'")
                    append(" in ").append(scopeLabel(org, projectSlug))
                    append(" (last ").append(statsPeriod).append("):\n")
                    issues.forEachIndexed { i, it ->
                        val id = it["id"]?.jsonPrimitive?.content.orEmpty()
                        val title = it["title"]?.jsonPrimitive?.content.orEmpty()
                        val level = it["level"]?.jsonPrimitive?.content
                        val count = it["count"]?.jsonPrimitive?.content
                        val status = it["status"]?.jsonPrimitive?.content
                        val lastSeen = it["lastSeen"]?.jsonPrimitive?.content
                        append("\n").append(i + 1).append(". [").append(id).append("] ")
                            .append(title.ifEmpty { "(untitled)" })
                        level?.let { append("  level=").append(it) }
                        count?.let { append("  count=").append(it) }
                        status?.let { append("  status=").append(it) }
                        lastSeen?.let { append("  last=").append(it.substringBefore('T')) }
                        // include the project slug when searching across the org
                        if (projectSlug.isEmpty()) {
                            it["project"]?.jsonObject?.get("slug")?.jsonPrimitive?.content
                                ?.let { s -> append("  project=").append(s) }
                        }
                    }
                    append("\n\nUse sentry_issue_events with an issue id for the raw events/stacktraces.")
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: sentry_search_issues failed — ${describe(project, e)}" },
        )
    }

    /** Fetch the raw log/error events for one issue (grouped by fingerprint). */
    fun issueEvents(project: Project, args: JsonObject): String {
        val issueId = VfsTools.str(args, "issue")?.trim()
            ?: throw IllegalArgumentException("issue required (issue id from sentry_search_issues)")
        val limit = VfsTools.str(args, "limit")?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        return get(project, "/api/0/issues/${enc(issueId)}/events/?limit=$limit").fold(
            onSuccess = { body ->
                val events = body.safeArray()
                if (events.isEmpty()) return@fold "No events found for Sentry issue $issueId."
                buildString {
                    append("Events for Sentry issue $issueId:\n")
                    events.forEachIndexed { i, e ->
                        val id = e["id"]?.jsonPrimitive?.content.orEmpty()
                        val ts = e["dateCreated"]?.jsonPrimitive?.content ?: e["timestamp"]?.jsonPrimitive?.content
                        val msg = e["message"]?.jsonPrimitive?.content.orEmpty()
                        append("\n").append(i + 1).append(". [event ").append(id).append("]")
                        ts?.let { append("  ").append(it.substringBefore('T')) }
                        append("\n   ").append(msg.ifEmpty { "(no message)" })
                        val ctx = e["contexts"]?.jsonObject
                        val sdk = e["sdk"]?.jsonObject
                        val frames = stackFrames(e)
                        if (frames.isNotEmpty()) {
                            append("\n   stack (top ").append(frames.size).append(" of ").append(frames.size).append("):")
                            frames.take(8).forEach { f ->
                                append("\n      at ").append(f)
                            }
                        }
                        sdk?.get("name")?.jsonPrimitive?.content?.let { append("  [sdk ").append(it).append("]") }
                        val platform = e["platform"]?.jsonPrimitive?.content
                        if (platform != null) append("  platform=").append(platform)
                        if (ctx != null) {
                            val os = ctx["os"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                            val runtime = ctx["runtime"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                            if (os != null) append("  os=").append(os)
                            if (runtime != null) append("  runtime=").append(runtime)
                        }
                    }
                    append("\n\nUse sentry_event_detail with an event id for the full forensic detail.")
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: sentry_issue_events failed — ${describe(project, e)}" },
        )
    }

    /** One full event: complete stacktrace, breadcrumbs, request/context. */
    fun eventDetail(project: Project, args: JsonObject): String {
        val org = requireOrg(project)
        val projectSlug = effectiveProject(project)
        val eventId = VfsTools.str(args, "event")?.trim()
            ?: throw IllegalArgumentException("event required (event id from sentry_issue_events)")
        val path = if (projectSlug.isNotEmpty()) {
            "/api/0/projects/${enc(org)}/${enc(projectSlug)}/events/${enc(eventId)}/"
        } else {
            "/api/0/events/${enc(eventId)}/"
        }
        return get(project, path).fold(
            onSuccess = { e ->
                buildString {
                    append("# Event ").append(eventId).append("\n\n")
                    e["title"]?.jsonPrimitive?.content?.let { append("## ").append(it).append("\n\n") }
                    e["message"]?.jsonPrimitive?.content?.let { append(it).append("\n\n") }
                    val ts = e["dateCreated"]?.jsonPrimitive?.content ?: e["timestamp"]?.jsonPrimitive?.content
                    ts?.let { append("_").append(it).append("_\n\n") }
                    val platform = e["platform"]?.jsonPrimitive?.content
                    platform?.let { append("Platform: ").append(it).append("\n") }

                    e["contexts"]?.jsonObject?.let { ctx ->
                        val os = ctx["os"]?.jsonObject
                        val runtime = ctx["runtime"]?.jsonObject
                        val browser = ctx["browser"]?.jsonObject
                        if (os != null || runtime != null || browser != null) {
                            append("\nContext:\n")
                            os?.let { o -> append("- os: ").append(o["name"]?.jsonPrimitive?.content.orEmpty())
                                .append(" ").append(o["version"]?.jsonPrimitive?.content.orEmpty()).append("\n") }
                            runtime?.let { r -> append("- runtime: ").append(r["name"]?.jsonPrimitive?.content.orEmpty())
                                .append(" ").append(r["version"]?.jsonPrimitive?.content.orEmpty()).append("\n") }
                            browser?.let { b -> append("- browser: ").append(b["name"]?.jsonPrimitive?.content.orEmpty())
                                .append(" ").append(b["version"]?.jsonPrimitive?.content.orEmpty()).append("\n") }
                        }
                    }

                    e["request"]?.jsonObject?.let { req ->
                        append("\nRequest: ").append(req["method"]?.jsonPrimitive?.content.orEmpty())
                            .append(" ").append(req["url"]?.jsonPrimitive?.content.orEmpty()).append("\n")
                    }

                    e["breadcrumbs"]?.jsonObject?.get("values")?.jsonArray?.let { crumbs ->
                        if (crumbs.isNotEmpty()) {
                            append("\nBreadcrumbs (recent first):\n")
                            crumbs.takeLast(10).forEach { c ->
                                val o = c.jsonObject
                                append("- [").append(o["timestamp"]?.jsonPrimitive?.content?.substringAfter('T')?.substringBefore('.') ?: "")
                                    .append("] ").append(o["type"]?.jsonPrimitive?.content.orEmpty())
                                    .append(" ").append(o["message"]?.jsonPrimitive?.content.orEmpty()).append("\n")
                            }
                        }
                    }

                    val frames = stackFrames(e)
                    if (frames.isNotEmpty()) {
                        append("\nStacktrace (top ").append(frames.size).append("):\n")
                        frames.take(20).forEach { f -> append("  ").append(f).append("\n") }
                    }

                    // User/extra context if present.
                    e["user"]?.jsonObject?.let { u ->
                        val id = u["id"]?.jsonPrimitive?.content
                        val email = u["email"]?.jsonPrimitive?.content
                        if (id != null || email != null) {
                            append("\nUser: ").append(email ?: id).append("\n")
                        }
                    }
                }.let { truncate(it) }
            },
            onFailure = { err -> "Error: sentry_event_detail failed — ${describe(project, err)}" },
        )
    }

    // ---------------------------------------------------------- connection test

    /** Used by the Configure Integrations dialog (Sentry tab): verifies token + org. */
    fun testConnection(baseUrl: String, token: String, org: String): String {
        if (token.isBlank()) return "⚠ Enter a Sentry auth token (Settings → User settings → Auth Tokens → Create new token)."
        val base = baseUrl.trim().removeSuffix("/").ifEmpty { "https://sentry.io" }
        val result = request(base + "/api/0/organizations/" + enc(org) + "/projects/", token, "GET")
        return result.fold(
            onSuccess = { body ->
                val projects = body.safeArray()
                val names = projects.take(5).mapNotNull { it["slug"]?.jsonPrimitive?.content }.joinToString(", ")
                "✓ Connected to Sentry org '$org' (${projects.size} project(s)" +
                    (if (names.isNotEmpty()) ": $names${if (projects.size > 5) ", …" else ""}" else "") + ")."
            },
            onFailure = { e ->
                val code = (e as? ApiException)?.code
                when (code) {
                    "401" -> "✗ Token rejected (401). Check the auth token and that it was not revoked " +
                        "(Settings → User settings → Auth Tokens)."
                    "403" -> "✗ Forbidden (403). The token needs org:read + project:read scopes."
                    "404" -> "✗ Org not found (404). Check the org slug — the readable part before .sentry.io " +
                        "in your Sentry URL."
                    else -> "✗ ${describeNetwork(e)}"
                }
            },
        )
    }

    // -------------------------------------------------------------- query build

    /** Org slug required for every query; mirrors the configured value. */
    private fun requireOrg(project: Project): String {
        val org = SentryState.getInstance(project).org()
        if (org.isEmpty()) throw IllegalArgumentException(
            "Sentry org is not configured for this project. Set it in the Configure Integrations dialog (Sentry tab)."
        )
        return org
    }

    /** The configured project slug, or "" when the agent queries the whole org. */
    private fun effectiveProject(project: Project): String =
        SentryState.getInstance(project).project()

    private fun scopeLabel(org: String, projectSlug: String): String =
        if (projectSlug.isNotEmpty()) "project '$projectSlug' (org '$org')" else "org '$org' (all projects)"

    /**
     * Translate a free-text agent query into Sentry search-syntax. Keeps known
     * filter tokens (`is:`, `level:`, `browser.name:`, `release:`,
     * `environment:`, `user:`, `message:`, `status:`, `project:`, quoted
     * phrases) as-is and quotes the remaining bare words so they're treated as
     * message text rather than rejected. Empty → "" (no query param).
     */
    internal fun translateQuery(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        // Tokenise on spaces, respecting double quotes.
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in trimmed) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == ' ' && !inQuote -> { if (sb.isNotEmpty()) { tokens += sb.toString(); sb.clear() } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()

        return tokens.joinToString(" ") { tok ->
            when {
                tok.startsWith("\"") && tok.endsWith("\"") -> tok // keep quoted phrase
                // Known filter/field tokens (optionally negated with a leading
                // "!") pass through untouched.
                Regex("^!?[a-zA-Z0-9_.\\-]+:(.*)$").matches(tok) -> tok
                // Bare word → treat as message search (quote it).
                else -> "\"$tok\""
            }
        }
    }

    // -------------------------------------------------------------- formatting

    /** Extract stack frames from an event's exception/stacktrace entries. */
    private fun stackFrames(e: JsonObject): List<String> {
        val out = mutableListOf<String>()
        e["entries"]?.jsonArray?.forEach { entry ->
            val o = runCatching { entry.jsonObject }.getOrNull() ?: return@forEach
            if (o["type"]?.jsonPrimitive?.content != "exception") return@forEach
            o["data"]?.jsonObject?.get("values")?.jsonArray?.forEach { valObj ->
                val v = runCatching { valObj.jsonObject }.getOrNull() ?: return@forEach
                v["stacktrace"]?.jsonObject?.get("frames")?.jsonArray?.forEach { fObj ->
                    val f = runCatching { fObj.jsonObject }.getOrNull() ?: return@forEach
                    val func = f["function"]?.jsonPrimitive?.content.orEmpty()
                    val file = f["filename"]?.jsonPrimitive?.content.orEmpty()
                    val line = f["lineNo"]?.jsonPrimitive?.content
                    val col = f["colNo"]?.jsonPrimitive?.content
                    if (file.isNotEmpty() || func.isNotEmpty()) {
                        val loc = if (file.isNotEmpty()) "$file${line?.let { ":$it" } ?: ""}" else ""
                        out += if (func.isNotEmpty()) "$func  ($loc)" else loc
                    }
                }
            }
        }
        return out
    }

    /** Safe array accessor (see NotionTool): unexpected shapes degrade to empty. */
    private fun JsonObject.safeArray(): List<JsonObject> =
        runCatching {
            (this["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        }.getOrNull() ?: emptyList()

    private fun truncate(s: String) = if (s.length > MAX_OUTPUT_CHARS) {
        s.take(MAX_OUTPUT_CHARS) + "\n\n[output truncated at $MAX_OUTPUT_CHARS characters]"
    } else s

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    // ------------------------------------------------------------------ HTTP

    private class ApiException(val code: String?, message: String) : RuntimeException(message)

    private fun tokenOrThrow(project: Project): String =
        SentryState.getInstance(project).token(project)
            ?.takeIf { it.isNotBlank() }
            ?: throw ApiException("unauthorized", "no Sentry auth token configured for this project")

    private fun baseUrl(project: Project): String =
        SentryState.getInstance(project).baseUrl()

    private fun get(project: Project, path: String) =
        request(baseUrl(project) + path, tokenOrThrow(project), "GET")

    private fun request(url: String, token: String, method: String): Result<JsonObject> {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, null)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(raw).jsonObject }
                        .getOrNull()
                    val detailCode = detail?.get("code")?.jsonPrimitive?.content
                    return Result.failure(
                        ApiException(resp.code.toString(), "HTTP ${resp.code}" +
                            (detailCode?.let { " ($it)" } ?: "") + ": ${raw.take(200)}"),
                    )
                }
                if (raw.isBlank()) return Result.success(JsonObject(emptyMap()))
                runCatching { json.parseToJsonElement(raw).jsonObject }
                    .recoverCatching { buildJsonObjectOf("raw", raw) }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun describe(project: Project, e: Throwable): String = when (e) {
        is ApiException -> when (e.code) {
            "401" -> "Sentry token rejected (401) — check the auth token in the Configure Integrations dialog (Sentry tab)"
            "403" -> "Sentry token lacks the needed scopes (403) — add org:read, project:read, event:read, issue:read"
            "404" -> "Sentry path not found (404) — wrong org/project slug? " +
                "Run sentry_list_projects to see the real slugs"
            "429" -> "Sentry rate limited (429) — try again shortly"
            else -> e.message ?: e.javaClass.simpleName
        }
        is java.net.UnknownHostException -> "unknown host '${e.message}' — check the Sentry base URL"
        is java.net.ConnectException -> "could not connect — is the Sentry base URL reachable?"
        is java.net.SocketTimeoutException -> "connection timed out after 30s — check the Sentry base URL/VPN"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun describeNetwork(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "unknown host '${e.message}' — check the Sentry base URL"
        is java.net.ConnectException -> "could not connect — is the Sentry base URL reachable?"
        is java.net.SocketTimeoutException -> "connection timed out — check the Sentry base URL/VPN"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun buildJsonObjectOf(key: String, value: String): JsonObject =
        kotlinx.serialization.json.buildJsonObject { put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
}