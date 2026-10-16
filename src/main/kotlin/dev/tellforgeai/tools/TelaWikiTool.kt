package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.TellForgeAIWikiState
import dev.tellforgeai.settings.WikiWriteAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Tela wiki access via tela's REST API (`{wikiUrl}/api/...`), authenticated
 * with a personal access token (tela: Settings -> API Keys) as a bearer header.
 * The token is stored in PasswordSafe; URL and options are per-project
 * (see [TellForgeAIWikiState]).
 *
 * Exposes four TellForgeAI tools:
 *  - `wiki_search`     — keyword search over pages (both modes, no approval)
 *  - `wiki_read_page`  — read one page's markdown body (both modes, no approval)
 *  - `wiki_write_page` — create a page (or replace one with the same title in
 *                        the target space). Forge mode only, gated by write
 *                        opt-in + the per-action "Ask permission" checkboxes.
 *  - `wiki_delete_page`— archive/delete a page. Forge mode only, same gates.
 *
 * API reference: {wikiUrl}/openapi.json. Errors are `{ "error": "...", "code": "..." }`.
 * Scopes: `read` tokens cannot write; writes need `write` scope + editor role
 * on the target space.
 */
object TelaWikiTool {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Hard cap covering DNS, connect and read so "Test connection" and the
        // wiki tools always fail fast with a clear message instead of hanging.
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val MAX_OUTPUT_CHARS = 12_000

    // ------------------------------------------------------------ tool entry

    fun search(project: Project, args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim()
            ?: throw IllegalArgumentException("query required")
        val resp = get(project, "/api/search?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8))
        return resp.fold(
            onSuccess = { body ->
                val hits = body["results"]?.jsonArray ?: return@fold "No wiki results for '$query'."
                if (hits.isEmpty()) return@fold "No wiki results for '$query'."
                buildString {
                    append("Tela wiki results for '").append(query).append("':\n")
                    for (hit in hits) {
                        val o = runCatching { hit.jsonObject }.getOrNull() ?: continue
                        append("\n- [").append(o["title"]?.jsonPrimitive?.content.orEmpty()).append("]")
                        append("(page id: ").append(o["page_id"]?.jsonPrimitive?.content.orEmpty()).append(")")
                        o["breadcrumb"]?.jsonArray?.let { bc ->
                            if (bc.isNotEmpty()) {
                                append("  (in: ").append(bc.joinToString(" / ") {
                                    it.jsonPrimitive.content
                                }).append(")")
                            }
                        }
                        append("\n  ").append(
                            o["snippet"]?.jsonPrimitive?.content.orEmpty()
                                .replace("<mark>", "").replace("</mark>", "").replace("\n", " ").take(200),
                        )
                    }
                    append("\n\nUse wiki_read_page with the page id to read a page.")
                    append("\nBefore creating pages, check this list for existing ones — ")
                    append("wiki_write_page with an existing title replaces that page instead.")
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: wiki_search failed — ${describe(e)}" },
        )
    }

    fun readPage(project: Project, args: JsonObject): String {
        val identifier = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (page id or exact title)")
        // Numeric → page id; otherwise resolve via search (first exact-title hit).
        val pageId = identifier.toLongOrNull()?.toString()
            ?: resolveTitleToId(project, identifier)
            ?: return "Error: no wiki page titled '$identifier'. Use wiki_search to find the exact title."

        return get(project, "/api/pages/$pageId").fold(
            onSuccess = { body ->
                val page = body["page"]?.jsonObject
                    ?: return@fold "Error: unexpected response reading page $pageId."
                buildString {
                    append("# ").append(page["title"]?.jsonPrimitive?.content.orEmpty())
                    append("\n\n")
                    append(page["body"]?.jsonPrimitive?.content.orEmpty())
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: wiki_read_page failed — ${describe(e)}" },
        )
    }

    private fun resolveTitleToId(project: Project, title: String): String? {
        val resp = get(project, "/api/search?q=" + java.net.URLEncoder.encode(title, Charsets.UTF_8))
        val hits = resp.getOrNull()?.get("results")?.jsonArray ?: return null
        for (hit in hits) {
            val o = runCatching { hit.jsonObject }.getOrNull() ?: continue
            if (o["title"]?.jsonPrimitive?.content.equals(title, ignoreCase = true)) {
                return o["page_id"]?.jsonPrimitive?.content
            }
        }
        // Fall back to the top hit.
        return (hits.firstOrNull() as? JsonObject)?.get("page_id")?.jsonPrimitive?.content
    }

    fun writePage(project: Project, args: JsonObject): String {
        val state = TellForgeAIWikiState.getInstance(project)
        val title = VfsTools.str(args, "title")?.trim()
            ?: throw IllegalArgumentException("title required")
        val body = VfsTools.str(args, "content").orEmpty()

        if (!state.configured(project)) {
            return "Error: no tela wiki is configured for this project. " +
                "Ask the user to open 'Configure Integrations…' (Integrations button in the tool window), " +
                "paste an access token and press 'Test connection'."
        }
        if (!state.writeEnabled()) {
            return "Error: wiki writes are not enabled for this project. " +
                "Ask the user to tick 'Allow the agent to create, update and delete wiki pages' " +
                "in the Configure Integrations dialog (Tela wiki tab)."
        }

        // Resolve space: explicit id → explicit name → first space.
        // When a write root is configured, creating is pinned to the root's
        // parent (and its space), overriding whatever the model passed.
        val rootId = state.writeRootId()
        if (rootId.isNotEmpty()) {
            val requestedSpaceId = VfsTools.str(args, "space")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { it.toLongOrNull()?.toString() ?: resolveSpaceByName(project, it) }
            val rootSpaceId = state.writeRootSpaceId()
            if (requestedSpaceId != null && rootSpaceId.isNotEmpty() && requestedSpaceId != rootSpaceId) {
                return "Error: writes are confined to the configured write root (page $rootId, " +
                    "space $rootSpaceId) — the requested space ($requestedSpaceId) is a different one. " +
                    "Ask the user to do this write themselves or to widen the write root."
            }
        }
        val spaceId = if (rootId.isNotEmpty() && state.writeRootSpaceId().isNotEmpty()) {
            state.writeRootSpaceId()
        } else {
            VfsTools.str(args, "space")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { it.toLongOrNull()?.toString() ?: resolveSpaceByName(project, it) }
                ?: firstSpaceId(project)
                ?: return "Error: could not determine a wiki space to write into. " +
                    "Ask the user to check the token's access (tela: Settings → API Keys)."
        }

        // Update when a page with this title already exists in the space, else create.
        val existingId = findPageInSpace(project, spaceId, title)
        val action = if (existingId != null) WikiWriteAction.UPDATE else WikiWriteAction.CREATE
        if (action == WikiWriteAction.UPDATE) {
            // Write-root gate: the page being replaced must sit under the root.
            val rootId = state.writeRootId()
            if (rootId.isNotEmpty() && !WriteRoot.isUnderTelaRoot(project, existingId!!, rootId)) {
                return WriteRoot.outsideRootError("tela wiki", rootId, "page '$title' (id $existingId)")
            }
            // Updating overwrites the whole page body — show it before sending.
            if (!approveWrite(project, action,
                    "TellForgeAI wants to REPLACE the body of wiki page '$title' (id $existingId):\n\n" +
                        body.take(500) + (if (body.length > 500) "\n…" else "") + "\n\nAllow?",
                )
            ) return "Wiki update declined by the user."
        }
        return if (existingId != null) {
            patch(project, "/api/pages/$existingId", buildJsonObject {
                put("title", title)
                put("body", body)
            }).fold(
                onSuccess = { "Updated wiki page '$title' (id $existingId)." },
                onFailure = { e -> "Error: wiki_write_page (update) failed — ${describe(e)}" },
            )
        } else {
            // Near-duplicate guard FIRST (before approval): a sibling page whose
            // title differs only by a trailing qualifier ("Foo 2", "Foo notes",
            // "(copy)") usually means the agent failed to find the real page —
            // make it retry with the existing page id instead of adding sprawl.
            nearDuplicateInSpace(project, spaceId, title)?.let { (id, t) ->
                return "Error: this looks like a duplicate of existing page '$t' (id $id). " +
                    "Do NOT create a copy — update the existing page with wiki_write_page " +
                    "using its exact title, or read it first with wiki_read_page."
            }
            if (!approveWrite(project, WikiWriteAction.CREATE,
                    "TellForgeAI wants to CREATE this tela wiki page:\n\n$title\n\n" +
                        body.take(500) + (if (body.length > 500) "\n…" else "") + "\n\nAllow?",
                )
            ) return "Wiki page creation declined by the user."
            val payload = buildJsonObject {
                put("space_id", spaceId.toLong())
                put("title", title)
                put("body", body)
                val rootId = state.writeRootId()
                when {
                    // Write root set → children of the root, always.
                    rootId.isNotEmpty() -> put("parent_id", rootId.toLong())
                    else -> parentId(project, spaceId, VfsTools.str(args, "parent")?.trim())
                        ?.let { put("parent_id", it.toLong()) }
                }
            }
            post(project, "/api/pages", payload).fold(
                onSuccess = { resp ->
                    val id = resp["page"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                    "Created wiki page '$title'${id?.let { " (id $it)" } ?: ""}."
                },
                onFailure = { e -> "Error: wiki_write_page failed — ${describe(e)}" },
            )
        }
    }

    /**
     * Per-action approval for mutating wiki tools: when the project's
     * "Ask permission to … page" checkbox for [action] is unchecked (skip),
     * writes proceed without a confirmation dialog.
     */
    private fun approveWrite(project: Project, action: WikiWriteAction, message: String): Boolean {
        if (TellForgeAIWikiState.getInstance(project).skipApproval(action)) return true
        return UiApprovals.ask(project, message, "Tela wiki ${action.name.lowercase()}")
    }

    /**
     * Deletes (archives) one wiki page by page id or exact title. Forge mode
     * only, gated by write opt-in + the "Ask permission to delete page" flag.
     */
    fun deletePage(project: Project, args: JsonObject): String {
        val state = TellForgeAIWikiState.getInstance(project)
        val identifier = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (page id or exact title)")

        if (!state.configured(project)) {
            return "Error: no tela wiki is configured for this project. " +
                "Ask the user to open 'Configure Integrations…' (Integrations button in the tool window), " +
                "paste an access token and press 'Test connection'."
        }
        if (!state.writeEnabled()) {
            return "Error: wiki writes are not enabled for this project. " +
                "Ask the user to tick 'Allow the agent to create, update and delete wiki pages' " +
                "in the Configure Integrations dialog (Tela wiki tab)."
        }

        val pageId = identifier.toLongOrNull()?.toString()
            ?: resolveTitleToId(project, identifier)
            ?: return "Error: no wiki page titled '$identifier'. Use wiki_search to find the exact title."

        // Read the title first so the confirmation names what is deleted.
        val title = get(project, "/api/pages/$pageId").getOrNull()
            ?.get("page")?.jsonObject?.get("title")?.jsonPrimitive?.content ?: identifier

        // Write-root gate BEFORE approval: deleting the root itself is always
        // refused (a tela delete takes the whole subtree to trash); deleting
        // anything outside the subtree is refused as out-of-scope.
        val rootId = TellForgeAIWikiState.getInstance(project).writeRootId()
        if (rootId.isNotEmpty()) {
            if (pageId == rootId) {
                return "Error: delete refused — the target is the write root itself " +
                    "(id $rootId); deleting it would take its whole subtree to trash. " +
                    "Ask the user to delete it manually if that is really intended."
            }
            if (!WriteRoot.isUnderTelaRoot(project, pageId, rootId)) {
                return WriteRoot.outsideRootError("tela wiki", rootId, "page $pageId")
            }
        }

        if (!approveWrite(project, WikiWriteAction.DELETE,
                "TellForgeAI wants to DELETE wiki page '$title' (id $pageId).\n\nAllow?",
            )
        ) return "Wiki delete declined by the user."

        return delete(project, "/api/pages/$pageId").fold(
            onSuccess = { "Deleted wiki page '$title' (id $pageId)." },
            onFailure = { e -> "Error: wiki_delete_page failed — ${describe(e)}" },
        )
    }

    private fun parentId(project: Project, spaceId: String, parent: String?): String? {
        if (parent.isNullOrEmpty()) return null
        return parent.toLongOrNull()?.toString() ?: findPageInSpace(project, spaceId, parent)
    }

    private fun findPageInSpace(project: Project, spaceId: String, title: String): String? {
        val resp = get(project, "/api/pages?space_id=$spaceId&tree=1")
        val pages = resp.getOrNull()?.get("pages")?.jsonArray ?: return null
        for (p in pages) {
            val o = runCatching { p.jsonObject }.getOrNull() ?: continue
            if (o["title"]?.jsonPrimitive?.content.equals(title, ignoreCase = true)) {
                return o["id"]?.jsonPrimitive?.content
            }
        }
        return null
    }

    /**
     * Possible near-duplicate of [title] in the space: an existing page whose
     * title is the same base stripped of a trailing "2", "(copy)", "notes"
     * style qualifier — or the requested title being that of an existing page
     * plus one. Returns (id, existingTitle) or null.
     */
    private fun nearDuplicateInSpace(project: Project, spaceId: String, title: String): Pair<String, String>? {
        val resp = get(project, "/api/pages?space_id=$spaceId&tree=1")
        val pages = resp.getOrNull()?.get("pages")?.jsonArray ?: return null
        val base = titleBase(title)
        for (p in pages) {
            val o = runCatching { p.jsonObject }.getOrNull() ?: continue
            val t = o["title"]?.jsonPrimitive?.content ?: continue
            if (t.equals(title, ignoreCase = true)) continue // exact handled elsewhere
            if (titleBase(t) == base) {
                return (o["id"]?.jsonPrimitive?.content ?: "") to t
            }
        }
        return null
    }

    /** Title without a trailing " 2", "(copy)", "- notes", " version" style qualifier. */
    private fun titleBase(title: String): String =
        title.replace(Regex("\\s*[-–(]?\\s*(copy|kopi|notes|note|draft|v\\d+|\\d+)\\s*\\)?\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()

    private fun resolveSpaceByName(project: Project, name: String): String? {
        val resp = get(project, "/api/spaces")
        val spaces = resp.getOrNull()?.get("spaces")?.jsonArray ?: return null
        for (s in spaces) {
            val o = runCatching { s.jsonObject }.getOrNull() ?: continue
            val n = o["name"]?.jsonPrimitive?.content.orEmpty()
            if (n.equals(name, ignoreCase = true) || o["slug"]?.jsonPrimitive?.content == name) {
                return o["id"]?.jsonPrimitive?.content
            }
        }
        return null
    }

    private fun firstSpaceId(project: Project): String? {
        val resp = get(project, "/api/spaces")
        val spaces = resp.getOrNull()?.get("spaces")?.jsonArray ?: return null
        return (spaces.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.content
    }

    // ------------------------------------------------------------ diagnostics

    /** Used by the Configure Integrations dialog: verifies URL + token, reports scope/spaces. */
    fun testConnection(baseUrl: String, token: String): String {
        if (baseUrl.isEmpty()) return "⚠ Enter a wiki URL first."
        if (token.isBlank()) return "⚠ Enter an access token (tela: Settings → API Keys)."
        // Accept base URL, /api or the MCP endpoint — all mean the same instance.
        var normalized = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/api/mcp", "/api", "/mcp")) {
            if (normalized.endsWith(suffix)) {
                normalized = normalized.removeSuffix(suffix)
                break
            }
        }
        normalized = normalized.trimEnd('/')

        // PAT bearer auth works on tela's REST API endpoints (spaces, pages, search)
        // but NOT on /api/auth/me (which uses session cookies).  So validate the
        // token by listing spaces instead — this is the same endpoint the tools use.
        val spaces = requestJson(normalized + "/api/spaces", token, "GET", null)
        val result = spaces.fold(
            onSuccess = { body ->
                val arr = body["spaces"]?.jsonArray
                if (arr == null || arr.isEmpty()) "No spaces visible to this token."
                else "Spaces: " + arr.take(8).joinToString(", ") {
                    (it as? JsonObject)?.get("name")?.jsonPrimitive?.content.orEmpty()
                } + (if (arr.size > 8) ", …" else "")
            },
            onFailure = { e ->
                val code = (e as? ApiException)?.code
                when (code) {
                    "unauthorized" -> "✗ Token rejected (401). " +
                        "Check the token is the raw tela_pat_… value shown once at creation, " +
                        "and that it has not expired or been revoked (tela: Settings → API Keys)."
                    "forbidden" -> "✗ Token works but lacks access to any space."
                    else -> "✗ ${describe(e)} (tried $normalized/api/spaces)"
                }
            },
        )
        return if (result.startsWith("Spaces:")) "✓ Connected. $result" else result
    }

    // ------------------------------------------------------------------ HTTP

    internal class ApiException(val code: String?, @Suppress("unused") message: String) : RuntimeException(message)

    internal fun describe(e: Throwable): String {
        if (e is ApiException) {
            return when (e.code) {
                "unauthorized" -> "token rejected (401 unauthorized) — check the token in the Configure Tela Wiki dialog"
                "forbidden" -> "not allowed (403 forbidden) — the token needs the write scope and editor role on the space"
                "forbidden_public" -> "published page only (403 forbidden_public) — your token is not a member of that space"
                "not_found" -> "not found (404)"
                "quota_exceeded" -> "quota exceeded"
                "rate_limited" -> "rate limited — try again shortly"
                "api_key_scope" -> "token scope too low (api_key_scope) — create a token with the write scope"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
        if (e is java.net.SocketTimeoutException) {
            return "connection timed out after 15s — check the URL, VPN/proxy and that the tela host is reachable in a browser"
        }
        if (e is java.net.UnknownHostException) return "unknown host '${e.message}' — check the wiki URL"
        if (e is java.net.ConnectException) return "could not connect — is the tela host reachable (URL, VPN, port)?"
        return e.message ?: e.javaClass.simpleName
    }

    private fun tokenOrThrow(project: Project): String =
        TellForgeAIWikiState.getInstance(project).token(project)
            ?.takeIf { it.isNotBlank() }
            ?: throw ApiException("unauthorized", "no tela wiki token configured for this project")

    private fun baseUrl(project: Project): String =
        TellForgeAIWikiState.getInstance(project).wikiUrl()

    private fun get(project: Project, path: String) =
        requestJson(baseUrl(project) + path, tokenOrThrow(project), "GET", null)

    /**
     * Package-visible GET for [WriteRoot]'s ancestry walk / verification —
     * same request as [get], just callable outside this object.
     */
    internal fun getRaw(project: Project, path: String) = get(project, path)

    private fun post(project: Project, path: String, payload: JsonObject) =
        requestJson(baseUrl(project) + path, tokenOrThrow(project), "POST", payload)

    private fun patch(project: Project, path: String, payload: JsonObject) =
        requestJson(baseUrl(project) + path, tokenOrThrow(project), "PATCH", payload)

    private fun delete(project: Project, path: String) =
        requestJson(baseUrl(project) + path, tokenOrThrow(project), "DELETE", null)

    private fun requestJson(url: String, token: String, method: String, payload: JsonObject?): Result<JsonObject> {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        when (method) {
            "POST" -> builder.post(
                (payload ?: JsonObject(emptyMap())).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            "PATCH" -> builder.patch(
                (payload ?: JsonObject(emptyMap())).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            "DELETE" -> builder.delete(
                (payload ?: JsonObject(emptyMap())).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            else -> builder.get()
        }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val code = runCatching {
                        json.parseToJsonElement(raw).jsonObject["code"]?.jsonPrimitive?.content
                    }.getOrNull()
                    return Result.failure(
                        ApiException(code, "HTTP ${resp.code}${code?.let { " ($it)" } ?: ""}: ${raw.take(200)}"),
                    )
                }
                if (raw.isBlank()) return Result.success(JsonObject(emptyMap()))
                runCatching { json.parseToJsonElement(raw).jsonObject }
                    .recoverCatching { buildJsonObjectOf("body", raw) }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildJsonObjectOf(key: String, value: String): JsonObject =
        kotlinx.serialization.json.buildJsonObject { put(key, kotlinx.serialization.json.JsonPrimitive(value)) }

    private fun truncate(text: String): String =
        if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n...(truncated)" else text
}

/** Small EDT helper so tool classes can ask yes/no without importing Swing everywhere. */
object UiApprovals {
    fun ask(project: Project?, message: String, title: String): Boolean {
        val approved = BooleanArray(1)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            approved[0] = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project, message, title, com.intellij.openapi.ui.Messages.getQuestionIcon(),
            ) == com.intellij.openapi.ui.Messages.YES
        }
        return approved[0]
    }
}
