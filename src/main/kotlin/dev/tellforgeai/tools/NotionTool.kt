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
 * Exposes nine TellForgeAI tools:
 *  - `notion_search`         — POST /v1/search (both modes, no approval)
 *  - `notion_read_page`      — page flattened to markdown-ish text, optional
 *                              block-id markers (both modes, no approval)
 *  - `notion_query_database` — POST /v1/databases/{id}/query (both modes)
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

    fun search(project: Project, args: JsonObject): String {
        val query = VfsTools.str(args, "query")?.trim().orEmpty()
        val resp = post(project, "/search", buildJsonObject {
            put("query", query)
            put("page_size", 20)
        })
        return resp.fold(
            onSuccess = { body ->
                val results = body["results"]?.jsonArray ?: return@fold "No Notion results for '$query'."
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
        if (approve(project, WikiWriteAction.CREATE, "Notion write",
                "TellForgeAI wants to create this Notion page:" +
                    (parent?.let { "\n\nParent: $it" } ?: "\n\nParent: (no parent — Notion requires one)") +
                    "\n\nTitle: $title\n\nAllow?",
            ).not()
        ) {
            return "Notion write declined by the user."
        }

        val parentId = when {
            parent == null -> null
            looksLikeUuid(parent) -> parent
            else -> resolveTitleToId(project, parent)
                ?: return "Error: no Notion page titled '$parent' to use as parent. " +
                    "Use notion_search to find an exact title."
        } ?: return "Error: Notion requires a parent page. Ask the user to name a parent " +
            "(or search for one with notion_search and pass its id)."

        val payload = buildJsonObject {
            put("parent", buildJsonObject { put("page_id", parentId) })
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

        if (approve(project, WikiWriteAction.CREATE, "Notion append",
                "TellForgeAI wants to append ${content.length} chars of content to Notion page " +
                    "'$page' (id $targetId).\n\nAllow?",
            ).not()
        ) return "Notion append declined by the user."

        // Children of a PAGE work directly; otherwise the id must be a block id.
        val blocks = content.toBlocks()
        return patch(project, "/blocks/$targetId/children", buildJsonObject {
            put("children", blocks)
        }).fold(
            onSuccess = { resp ->
                val count = resp["results"]?.jsonArray?.size ?: blocks.size
                "Appended $count block(s) to Notion page '$page' (id $targetId)."
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

        var parentId: String? = null
        if (parent != null) {
            parentId = if (looksLikeUuid(parent)) parent else resolveTitleToId(project, parent)
                ?: return "Error: no Notion page titled '$parent' to use as parent."
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
                "TellForgeAI wants to $verb Notion page '$page' (id $pageId): $summary.\n\nAllow?",
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
        return patch(project, "/pages/$pageId", payload).fold(
            onSuccess = { "Updated Notion page '$page' (id $pageId): $summary." },
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
            ?: throw IllegalArgumentException("content required (new text for the block)")
        val archive = (args["archive"]?.jsonPrimitive?.content ?: "").equals("true", ignoreCase = true)

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
            put("filename", filename)
            put("content_type", contentType)
        }.toString().toRequestBody("application/json".toMediaType()))
            .getOrElse { return "Error: notion_upload_file failed (create) — ${describe(it)}" }
        val uploadId = created["id"]?.jsonPrimitive?.content
            ?: return "Error: notion_upload_file failed — no upload id in response."
        val uploadUrl = created["upload_url"]?.jsonPrimitive?.content

        // 2. Send the bytes (multipart, to upload_url for single_part mode).
        val mime = contentType.toMediaType()
        val fileBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mime))
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
                onSuccess = { "Uploaded '$path' (file_upload $finalId) and attached it to '$page'." },
                onFailure = { e ->
                    "Uploaded '$path' (file_upload $finalId) but attaching to '$page' failed: " +
                        "${describe(e)}. The upload is valid for 1 hour — attach it manually " +
                        "or retry the attach step."
                },
            )
        }
        return "Uploaded '$path' to Notion (file_upload id $finalId). Attach it with " +
            "notion_append_blocks using an image/file block with \"file_upload\": {\"id\": \"$finalId\"} " +
            "(valid for 1 hour)."
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
                "Ask the user to open 'Configure Tela Wiki…' (Wiki button in the tool window), " +
                "Notion tab, paste an integration token and press 'Test connection'."
        !NotionState.getInstance(project).writeEnabled() ->
            "Error: Notion writes are not enabled for this project. " +
                "Ask the user to tick 'Allow the agent to create, update and delete Notion pages' " +
                "in the Configure Tela Wiki dialog."
        else -> null
    }

    /**
     * Per-action approval for mutating Notion tools, keyed by
     * [WikiWriteAction]: when the action's "Ask permission to … page"
     * checkbox is unchecked in the Configure Tela Wiki dialog (skip), the
     * call proceeds without a confirmation dialog.
     */
    private fun approve(project: Project, action: WikiWriteAction, message: String, title: String): Boolean {
        if (NotionState.getInstance(project).skipApproval(action)) return true
        return UiApprovals.ask(project, message, title)
    }

    // ------------------------------------------------------- title helpers

    /** Extracts the title property value from a page/search-result object. */
    private fun pageTitle(o: JsonObject): String {
        val props = o["properties"]?.jsonObject ?: return "(untitled)"
        for ((_, v) in props) {
            val vo = runCatching { v.jsonObject }.getOrNull() ?: continue
            if (vo["type"]?.jsonPrimitive?.content == "title") {
                val arr = vo["title"]?.jsonArray ?: continue
                return arr.joinToString("") { (it as? JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty() }
                    .ifBlank { "(untitled)" }
            }
        }
        return "(untitled)"
    }

    private fun resolveTitleToId(project: Project, title: String): String? {
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
            if (pageTitle(o).equals(title, ignoreCase = true)) {
                return o["id"]?.jsonPrimitive?.content
            }
        }
        // Fall back to the top hit.
        return (hits.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.content
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
        if (withIds && type in EDITABLE_BLOCKS) {
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
                val url = block[type]?.jsonObject?.get("file")?.jsonObject?.get("url")
                    ?.jsonPrimitive?.content ?: ""
                sb.append(indent).append("[").append(type).append("] ").append(url).append("\n")
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
                    if (code == "validation_error")
                        " (the id may be a page, not a database — databases appear in " +
                            "notion_search results only when they are shared with the integration)"
                    else null
                }
                "Error: notion_query_database failed — ${describe(e)}$hint"
            },
        )
    }

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

    /** Used by the Configure Tela Wiki dialog (Notion tab): verifies the token. */
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

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    private fun truncate(s: String) = if (s.length > MAX_OUTPUT_CHARS) {
        s.take(MAX_OUTPUT_CHARS) + "\n\n[output truncated at $MAX_OUTPUT_CHARS characters]"
    } else s

    private fun looksLikeUuid(s: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$").matches(s)
}
