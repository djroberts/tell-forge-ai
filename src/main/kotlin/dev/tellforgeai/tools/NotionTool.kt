package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.NotionState
import dev.tellforgeai.settings.WikiWriteAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Notion access via the public REST API (`https://api.notion.com/v1/...`),
 * authenticated with an internal-integration token ("ntn_…" / "secret_…")
 * as a bearer header plus the mandatory `Notion-Version` header. The token is
 * stored in PasswordSafe per project (see [NotionState]); nothing else roams.
 *
 * Exposes ten TellForgeAI tools:
 *  - `notion_search`         — POST /v1/search (both modes, no approval)
 *  - `notion_read_page`      — page flattened to markdown-ish text, optional
 *                              block-id markers (both modes, no approval)
 *  - `notion_query_database` — POST /v1/databases/{id}/query (both modes)
 *  - `notion_describe_database` — GET /v1/databases/{id}: schema (property
 *                              names/types/options) so filters aren't guessed
 *                              (both modes); also auto-attached to query
 *                              validation_error failures
 *  - `notion_write_page`     — POST /v1/pages, PAGE parent, paragraph blocks
 *  - `notion_append_blocks`  — PATCH /v1/blocks/{page}/children, extends an
 *                              existing page instead of duplicating it
 *  - `notion_update_page`    — PATCH /v1/pages/{id}: rename / move / archive
 *  - `notion_delete_page`    — archive (Notion has no hard delete)
 *  - `notion_update_block`   — PATCH /v1/blocks/{blockId}: in-place content
 *                              edit or archive of a single block
 *  - `notion_upload_file`    — File Upload API (single part ≤ 20 MB) with
 *                              optional attachment as an image/file block
 *  - `notion_create_comment` — POST /v1/comments (needs the "insert comments"
 *                              capability on the integration)
 *
 * All mutating tools are Forge mode only, gated by the project's one-time
 * write opt-in + per-approval dialog.
 *
 * Notion specifics handled here:
 *  - plain integration tokens are PAGE-SCOPED: the integration must be granted
 *    to the parent pages explicitly (Notion: page ··· menu → Connections);
 *    search only returns pages it can see.
 *  - 3 requests/sec rate limit: one retry on 429 honouring `Retry-After`.
 *  - block children are paginated (100/page) and followed recursively,
 *    flattening to markdown; databases and unsupported blocks become links.
 */
object NotionTool {

    private const val API = "https://api.notion.com/v1"
    private const val NOTION_VERSION = "2022-06-28"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Hard cap covering DNS, connect and read so "Test connection" and the
        // notion tools always fail fast with a clear message instead of hanging.
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val MAX_OUTPUT_CHARS = 12_000

    // ------------------------------------------------------------ tool entry

    /**
     * Raw search results as JSON for the integrations dialog's write-root
     * picker: same POST /v1/search as [search], without formatting.
     */
    internal fun searchForPicker(project: Project, query: String): Result<JsonObject> {
        val payload = buildJsonObject {
            if (query.isNotEmpty()) put("query", query)
            put("page_size", 8)
        }
        return post(project, "/search", payload)
    }

    fun search(project: Project, args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim().orEmpty()
        // Empty query = list accessible pages: the "query" field must be OMITTED,
        // not set to "" (Notion's /v1/search with no query lists everything the
        // integration can see; sending "" behaves differently).
        val payload = buildJsonObject {
            if (query.isNotEmpty()) put("query", query)
            put("page_size", 20)
        }
        return post(project, "/search", payload).fold(
            onSuccess = { body ->
                val results = body.safeArray("results")
                if (results.isEmpty()) return@fold "No Notion results for '$query'."
                buildString {
                    append("Notion results for '").append(query.ifEmpty { "(all)" }).append("':\n")
                    for (hit in results) {
                        val o = runCatching { hit.jsonObject }.getOrNull() ?: continue
                        val id = o["id"]?.jsonPrimitive?.content.orEmpty()
                        val kind = o["object"]?.jsonPrimitive?.content ?: "page"
                        append("\n- [").append(kind).append("] ").append(pageTitle(o))
                            .append("  (id: ").append(id).append(")")
                        val url = o["url"]?.jsonPrimitive?.content
                        if (!url.isNullOrBlank()) append("  ").append(url)
                    }
                    append("\n\nUse notion_read_page (pages) or notion_query_database (databases) with the id.")
                    append("\nBefore creating pages, check this list for existing ones — prefer ")
                    append("notion_append_blocks / notion_update_block over creating duplicates.")
                }.let { truncate(it) }
            },
            onFailure = { e -> "Error: notion_search failed — ${describe(e)}" },
        )
    }

    fun readPage(project: Project, args: JsonObject): String {
        val id = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (page id or title)")
        val withIds = (args["include_block_ids"]?.jsonPrimitive?.content ?: "")
            .equals("true", ignoreCase = true)
        val pageId = if (looksLikeUuid(id)) id else resolveTitleToId(project, id)
            ?: return "Error: no Notion page titled '$id'. Use notion_search to find the exact title."

        return get(project, "/pages/$pageId").fold(
            onSuccess = { meta ->
                val blocksResp = getBlocks(project, pageId, 0, withIds)
                val title = pageTitle(meta)
                val body = blocksResp ?: "_No readable block content._"
                buildString {
                    append("# ").append(title).append("\n\n").append(body)
                    if (withIds) append("\n\nBlock ids above can be edited with notion_update_block.")
                    meta["url"]?.jsonPrimitive?.content?.let { append("\n\nSource: ").append(it) }
                }.let { truncate(it) }
            },
            onFailure = { e ->
                val hint = (e as? ApiException)?.code?.let { code ->
                    if (code == "unauthorized" || code == "restricted_resource") {
                        " (the integration is not connected to this page — in Notion open the " +
                            "page's ··· menu → Connections → add this integration)"
                    } else null
                }
                "Error: notion_read_page failed — ${describe(e)}$hint"
            },
        )
    }

    fun writePage(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate
        val title = VfsTools.str(args, "title")?.trim()
            ?: throw IllegalArgumentException("title required")
        val content = VfsTools.str(args, "content").orEmpty()
        val parent = VfsTools.str(args, "parent")?.trim()

        // Write-root handling: when a root is configured, creates are pinned
        // to the root (a page parent, or rows in a database root) and the
        // model's parent arg is overridden or rejected.
        val rootId = NotionState.getInstance(project).writeRootId()
        var pinnedParent: String? = null
        var pinnedIsDatabase = false
        if (rootId.isNotEmpty()) {
            // Normalize to the dashless canonical form so comparison with
            // rootId (stored dashless) is like-for-like — Notion search
            // results and raw UUID args arrive dashed (see
            // WriteRoot.normalizeNotionId for why this matters).
            val resolvedRequestedParent = parent?.let {
                (if (looksLikeUuid(it)) it else resolveTitleToId(project, it))
                    ?.let(WriteRoot::normalizeNotionId)
            }
            if (resolvedRequestedParent != null && resolvedRequestedParent != rootId &&
                !WriteRoot.isUnderNotionRoot(project, resolvedRequestedParent, rootId)
            ) {
                return WriteRoot.outsideRootError("Notion", rootId, "parent '$parent'")
            }
            // The root page itself is a valid direct parent.
            pinnedParent = rootId
            pinnedIsDatabase = !looksLikeUuidPage(project, rootId)
            if (parent != null && resolvedRequestedParent != rootId) {
                // Requested parent inside the root subtree: honor it.
                pinnedParent = resolvedRequestedParent
            }
        }

        if (approve(project, WikiWriteAction.CREATE, "Notion write",
                "TellForgeAI wants to create this Notion page:" +
                    (pinnedParent?.let {
                        "\n\nParent: $it" + (if (pinnedIsDatabase) " (database root — the page becomes a row)" else "")
                    } ?: "\n\nParent: (no parent — Notion requires one)") +
                    "\n\nTitle: $title\n\nAllow?",
            ).not()
        ) {
            return "Notion write declined by the user."
        }

        val parentId = when {
            pinnedParent != null -> pinnedParent
            parent == null -> null
            looksLikeUuid(parent) -> parent
            else -> resolveTitleToId(project, parent)
                ?: return "Error: no Notion page titled '$parent' to use as parent. " +
                    "Use notion_search to find an exact title."
        } ?: return "Error: Notion requires a parent page. Ask the user to name a parent " +
            "(or search for one with notion_search and pass its id)."

        // Duplicate guard: refuse to create when an accessible page already
        // has this exact (or near-duplicate) title. Duplicate-page sprawl is
        // the classic LLM failure mode; the model self-corrects on the next
        // turn by appending/updating instead.
        (
            findExistingByTitle(project, title)?.let { id -> id to title }
                ?: nearDuplicatePage(project, title)?.let { t -> t.first to t.second }
            )?.let { (id, existing) ->
            return "Error: a Notion page titled '$existing' already exists (id $id). " +
                "Do NOT create a duplicate (e.g. '$title', '$title 2', '$title notes') — " +
                "update it instead with notion_append_blocks (add content) or " +
                "notion_update_block (edit in place); read it first with notion_read_page."
        }

        val payload = buildJsonObject {
            if (pinnedIsDatabase && parentId == rootId) {
                put("parent", buildJsonObject { put("database_id", parentId) })
            } else {
                put("parent", buildJsonObject { put("page_id", parentId) })
            }
            put("properties", buildJsonObject {
                put("title", buildJsonObject {
                    put("title", JsonArray(listOf(buildJsonObject {
                        put("text", buildJsonObject { put("content", title) })
                    })))
                })
            })
            put("children", content.toBlocks())
        }
        return post(project, "/pages", payload).fold(
            onSuccess = { resp ->
                val id = resp["id"]?.jsonPrimitive?.content
                "Created Notion page '$title'${id?.let { " (id $it)" } ?: ""}${resp["url"]?.jsonPrimitive?.content?.let { " — $it" } ?: ""}."
            },
            onFailure = { e ->
                val hint = (e as? ApiException)?.code?.let { code ->
                    if (code == "validation_error" && parent == null) {
                        " (check that the parent page id is correct and belongs to a page " +
                            "the integration is connected to)"
                    } else null
                }
                "Error: notion_write_page failed — ${describe(e)}$hint"
            },
        )
    }

    // --------------------------------------------------- write: append/update

    /**
     * Appends markdown content to an EXISTING page (or a block's children).
     * This is the preferred way to "fill" a page — creating a new page per
     * write is the classic duplicate-page failure mode.
     */
    fun appendBlocks(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val page = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (page id or title)")
        val content = VfsTools.str(args, "content")
            ?: throw IllegalArgumentException("content required (markdown)")
        val targetId = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
            ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
        // Canonical (dashless) form for the ancestry walk and messages —
        // resolveTitleToId normalizes, but a raw UUID arg may arrive dashed.
        val targetNorm = WriteRoot.normalizeNotionId(targetId)
        // Write-root gate: the target page must sit inside the root subtree.
        val rootId = NotionState.getInstance(project).writeRootId()
        if (rootId.isNotEmpty() && !WriteRoot.isUnderNotionRoot(project, targetNorm, rootId)) {
            return WriteRoot.outsideRootError("Notion", rootId, "page '$page' (id $targetNorm)")
        }

        if (approve(project, WikiWriteAction.CREATE, "Notion append",
                "TellForgeAI wants to append ${content.length} chars of content to Notion page " +
                    "'$page' (id $targetNorm).\n\nAllow?",
            ).not()
        ) return "Notion append declined by the user."

        // Children of a PAGE work directly; otherwise the id must be a block id.
        val blocks = content.toBlocks()
        return patch(project, "/blocks/$targetNorm/children", buildJsonObject {
            put("children", blocks)
        }).fold(
            onSuccess = { resp ->
                val count = resp["results"]?.jsonArray?.size ?: blocks.size
                "Appended $count block(s) to Notion page '$page' (id $targetNorm)."
            },
            onFailure = { e ->
                "Error: notion_append_blocks failed — ${describe(e)}" +
                    (if ((e as? ApiException)?.code == "unauthorized")
                        " (the integration may not be connected to this page — page ··· menu → " +
                            "Connections → add this integration)" else "")
            },
        )
    }

    /**
     * Updates page properties: rename (title), move to another parent page,
     * and/or archive ("delete" — Notion's API only offers soft delete).
     */
    fun updatePage(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val page = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (page id or title)")
        val title = VfsTools.str(args, "title")?.trim()
        val parent = VfsTools.str(args, "parent")?.trim()
        val archive = (args["archive"]?.jsonPrimitive?.content ?: "").equals("true", ignoreCase = true)
        if (title == null && parent == null && !archive) {
            return "Error: nothing to do — pass title, parent and/or archive=true."
        }

        val pageId = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
            ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
        // Canonical form for the root gates and API path (raw UUID args may
        // arrive dashed; see WriteRoot.normalizeNotionId).
        val pageNorm = WriteRoot.normalizeNotionId(pageId)

        var parentId: String? = null
        if (parent != null) {
            parentId = if (looksLikeUuid(parent)) parent else resolveTitleToId(project, parent)
                ?: return "Error: no Notion page titled '$parent' to use as parent."
            parentId = WriteRoot.normalizeNotionId(parentId)
        }

        // Write-root gate: target must be inside the root subtree (moving the
        // root itself or anything outside is out of scope), and a move's new
        // parent must land inside the subtree too — a move is otherwise the
        // escape hatch that defeats confinement.
        val rootId = NotionState.getInstance(project).writeRootId()
        if (rootId.isNotEmpty()) {
            if (!WriteRoot.isUnderNotionRoot(project, pageNorm, rootId)) {
                return WriteRoot.outsideRootError("Notion", rootId, "page '$page' (id $pageNorm)")
            }
            if (parentId != null &&
                parentId != rootId &&
                !WriteRoot.isUnderNotionRoot(project, parentId, rootId)
            ) {
                return WriteRoot.outsideRootError("Notion", rootId, "move destination '$parent'")
            }
        }

        val summary = listOfNotNull(
            title?.let { "rename to '$it'" },
            parentId?.let { "move under '$parent'" },
            if (archive) "archive (move to trash)" else null,
        ).joinToString(" and ")

        // A page archive is a DELETE, distinct from a property/move update:
        // each is gated by its own "Ask permission to … page" checkbox.
        val action = if (archive) WikiWriteAction.DELETE else WikiWriteAction.UPDATE
        val verb = if (archive) "delete" else "update"
        if (approve(project, action, "Notion $verb",
                "TellForgeAI wants to $verb Notion page '$page' (id $pageNorm): $summary.\n\nAllow?",
            ).not()
        ) return "Notion $verb declined by the user."

        val payload = buildJsonObject {
            if (archive) put("archived", true)
            if (title != null) {
                put("properties", buildJsonObject {
                    put("title", buildJsonObject {
                        put("title", JsonArray(listOf(buildJsonObject {
                            put("text", buildJsonObject { put("content", title) })
                        })))
                    })
                })
            }
            if (parentId != null) {
                put("parent", buildJsonObject { put("page_id", parentId) })
            }
        }
        return patch(project, "/pages/$pageNorm", payload).fold(
            onSuccess = { "Updated Notion page '$page' (id $pageNorm): $summary." },
            onFailure = { e ->
                "Error: notion_update_page failed — ${describe(e)}" +
                    (if ((e as? ApiException)?.code == "validation_error" && parent != null)
                        " (moving pages may be limited for some parent types; try moving in the UI)" else "")
            },
        )
    }

    /** Convenience alias: archive a page (Notion has no hard delete via API). */
    fun deletePage(project: Project, args: JsonObject): String =
        updatePage(project, buildJsonObject {
            args.forEach { (k, v) -> put(k, v) }
            put("archive", JsonPrimitive("true"))
        })

    /**
     * In-place edit of a single block's text content (paragraph, headings,
     * list items, quote, toggle) — the tool that lets the agent fix a page
     * without rewriting the whole thing. Requires a block id, which
     * notion_read_page emits as `(block_id: …)` markers when
     * `include_block_ids=true`.
     */
    fun updateBlock(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val blockId = VfsTools.str(args, "blockId")?.trim()?.takeIf { looksLikeUuid(it) }
            ?: return "Error: blockId required — a block id from notion_read_page " +
                "(call it with include_block_ids=true; ids appear as (block_id: …))."
        val content = VfsTools.str(args, "content")
            ?: throw IllegalArgumentException("content required (new text for the block, or any value when archive=true)")
        val archive = (args["archive"]?.jsonPrimitive?.content ?: "").equals("true", ignoreCase = true)

        // Write-root gate: the block's page must sit inside the root subtree.
        // The block's own parent chain is the same walk (blocks carry the
        // same parent object, eventually naming the page).
        val rootId = NotionState.getInstance(project).writeRootId()
        if (rootId.isNotEmpty() && !WriteRoot.isUnderNotionRoot(project, blockId, rootId)) {
            return WriteRoot.outsideRootError("Notion", rootId, "block $blockId")
        }

        if (approve(project, WikiWriteAction.UPDATE, "Notion block edit",
                "TellForgeAI wants to ${if (archive) "remove (archive)" else "replace the text of"} " +
                    "Notion block $blockId with:\n\n${content.take(500)}\n\nAllow?",
            ).not()
        ) return "Notion block edit declined by the user."

        val payload = if (archive) buildJsonObject { put("archived", true) }
        else buildJsonObject {
            put(typeHint(args, content), buildJsonObject { put("rich_text", richText(content)) })
        }
        return patch(project, "/blocks/$blockId", payload).fold(
            onSuccess = {
                if (archive) "Archived Notion block $blockId."
                else "Updated text of Notion block $blockId."
            },
            onFailure = { e ->
                val hint = (e as? ApiException)?.code?.let { code ->
                    when {
                        code == "validation_error" ->
                            " (this block type may not support text replacement; only paragraph, " +
                                "headings, list items, quote and toggle are editable)"
                        else -> null
                    }
                }
                "Error: notion_update_block failed — ${describe(e)}$hint"
            },
        )
    }

    /** Guesses the block type for rich-text replacement, defaulting to paragraph. */
    private fun typeHint(args: JsonObject, content: String): String {
        val explicit = VfsTools.str(args, "blockType")?.trim()
        if (explicit != null) return explicit
        return when {
            content.startsWith("# ") -> "heading_1"
            content.startsWith("## ") -> "heading_2"
            content.startsWith("### ") -> "heading_3"
            content.startsWith("- ") || content.startsWith("* ") -> "bulleted_list_item"
            else -> "paragraph"
        }
    }

    /**
     * True when [id] resolves to a PAGE (not a database) — used to decide
     * whether a database-root create needs `parent.database_id` instead of
     * `parent.page_id`. Cheap: one GET, cached for the session.
     */
    private fun looksLikeUuidPage(project: Project, id: String): Boolean {
        val key = WriteRoot.normalizeNotionId(id)
        val cached = rootKindCache[key]
        if (cached != null) return cached
        val isPage = NotionTool.getForRootCheck(project, "/pages/$key").isSuccess
        rootKindCache[key] = isPage
        return isPage
    }

    /** Session cache for [looksLikeUuidPage]: normalized root id → is-a-page. */
    private val rootKindCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ------------------------------------------------------------ file upload

    /**
     * Uploads a file from the project VFS via the File Upload API (single
     * part, ≤ ~20 MB) and optionally attaches it as an image or file block at
     * the end of a target page. Two API calls + one multipart POST.
     */
    fun uploadFile(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val path = VfsTools.str(args, "path")?.trim()
            ?: throw IllegalArgumentException("path required (project-relative file path)")
        val page = VfsTools.str(args, "page")?.trim()
        val caption = VfsTools.str(args, "caption")?.trim()

        val vf = runCatching {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByNioFile(java.nio.file.Path.of(project.basePath ?: ".", path))
        }.getOrNull()
            ?: return "Error: file not found: $path (path is relative to the project root)."
        if (vf.isDirectory) return "Error: $path is a directory; only single files can be uploaded."
        val bytes = vf.contentsToByteArray()
        if (bytes.size > 20 * 1024 * 1024) {
            return "Error: file is ${bytes.size / 1024 / 1024} MB; the API supports single-part " +
                "uploads up to ~20 MB."
        }
        val filename = vf.name
        val contentType = guessMimeType(filename)

        // Notion rejects some text-ish extensions (.asc, .log, .conf, …) with
        // a validation error. If the bytes are actually text, upload under a
        // Notion-friendly name instead: "foo.asc" → "foo_asc.txt". Extensions
        // the API already accepts (.txt/.md/.json/…) are never renamed.
        val acceptedExts = setOf("txt", "md", "markdown", "csv", "json", "yml", "yaml",
            "xml", "html", "svg", "png", "jpg", "jpeg", "gif", "webp", "pdf", "zip")
        val ext = filename.substringAfterLast('.', "").lowercase()
        val effectiveFilename = if (ext !in acceptedExts && isPlainText(bytes)) {
            textUploadFilename(filename)
        } else filename
        val uploadContentType = if (effectiveFilename != filename) "text/plain" else contentType

        // Write-root gate: attaching to a page outside the root subtree is
        // out of scope (the upload itself is harmless without a target page).
        if (page != null) {
            val rootId = NotionState.getInstance(project).writeRootId()
            if (rootId.isNotEmpty()) {
                val pageId0 = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
                    ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
                if (!WriteRoot.isUnderNotionRoot(project, pageId0, rootId)) {
                    return WriteRoot.outsideRootError("Notion", rootId, "page '$page'")
                }
            }
        }

        val action = listOfNotNull(
            "upload '$path' (${bytes.size} bytes)",
            page?.let { "attach to page '$it'" },
        ).joinToString(", then ")

        if (approve(project, WikiWriteAction.UPDATE, "Notion upload",
                "TellForgeAI wants to $action.\n\nAllow?",
            ).not()
        ) return "Notion upload declined by the user."

        val token = token(project)

        // 1. Create the pending upload.
        val created = request("POST", "$API/file_uploads", token, buildJsonObject {
            put("mode", "single_part")
            put("filename", effectiveFilename)
            put("content_type", uploadContentType)
        }.toString().toRequestBody("application/json".toMediaType()))
            .getOrElse { return "Error: notion_upload_file failed (create) — ${describe(it)}" }
        val uploadId = created["id"]?.jsonPrimitive?.content
            ?: return "Error: notion_upload_file failed — no upload id in response."
        val uploadUrl = created["upload_url"]?.jsonPrimitive?.content

        // 2. Send the bytes (multipart, to upload_url for single_part mode).
        val mime = uploadContentType.toMediaType()
        val fileBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", effectiveFilename, bytes.toRequestBody(mime))
            .build()
        val sent = request("POST", uploadUrl ?: "$API/file_uploads/$uploadId/send", token, fileBody)
            .getOrElse { return "Error: notion_upload_file failed (send) — ${describe(it)}" }
        val finalId = sent["id"]?.jsonPrimitive?.content ?: uploadId

        // 3. Optionally attach as an image/file block.
        if (page != null) {
            val pageId = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
                ?: return "Uploaded as file_upload $finalId, but no Notion page titled '$page' " +
                    "to attach it to. Attach manually or retry with a valid page."
            val blockType = if (contentType.startsWith("image/")) "image" else "file"
            val attach = patch(project, "/blocks/$pageId/children", buildJsonObject {
                put("children", JsonArray(listOf(block(blockType, buildJsonObject {
                    put("type", "file_upload")
                    put("file_upload", buildJsonObject {
                        put("id", finalId)
                        caption?.let { put("caption", richText(it)) }
                    })
                }))))
            })
            return attach.fold(
                onSuccess = {
                    val renameNote = if (effectiveFilename != filename)
                        " (uploaded as '$effectiveFilename' — Notion does not accept the original extension)"
                    else ""
                    "Uploaded '$path'$renameNote (file_upload $finalId) and attached it to '$page'."
                },
                onFailure = { e ->
                    "Uploaded '$path' (file_upload $finalId) but attaching to '$page' failed: " +
                        "${describe(e)}. The upload is valid for 1 hour — attach it manually " +
                        "or retry the attach step."
                },
            )
        }
        return "Uploaded '$path' to Notion (file_upload id $finalId)" +
            (if (effectiveFilename != filename) " as '$effectiveFilename' — Notion does not accept the original extension" else "") +
            ". Attach it with notion_append_blocks using an image/file block with " +
            "\"file_upload\": {\"id\": \"$finalId\"} (valid for 1 hour)."
    }

    // ------------------------------------------------ text-extension fallback

    /**
     * Notion's File Upload API rejects a set of text-ish extensions (asc, log,
     * conf, ini, …) with a validation error even though the content is plain
     * text. When that happens the working convention is to upload the same
     * bytes under a .txt name, keeping the original extension visible as a
     * suffix: "foo.asc" → "foo_asc.txt". Renaming only when [isPlainText]
     * confirms the bytes really are text — a binary blob must never be
     * relabeled .txt.
     */
    private fun textUploadFilename(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name.txt" else name.substring(0, dot) + "_" + name.substring(dot + 1) + ".txt"
    }

    /** Heuristic text sniff: no NUL bytes and mostly printable/whitespace. */
    private fun isPlainText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.take(8 * 1024)
        if (sample.any { it == 0.toByte() }) return false
        val printable = sample.count { it in 9..13 || it in 32..126 || it < 0 } // tab/LF/CR, ASCII, UTF-8 high bytes
        return printable.toDouble() / sample.size > 0.95
    }

    /**
     * "Moves" a Notion file/image block to another page. The Notion API has no
     * block re-parenting for file blocks, so this is a copy-then-delete:
     * download the signed file URL to a TEMP file (every OS has one via
     * java.io.tmpdir; the file is explicitly deleted in a `finally` whenever
     * this call ends, success or failure), upload it fresh via the File
     * Upload API, attach it to the target page, and only then archive the
     * original block — archive last so a mid-flight failure never loses the
     * attachment.
     */
    fun moveFile(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val blockIdRaw = VfsTools.str(args, "block_id")?.trim()
            ?: throw IllegalArgumentException("block_id required (id of the file/image block to move)")
        val page = VfsTools.str(args, "page")?.trim()
            ?: throw IllegalArgumentException("page required (target page id or title)")
        val caption = VfsTools.str(args, "caption")?.trim()

        val blockId = WriteRoot.normalizeNotionId(blockIdRaw)

        // Write-root gate: both source block and target page must sit inside
        // the root subtree (moving out of it would defeat confinement).
        val rootId = NotionState.getInstance(project).writeRootId()
        if (rootId.isNotEmpty()) {
            if (!WriteRoot.isUnderNotionRoot(project, blockId, rootId)) {
                return WriteRoot.outsideRootError("Notion", rootId, "file block $blockId")
            }
            val pageId0 = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
                ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
            if (!WriteRoot.isUnderNotionRoot(project, WriteRoot.normalizeNotionId(pageId0), rootId)) {
                return WriteRoot.outsideRootError("Notion", rootId, "target page '$page'")
            }
        }

        // 1. Read the source block: must be a file or image block with an
        //    internal (Notion-hosted) URL; external URLs can just be
        //    re-embedded on the target page losslessly, no temp needed.
        val block = get(project, "/blocks/$blockId").getOrElse {
            return "Error: could not read file block $blockId — ${describe(it)}"
        }
        val type = block["type"]?.jsonPrimitive?.content
        if (type != "file" && type != "image") {
            return "Error: block $blockId is a '$type' block, not a file/image block — " +
                "only file attachment and image blocks can be moved. " +
                "Use notion_read_page with include_block_ids=true to find block ids."
        }
        val fileObj = block[type]?.jsonObject
        val fileUrl = fileObj?.get("file")?.jsonObject?.get("url")?.jsonPrimitive?.content
        val externalUrl = fileObj?.get("external")?.jsonObject?.get("url")?.jsonPrimitive?.content

        val targetPageId = if (looksLikeUuid(page)) WriteRoot.normalizeNotionId(page)
        else WriteRoot.normalizeNotionId(resolveTitleToId(project, page)
            ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title.")

        if (approve(project, WikiWriteAction.UPDATE, "Notion move file",
                "TellForgeAI wants to move a '$type' file block (id $blockId) to page '$page' " +
                    "(id $targetPageId). This re-uploads the file and archives the original block.\n\nAllow?",
            ).not()
        ) return "Notion move-file declined by the user."

        val token = token(project)

        // External URL: no bytes to move — embed the same URL on the target
        // page and archive the source. Lossless, no temp file involved.
        if (fileUrl == null && externalUrl != null) {
            val attach = patch(project, "/blocks/$targetPageId/children", buildJsonObject {
                put("children", JsonArray(listOf(block("file", buildJsonObject {
                    put("type", "external")
                    put("external", buildJsonObject {
                        put("url", externalUrl)
                        caption?.let { put("caption", richText(it)) }
                    })
                }))))
            })
            if (attach.isFailure) {
                return "Error: attaching external file to '$page' failed — ${describe(attach.exceptionOrNull()!!)}"
            }
            archiveBlock(project, blockId)
            return "Moved external file from block $blockId to '$page' (by re-embedding the URL)."
        }
        if (fileUrl == null) {
            return "Error: block $blockId has no file URL — cannot move it."
        }
        val filename = fileObj?.get("name")?.jsonPrimitive?.content
            ?: fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "file" }

        // 2. Download to a TEMP file. Temp is deleted when this call ends,
        //    success or failure; deleteOnExit covers a hard crash in between.
        val temp = runCatching {
            java.nio.file.Files.createTempFile("tellforgeai-notion-", "-" + filename.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                .also { it.toFile().deleteOnExit() }
        }.getOrElse { e ->
            return "Error: could not create a temp file (${System.getProperty("java.io.tmpdir")}) — ${e.message}"
        }
        try {
            val bytes = runCatching {
                client.newCall(Request.Builder().url(fileUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException("http_${resp.code}", "HTTP ${resp.code} downloading file")
                    val b = (resp.body ?: throw ApiException(null, "empty response body")).bytes()
                    if (b.size > 20 * 1024 * 1024) {
                        throw ApiException("payload_too_large",
                            "file is ${b.size / 1024 / 1024} MB; the API supports single-part uploads up to ~20 MB")
                    }
                    b
                }
            }.getOrElse { e ->
                // Signed S3 URLs expire (~1 h) — a dead URL means the caller
                // must re-fetch the block, not retry blind.
                return if (e is ApiException && (e.code ?: "").startsWith("http_")) {
                    "Error: downloading the file failed (${e.message}). The signed URL may have " +
                        "expired — read the block again with notion_read_page (include_block_ids=true) " +
                        "and retry with the fresh block id."
                } else {
                    "Error: downloading the file failed — ${describe(e)}"
                }
            }

            // 3. Upload via the File Upload API (same flow as uploadFile).
            // Same Notion-extension fallback: text bytes under an extension
            // the API rejects are uploaded as "foo_asc.txt".
            val acceptedExts = setOf("txt", "md", "markdown", "csv", "json", "yml", "yaml",
                "xml", "html", "svg", "png", "jpg", "jpeg", "gif", "webp", "pdf", "zip")
            val ext = filename.substringAfterLast('.', "").lowercase()
            val uploadFilename = if (ext !in acceptedExts && isPlainText(bytes)) {
                textUploadFilename(filename)
            } else filename
            val uploadContentType = if (uploadFilename != filename) "text/plain" else guessMimeType(filename)
            val created = request("POST", "$API/file_uploads", token, buildJsonObject {
                put("mode", "single_part")
                put("filename", uploadFilename)
                put("content_type", uploadContentType)
            }.toString().toRequestBody("application/json".toMediaType()))
                .getOrElse { return "Error: notion_move_file failed (create upload) — ${describe(it)}" }
            val uploadId = created["id"]?.jsonPrimitive?.content
                ?: return "Error: notion_move_file failed — no upload id in response."
            val uploadUrl = created["upload_url"]?.jsonPrimitive?.content
            val sent = request("POST", uploadUrl ?: "$API/file_uploads/$uploadId/send", token,
                okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("file", uploadFilename, bytes.toRequestBody(uploadContentType.toMediaType()))
                    .build())
                .getOrElse { return "Error: notion_move_file failed (send) — ${describe(it)}" }
            val finalId = sent["id"]?.jsonPrimitive?.content ?: uploadId

            // 4. Attach to the target page.
            val blockType = if (uploadContentType.startsWith("image/")) "image" else "file"
            val attach = patch(project, "/blocks/$targetPageId/children", buildJsonObject {
                put("children", JsonArray(listOf(block(blockType, buildJsonObject {
                    put("type", "file_upload")
                    put("file_upload", buildJsonObject {
                        put("id", finalId)
                        caption?.let { put("caption", richText(it)) }
                    })
                }))))
            })
            if (attach.isFailure) {
                return "Moved block $blockId as far as the upload (file_upload $finalId, valid 1 hour) " +
                    "but attaching to '$page' failed — ${describe(attach.exceptionOrNull()!!)}. " +
                    "The original block is untouched; attach manually or retry."
            }

            // 5. Archive the original block LAST (archive-first would lose
            //    the attachment on a mid-flight failure).
            archiveBlock(project, blockId)
            return "Moved file '$filename'" +
                (if (uploadFilename != filename) " (uploaded as '$uploadFilename' — Notion does not " +
                    "accept the original extension)" else "") +
                " from block $blockId to page '$page' (id $targetPageId) and archived the original block."
        } finally {
            // Always clean up the temp file, even on failure.
            runCatching { java.nio.file.Files.deleteIfExists(temp) }
        }
    }

    /** Archives (soft-deletes) one block; best-effort, returns silently on failure. */
    private fun archiveBlock(project: Project, blockId: String) {
        val payload = buildJsonObject { put("archived", true) }
        val token = token(project)
        request("PATCH", "$API/blocks/$blockId", token,
            payload.toString().toRequestBody("application/json".toMediaType()))
    }

    /** Best-effort MIME guess from the filename extension. */
    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    // ------------------------------------------------------------- comments

    /**
     * Creates a comment on a page (or in an existing discussion thread).
     * Requires the integration to have the "insert comments" capability
     * (checkbox when creating the integration token).
     */
    fun createComment(project: Project, args: JsonObject): String {
        val gate = requireWriteEnabled(project)
        if (gate != null) return gate

        val content = VfsTools.str(args, "content")?.trim()
            ?: throw IllegalArgumentException("content required (comment text)")
        val page = VfsTools.str(args, "page")?.trim()
        val discussionId = VfsTools.str(args, "discussionId")?.trim()
        if (page == null && discussionId == null) {
            return "Error: pass either page (page id or title) or discussionId."
        }

        // Write-root gate: commenting is a mutation on the target page.
        if (page != null) {
            val rootId = NotionState.getInstance(project).writeRootId()
            if (rootId.isNotEmpty()) {
                val pageId0 = if (looksLikeUuid(page)) page else resolveTitleToId(project, page)
                    ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
                if (!WriteRoot.isUnderNotionRoot(project, pageId0, rootId)) {
                    return WriteRoot.outsideRootError("Notion", rootId, "page '$page'")
                }
            }
        }

        if (approve(project, WikiWriteAction.CREATE, "Notion comment",
                "TellForgeAI wants to comment on Notion " +
                    (page?.let { "page '$it'" } ?: "discussion $discussionId") + ":\n\n" +
                    content.take(500) + "\n\nAllow?",
            ).not()
        ) return "Notion comment declined by the user."

        val payload = buildJsonObject {
            put("rich_text", richText(content))
            if (discussionId != null) {
                put("discussion_id", discussionId)
            } else {
                val pageId = if (looksLikeUuid(page!!)) page else resolveTitleToId(project, page)
                    ?: return "Error: no Notion page titled '$page'. Use notion_search to find the exact title."
                put("parent", buildJsonObject { put("page_id", pageId) })
            }
        }
        return post(project, "/comments", payload).fold(
            onSuccess = { resp ->
                val id = resp["id"]?.jsonPrimitive?.content
                "Created Notion comment${id?.let { " (id $it)" } ?: ""}."
            },
            onFailure = { e ->
                val hint = (e as? ApiException)?.code?.let { code ->
                    if (code == "unauthorized" || code == "restricted_resource")
                        " (the integration needs the 'insert comments' capability — recreate the " +
                            "token with that capability ticked, and connect it to the page)"
                    else null
                }
                "Error: notion_create_comment failed — ${describe(e)}$hint"
            },
        )
    }

    // ---------------------------------------------------- shared write gate

    /**
     * Common gate for all mutating Notion tools: the project must have Notion
     * configured and the user must have opted in to writes. Returns null when
     * the gate passes, otherwise an error string.
     */
    private fun requireWriteEnabled(project: Project): String? = when {
        !NotionState.getInstance(project).configured(project) ->
            "Error: Notion is not configured for this project. " +
                "Ask the user to open 'Configure Integrations…' (Integrations button in the tool window), " +
                "Notion tab, paste an integration token and press 'Test connection'."
        !NotionState.getInstance(project).writeEnabled() ->
            "Error: Notion writes are not enabled for this project. " +
                "Ask the user to tick 'Allow the agent to create, update and delete Notion pages' " +
                "in the Configure Integrations dialog (Notion tab)."
        else -> null
    }

    /**
     * Per-action approval for mutating Notion tools, keyed by
     * [WikiWriteAction]: when the action's "Ask permission to … page"
     * checkbox is unchecked in the Configure Integrations dialog (skip), the
     * call proceeds without a confirmation dialog.
     */
    private fun approve(project: Project, action: WikiWriteAction, message: String, title: String): Boolean {
        if (NotionState.getInstance(project).skipApproval(action)) return true
        return UiApprovals.ask(project, message, title)
    }

    // ------------------------------------------------------- title helpers

    /** Extracts the title property value from a page/search-result object. */
    internal fun pageTitle(o: JsonObject): String {
        val props = o["properties"] as? JsonObject ?: return "(untitled)"
        for ((_, v) in props) {
            val vo = v as? JsonObject ?: continue
            if (vo["type"]?.jsonPrimitive?.content == "title") {
                val arr = vo["title"] as? JsonArray ?: continue
                return arr.joinToString("") { (it as? JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty() }
                    .ifBlank { "(untitled)" }
            }
        }
        return "(untitled)"
    }

    private fun resolveTitleToId(project: Project, title: String): String? =
        findExistingByTitle(project, title)?.first ?: fuzzyTitleToId(project, title)

    /** Legacy fallback: top search hit for the title, exact or not. */
    private fun fuzzyTitleToId(project: Project, title: String): String? {
        val resp = post(project, "/search", buildJsonObject {
            put("query", title)
            put("filter", buildJsonObject {
                put("property", "object")
                put("value", "page")
            })
        })
        val hits = resp.getOrNull()?.get("results")?.jsonArray ?: return null
        return (hits.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.content
            ?.let(WriteRoot::normalizeNotionId)
    }

    /**
     * Near-duplicate check for the write guard: an accessible page whose title
     * matches after stripping a trailing "2"/"(copy)"/"- notes" style
     * qualifier (either side) — the residue of failed search-then-create
     * loops. Returns (id, existingTitle) or null.
     */
    private fun nearDuplicatePage(project: Project, title: String): Pair<String, String>? {
        val base = titleBase(title)
        if (base.isEmpty()) return null
        val resp = post(project, "/search", buildJsonObject {
            put("query", base)
            put("filter", buildJsonObject {
                put("property", "object")
                put("value", "page")
            })
        })
        val hits = resp.getOrNull()?.get("results")?.jsonArray ?: return null
        for (hit in hits) {
            val o = runCatching { hit.jsonObject }.getOrNull() ?: continue
            val t = pageTitle(o)
            if (t.equals(title, ignoreCase = true)) continue
            if (titleBase(t) == base) {
                return (o["id"]?.jsonPrimitive?.content?.let(WriteRoot::normalizeNotionId) ?: "") to t
            }
        }
        return null
    }

    /** Title without a trailing " 2", "(copy)", "- notes", " version" style qualifier. */
    private fun titleBase(title: String): String =
        title.replace(Regex("\\s*[-–(]?\\s*(copy|kopi|notes|note|draft|v\\d+|\\d+)\\s*\\)?\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()

    /**
     * Exact-title (case-insensitive) page lookup used by the duplicate guard:
     * returns (id, url) when a page with exactly this title exists among the
     * integration's accessible pages, so notion_write_page can refuse instead
     * of creating a near-duplicate. Unlike [resolveTitleToId] it does NOT fall
     * back to the top hit — an empty answer means "no exact match".
     */
    private fun findExistingByTitle(project: Project, title: String): Pair<String, String>? {
        val resp = post(project, "/search", buildJsonObject {
            put("query", title)
            put("filter", buildJsonObject {
                put("property", "object")
                put("value", "page")
            })
        })
        val hits = resp.getOrNull()?.get("results")?.jsonArray ?: return null
        for (hit in hits) {
            val o = runCatching { hit.jsonObject }.getOrNull() ?: continue
            if (!pageTitle(o).equals(title, ignoreCase = true)) continue
            val id = o["id"]?.jsonPrimitive?.content?.let(WriteRoot::normalizeNotionId) ?: continue
            return id to (o["url"]?.jsonPrimitive?.content ?: "")
        }
        return null
    }

    // ------------------------------------------------------------ blocks → markdown

    /** Fetches all child blocks (with pagination) and flattens them to markdown. */
    private fun getBlocks(project: Project, blockId: String, depth: Int = 0, withIds: Boolean = false): String? {
        if (depth > 4) return null
        val sb = StringBuilder()
        var cursor: String? = null
        do {
            val resp = get(project, "/blocks/$blockId/children" + (cursor?.let { "?start_cursor=$it" } ?: ""))
                .getOrElse { return sb.toString().ifBlank { null } }
            for (b in resp["results"]?.jsonArray ?: JsonArray(emptyList())) {
                val o = runCatching { b.jsonObject }.getOrNull() ?: continue
                appendBlock(project, o, sb, depth, withIds)
            }
            cursor = resp["next_cursor"]?.jsonPrimitive?.content?.takeIf { it != "null" }
            // Safety valve against pathological pagination loops.
            if (sb.length > 60_000) break
        } while (resp["has_more"]?.jsonPrimitive?.content == "true")
        return sb.toString().ifBlank { null }
    }

    private fun appendBlock(project: Project, block: JsonObject, sb: StringBuilder, depth: Int, withIds: Boolean) {
        val type = block["type"]?.jsonPrimitive?.content.orEmpty()
        val id = block["id"]?.jsonPrimitive?.content.orEmpty()
        val texts = block[type]?.jsonObject?.get("rich_text")?.jsonArray
            ?.joinToString("") { (it as? JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty() }
            .orEmpty()
        val indent = "  ".repeat(depth)
        if (withIds && (type in EDITABLE_BLOCKS || type in FILE_BLOCKS)) {
            sb.append(indent).append("(block_id: ").append(id).append(") ")
        }
        when (type) {
            "paragraph" -> sb.append(indent).append(texts).append("\n\n")
            "heading_1" -> sb.append("# ").append(texts).append("\n\n")
            "heading_2" -> sb.append("## ").append(texts).append("\n\n")
            "heading_3" -> sb.append("### ").append(texts).append("\n\n")
            "bulleted_list_item" -> sb.append(indent).append("- ").append(texts).append("\n")
            "numbered_list_item" -> sb.append(indent).append("1. ").append(texts).append("\n")
            "quote" -> sb.append(indent).append("> ").append(texts).append("\n\n")
            "code" -> sb.append("```\n").append(texts).append("\n```\n\n")
            "child_page" -> sb.append(indent).append("[")
                .append(block["child_page"]?.jsonObject?.get("title")?.jsonPrimitive?.content ?: "child page")
                .append("]  (sub-page, read with notion_read_page if needed)\n")
            "child_database" -> sb.append(indent).append("[database]  (content not flattened)\n")
            "image", "video", "file", "pdf" -> {
                val obj = block[type]?.jsonObject
                val url = obj?.get("file")?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: obj?.get("external")?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""
                // File attachments carry a name; images may have a caption —
                // both help the model tell multiple attachments apart.
                val name = obj?.get("name")?.jsonPrimitive?.content
                    ?: obj?.get("caption")?.jsonArray?.joinToString("") {
                        (it as? JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty()
                    }.orEmpty()
                sb.append(indent).append("[").append(type).append("]")
                if (name.isNotBlank()) sb.append(" ").append(name)
                sb.append(" ").append(url)
                sb.append("  (deletable with notion_update_block archive=true)")
                sb.append("\n")
            }
            "toggle" -> sb.append(indent).append("▸ ").append(texts).append("\n")
            else -> if (texts.isNotBlank()) sb.append(indent).append(texts).append("\n\n")
        }
        // Recurse into nested children declared inside the block.
        if (block["has_children"]?.jsonPrimitive?.content == "true" && type !in setOf("child_page", "child_database")) {
            getBlocks(project, block["id"]?.jsonPrimitive?.content ?: return, depth + 1, withIds)
                ?.let { sb.append(indent).append(it) }
        }
    }

    /** Block types Notion allows editing via PATCH /v1/blocks/{id}. */
    private val EDITABLE_BLOCKS = setOf(
        "paragraph", "heading_1", "heading_2", "heading_3",
        "bulleted_list_item", "numbered_list_item", "quote", "toggle", "callout",
    )

    /**
     * Attachment-type blocks. Their ids are NOT surfaced for notion_update_block
     * (Notion cannot change a file block's content in place) but they ARE
     * needed for deletion (PATCH archived=true works on any block) and
     * notion_move_file — so include them in the block-id markers even though
     * they are not "editable".
     */
    private val FILE_BLOCKS = setOf("image", "video", "file", "pdf")

    // ------------------------------------------------------------ databases

    /**
     * Queries a database (the thing the reader currently shows as
     * "[database] (content not flattened)"). Optional filter/sort are passed
     * through as raw JSON strings to keep the tool schema string-only.
     */
    fun queryDatabase(project: Project, args: JsonObject): String {
        val db = VfsTools.str(args, "database")?.trim()
            ?: throw IllegalArgumentException("database required (database id, page id or title)")
        val dbId = if (looksLikeUuid(db)) db else resolveTitleToId(project, db)
            ?: return "Error: no Notion page/database titled '$db'. Use notion_search to find the exact title."
        val filter = VfsTools.str(args, "filter")?.trim()
        val sorts = VfsTools.str(args, "sorts")?.trim()
        val limit = VfsTools.str(args, "limit")?.trim()?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val payload = buildJsonObject {
            put("page_size", limit)
            filter?.let {
                val f = runCatching { json.parseToJsonElement(it) }.getOrNull()
                    ?: return "Error: filter is not valid JSON: $it"
                put("filter", f)
            }
            sorts?.let {
                val s = runCatching { json.parseToJsonElement(it) }.getOrNull()
                    ?: return "Error: sorts is not valid JSON: $it"
                put("sorts", s)
            }
        }
        return post(project, "/databases/$dbId/query", payload).fold(
            onSuccess = { body ->
                val rows = body["results"]?.jsonArray
                if (rows.isNullOrEmpty()) return@fold "No rows in database '$db'."
                buildString {
                    append("Database '").append(db).append("' rows (").append(rows.size).append("):\n")
                    for (row in rows) {
                        val o = runCatching { row.jsonObject }.getOrNull() ?: continue
                        append("\n- ").append(pageTitle(o)).append("  (id: ")
                            .append(o["id"]?.jsonPrimitive?.content.orEmpty()).append(")")
                        val url = o["url"]?.jsonPrimitive?.content
                        if (!url.isNullOrBlank()) append("  ").append(url)
                    }
                    append("\n\nUse notion_read_page with a row id to read its content.")
                }.let { truncate(it) }
            },
            onFailure = { e ->
                val hint = (e as? ApiException)?.code?.let { code ->
                    when (code) {
                        "validation_error" -> {
                            // Blind-guessing a property name is the most common
                            // failure here: auto-fetch the schema so the agent
                            // self-corrects in one round trip.
                            val schema = runCatching { describeSchema(project, dbId) }.getOrNull()
                            " (property name or filter shape may be wrong)" +
                                (schema?.let { "\n\n$schema" } ?: "")
                        }
                        else -> " (the id may be a page, not a database — databases appear in " +
                            "notion_search results only when they are shared with the integration)"
                    }
                }
                "Error: notion_query_database failed — ${describe(e)}$hint"
            },
        )
    }

    /**
     * Describes a database's schema (property names, types and select/status/
     * multi-select options) via GET /v1/databases/{id}. Read-only, both modes.
     * Call this before writing a filter/sorts for notion_query_database instead
     * of guessing property names.
     */
    fun describeDatabase(project: Project, args: JsonObject): String {
        val db = VfsTools.str(args, "database")?.trim()
            ?: throw IllegalArgumentException("database required (database id, page id or title)")
        val dbId = if (looksLikeUuid(db)) db else resolveTitleToId(project, db)
            ?: return "Error: no Notion page/database titled '$db'. Use notion_search to find the exact title."

        val schema = runCatching { describeSchema(project, dbId) }
        return schema.fold(
            onSuccess = { it },
            onFailure = { e ->
                "Error: notion_describe_database failed — ${describe(e)}" +
                    (if ((e as? ApiException)?.code == "validation_error")
                        " (the id may be a page, not a database)" else "")
            },
        )
    }

    /** Fetches GET /v1/databases/{id} and formats the properties schema. */
    private fun describeSchema(project: Project, dbId: String): String {
        val meta = get(project, "/databases/$dbId")
            .getOrElse { throw it }
        val title = pageTitle(meta)
        val props = meta["properties"] as? JsonObject
        if (props.isNullOrEmpty()) return "Database '$title' (id $dbId) has no properties."
        val sb = StringBuilder()
        sb.append("Database '").append(title).append("' schema (id ").append(dbId).append("):\n")
        for ((name, v) in props) {
            val vo = v as? JsonObject ?: continue
            val type = vo["type"]?.jsonPrimitive?.content ?: "?"
            val opts = when (type) {
                "select" -> vo["select"]?.let { options(it) }
                "multi_select" -> vo["multi_select"]?.let { options(it) }
                "status" -> {
                    val groups = vo["status"] as? JsonObject
                    (groups?.get("options") as? JsonArray)?.let { options(it) }
                }
                else -> null
            }
            sb.append("\n- ").append(name).append(": ").append(type)
            if (!opts.isNullOrEmpty()) sb.append(" (options: ").append(opts).append(")")
        }
        sb.append("\n\nUse these exact property names in notion_query_database filter/sorts.")
        return truncate(sb.toString())
    }

    /** Formats a select/multi_select/status "options" JsonArray as option names. */
    private fun options(container: kotlinx.serialization.json.JsonElement): String =
        (container as? JsonObject)?.get("options")?.let { options(it) }
            ?: (container as? JsonArray)?.joinToString(", ") { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: "?"
            }
            ?: ""

    // ------------------------------------------------------------ markdown → blocks

    /** Converts plain markdown-ish content into Notion paragraph/list/code blocks. */
    private fun String.toBlocks(): JsonArray {
        val blocks = mutableListOf<JsonObject>()
        val lines = lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim()
                    val code = buildString {
                        i++
                        while (i < lines.size && !lines[i].trim().startsWith("```")) {
                            append(lines[i]).append('\n'); i++
                        }
                        i++ // closing fence
                    }
                    blocks += block("code", buildJsonObject {
                        put("rich_text", richText(code.removeSuffix("\n")))
                        put("language", lang.ifBlank { "plain text" })
                    })
                }
                trimmed.startsWith("# ") -> {
                    blocks += block("heading_1", buildJsonObject { put("rich_text", richText(trimmed.removePrefix("# ").trim())) }); i++
                }
                trimmed.startsWith("## ") -> {
                    blocks += block("heading_2", buildJsonObject { put("rich_text", richText(trimmed.removePrefix("## ").trim())) }); i++
                }
                trimmed.startsWith("### ") -> {
                    blocks += block("heading_3", buildJsonObject { put("rich_text", richText(trimmed.removePrefix("### ").trim())) }); i++
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    blocks += block("bulleted_list_item", buildJsonObject { put("rich_text", richText(trimmed.substring(2).trim())) }); i++
                }
                trimmed.isBlank() -> i++ // skip blank lines between blocks
                else -> {
                    blocks += block("paragraph", buildJsonObject { put("rich_text", richText(trimmed)) }); i++
                }
            }
        }
        if (blocks.isEmpty()) blocks += block("paragraph", buildJsonObject { put("rich_text", richText("")) })
        // Notion allows at most 100 children per request.
        return JsonArray(blocks.take(100))
    }

    private fun block(type: String, body: JsonObject): JsonObject = buildJsonObject {
        put("object", "block")
        put("type", type)
        put(type, body)
    }

    private fun richText(text: String): JsonArray = JsonArray(listOf(buildJsonObject {
        put("type", "text")
        put("text", buildJsonObject { put("content", text.take(2000)) })
    }))

    // ------------------------------------------------------- connection test

    /** Used by the Configure Integrations dialog (Notion tab): verifies the token. */
    fun testConnection(token: String): String {
        if (token.isBlank()) return "⚠ Enter an integration token (Notion: Settings → Connections " +
            "→ new internal integration)."
        return request("GET", "$API/users/me", token, null).fold(
            onSuccess = { body ->
                val name = body["name"]?.jsonPrimitive?.content ?: "integration"
                val bot = body["bot"]?.jsonObject?.get("owner") != null
                "✓ Connected as integration \"$name\"." +
                    (if (bot) "" else " ⚠ This token does not look like an internal integration token.")
            },
            onFailure = { e ->
                val code = (e as? ApiException)?.code
                when (code) {
                    "unauthorized" -> "✗ Token rejected (401). Check the internal-integration " +
                        "token value (ntn_… / secret_…) and that the integration is not revoked " +
                        "(Notion: Settings → Connections)."
                    else -> "✗ ${describe(e)}"
                }
            },
        )
    }

    // ------------------------------------------------------------------ HTTP

    private class ApiException(val code: String?, message: String) : RuntimeException(message)

    private fun get(project: Project, path: String) =
        request("GET", API + path, token(project), null)

    /** Package-visible GET for [WriteRoot]'s ancestry walk / verification. */
    internal fun getForRootCheck(project: Project, path: String) = get(project, path)

    private fun post(project: Project, path: String, payload: JsonObject) =
        request("POST", API + path, token(project), payload.toString().toRequestBody("application/json".toMediaType()))

    private fun patch(project: Project, path: String, payload: JsonObject) =
        request("PATCH", API + path, token(project), payload.toString().toRequestBody("application/json".toMediaType()))

    private fun delete(project: Project, path: String) =
        request("DELETE", API + path, token(project), null)

    private fun token(project: Project): String {
        val t = NotionState.getInstance(project).token(project)
        if (t.isNullOrBlank()) throw ApiException(null, "Notion is not configured for this project")
        return t
    }

    /** Executes a request with a single 429 retry honouring Retry-After (3 req/s limit). */
    private fun request(method: String, url: String, token: String, body: okhttp3.RequestBody?): Result<JsonObject> {
        fun exec(): Pair<Int, String?> {
            val req = Request.Builder().url(url).method(method, body)
                .header("Authorization", "Bearer $token")
                .header("Notion-Version", NOTION_VERSION)
                .build()
            client.newCall(req).execute().use { resp ->
                return resp.code to resp.body?.string()
            }
        }
        var (code, text) = exec()
        if (code == 429) {
            // Notion is rate-limited to ~3 req/s; retry once after the hinted delay.
            val retryAfter = 1_000L
            Thread.sleep(retryAfter)
            val (code2, text2) = exec()
            code = code2
            text = text2
        }
        val parsed = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (code !in 200..299) {
            val errCode = parsed?.get("code")?.jsonPrimitive?.content
            val msg = parsed?.get("message")?.jsonPrimitive?.content ?: text?.take(300) ?: "(empty body)"
            return Result.failure(ApiException(errCode, "HTTP $code: $msg"))
        }
        return parsed?.let { Result.success(it) }
            ?: Result.failure(ApiException(null, "Malformed JSON response"))
    }

    // ------------------------------------------------------------ diagnostics

    internal fun describePublic(e: Throwable): String = describe(e)

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    /**
     * Elvis (?:) does NOT catch throw-on-type-mismatch for kotlinx accessors:
     * `body["results"]?.jsonArray` throws when the field is a JsonObject, which
     * escapes the tool and surfaces as "Element … is not a JsonArray". This
     * safe accessor returns an empty list instead, so malformed/unexpected
     * responses degrade to "no results" rather than an opaque error.
     */
    private fun JsonObject.safeArray(key: String): List<JsonObject> =
        runCatching {
            (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
        }.getOrNull() ?: emptyList()

    private fun truncate(s: String) = if (s.length > MAX_OUTPUT_CHARS) {
        s.take(MAX_OUTPUT_CHARS) + "\n\n[output truncated at $MAX_OUTPUT_CHARS characters]"
    } else s

    private fun looksLikeUuid(s: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$").matches(s)
}
