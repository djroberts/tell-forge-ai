package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The tools the model may call (OpenAI function-calling format) and the
 * dispatcher that executes them on a background thread.
 */
object ToolRegistry {

    /**
     * Tool specs sent to the model. [planMode] restricts the command tool.
     * [project] (nullable for tests) gates the wiki/Notion tools: their specs
     * are only offered when the corresponding per-project integration is
     * enabled in the Configure Tela Wiki dialog.
     */
    fun specs(project: Project?, planMode: Boolean): List<Map<String, Any>> {
        val wikiEnabled = project != null &&
            dev.tellforgeai.settings.TellForgeAIWikiState.getInstance(project).enabled() &&
            dev.tellforgeai.settings.TellForgeAIWikiState.getInstance(project).configured(project)
        val notionEnabled = project != null &&
            dev.tellforgeai.settings.NotionState.getInstance(project).enabled() &&
            dev.tellforgeai.settings.NotionState.getInstance(project).configured(project)
        return listOfNotNull(
        spec(
            "list_files",
            "List files and folders under a path relative to the project root. " +
                "Use this to explore the project structure.",
            jsonObjectProps(
                "path" to "Relative directory path; empty or '.' for the project root.",
                "depth" to "How deep to recurse (default 2, max 6).",
            ),
            required = listOf("path"),
        ),
        spec(
            "read_file",
            "Read a text file relative to the project root. Returns numbered lines.",
            jsonObjectProps(
                "path" to "Relative file path.",
                "startLine" to "Optional 1-based first line to read.",
                "endLine" to "Optional 1-based last line to read.",
            ),
            required = listOf("path"),
        ),
        spec(
            "search_files",
            "Search file NAMES and file CONTENTS (case-insensitive substring match) under a " +
                "directory relative to the project root. Returns matching paths with line " +
                "excerpts. ALWAYS use this instead of shell commands (grep, find, rg, ls | grep) " +
                "for searching — it is not subject to command restrictions and needs no approval.\n" +
                "Usage tips:\n" +
                "- For multiple search terms, make one search_files call PER TERM (you can issue " +
                "them in parallel), or search a broader common substring first.\n" +
                "- The query is a plain substring, not a regex — use short distinctive terms.",
            jsonObjectProps(
                "query" to "Text to search for (case-insensitive substring, not a regex).",
                "path" to "Optional relative directory to limit the search to.",
            ),
            required = listOf("query"),
        ),
        spec(
            "run_command",
            "Run a shell command in the project root and return its output. " +
                "PREFERRED ALTERNATIVES: use search_files for searching, read_file for reading " +
                "files, list_files for structure — run_command is a last resort when no " +
                "dedicated tool fits (e.g. git status, npm --version).\n" +
                (if (planMode) "Foretell mode: only read-only commands are allowed (ls, cat, find, " +
                    "git status/log/diff, node --version, ...). " else "") +
                "MUTATIONS ARE ALLOWED in Forge mode when the user asks for them: " +
                "git add/commit/push, npm install, gradle build, git config and similar " +
                "state-changing commands run fine as single commands. " +
                "NEVER use pipes (|), redirects (>, >>, 2>&1), command substitution (\$(...), " +
                "backticks) or compound commands (;, &&, ||) — these are always refused even for " +
                "read-only commands. You do NOT need '2>&1': BOTH stdout and stderr are captured " +
                "automatically and returned together (stderr is labeled '[stderr]'), so plain " +
                "output redirection only discards information.\n" +
                "Run a single simple command instead; if you need grep-like output " +
                "filtering, use search_files or read_file rather than 'grep ... | head'.\n" +
                "Do NOT claim a mutation is impossible without first TRYING it: if the user " +
                "is confident a command should work, run it once and report the real error " +
                "instead of asserting tool restrictions from memory.\n" +
                "Destructive commands (rm, sudo, force-push) still need explicit user request.",
            jsonObjectProps("command" to "The shell command to execute. A single plain command — " +
                "no pipes, redirects (including 2>&1), or chaining. stdout and stderr are both " +
                "returned automatically."),
            required = listOf("command"),
        ),
        spec(
            "web_search",
            "Search the web for documentation, error messages, library APIs, or current " +
                "information beyond your training data. Prefer official docs in the results. " +
                "This is read-only and needs no user approval. If search fails or returns " +
                "nothing, answer from your own knowledge and say so — do not retry repeatedly.",
            jsonObjectProps(
                "query" to "Search query (keep it short and specific).",
            ),
            required = listOf("query"),
        ),
        if (wikiEnabled) spec(
            "wiki_search",
            "Keyword-search the project's tela wiki (configured via 'Configure Tela Wiki…' " +
                "in the tool window gear menu). Read-only, both modes, no approval. " +
                "If no wiki is configured the call fails — do not retry; say so and move on.",
            jsonObjectProps(
                "query" to "Search query; short and specific works best.",
            ),
            required = listOf("query"),
        ) else null,
        if (wikiEnabled) spec(
            "wiki_read_page",
            "Read one page from the project's tela wiki and return its markdown body. " +
                "Read-only, both modes, no approval.",
            jsonObjectProps(
                "page" to "Page title, path or id as returned by wiki_search.",
            ),
            required = listOf("page"),
        ) else null,
        if (wikiEnabled && !planMode) spec(
            "wiki_write_page",
            "Create a new page in the project's tela wiki, OR replace the body of an existing " +
                "page with the same title in the target space (confirmation per the user's " +
                "'Ask permission' settings). Forge mode only. Do not call this repeatedly for " +
                "the same title — prefer one well-formed page; to extend an existing page tell " +
                "the user and include the full new body in one call.",
            jsonObjectProps(
                "title" to "Page title.",
                "content" to "Page body in markdown.",
                "space" to "Optional space key; omit to use the default.",
                "parent" to "Optional parent page title/path.",
            ),
            required = listOf("title", "content"),
        ) else null,
        if (wikiEnabled && !planMode) spec(
            "wiki_delete_page",
            "Delete one page from the project's tela wiki, by page id or exact title from " +
                "wiki_search. Forge mode only, gated by the user's write opt-in and " +
                "'Ask permission to delete page' setting.",
            jsonObjectProps(
                "page" to "Page id or exact title.",
            ),
            required = listOf("page"),
        ) else null,
        // Notion tools, gated by the per-project Notion integration.
        if (notionEnabled) spec(
            "notion_search",
            "Keyword-search the user's Notion workspace for pages the integration can access " +
                "(configured via the Notion tab of 'Configure Tela Wiki…'). Read-only, both " +
                "modes, no approval. Returns page ids/titles; use notion_read_page to read one.",
            jsonObjectProps(
                "query" to "Search query; short and specific works best. Empty lists all accessible pages.",
            ),
            required = listOf("query"),
        ) else null,
        if (notionEnabled) spec(
            "notion_read_page",
            "Read one Notion page and return its content flattened to markdown-ish text " +
                "(sub-pages become links, databases are not flattened). Read-only, both modes, " +
                "no approval. Pass a page id or an exact title from notion_search.",
            jsonObjectProps(
                "page" to "Notion page id (UUID) or exact page title.",
            ),
            required = listOf("page"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_write_page",
            "Create a new Notion page under an existing parent page (markdown body flattened " +
                "to Notion paragraph/heading/list/code blocks). Forge mode only. Requires the " +
                "user to have enabled Notion writes in the 'Configure Tela Wiki…' dialog, and " +
                "each write asks for approval. Do not call this repeatedly for the same title.",
            jsonObjectProps(
                "title" to "New page title.",
                "content" to "Page body in markdown (headings, bullets, code fences; 100 blocks max).",
                "parent" to "Parent page id or exact title. Notion requires a parent page.",
            ),
            required = listOf("title", "content", "parent"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_append_blocks",
            "Append markdown content to an EXISTING Notion page (or block) instead of creating a " +
                "duplicate page — PREFER THIS over notion_write_page when the page already exists. " +
                "Forge mode only, requires Notion writes enabled, asks for approval per call.",
            jsonObjectProps(
                "page" to "Existing page (or block) id or exact page title.",
                "content" to "Markdown to append (headings, bullets, code fences; 100 blocks max).",
            ),
            required = listOf("page", "content"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_update_page",
            "Update a Notion page's properties: rename (title), move (parent) and/or archive. " +
                "Forge mode only, requires Notion writes enabled, asks for approval per call.",
            jsonObjectProps(
                "page" to "Page id or exact title.",
                "title" to "Optional new title.",
                "parent" to "Optional new parent page id or title (move).",
                "archive" to "Optional 'true' to archive (move to trash; Notion has no hard delete).",
            ),
            required = listOf("page"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_delete_page",
            "Archive (soft-delete) a Notion page — Notion's API has no hard delete; archived " +
                "pages are recoverable from Notion's trash. Forge mode only, asks for approval.",
            jsonObjectProps(
                "page" to "Page id or exact title to archive.",
            ),
            required = listOf("page"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_update_block",
            "Replace the text of one editable Notion block (paragraph, heading 1-3, list item, " +
                "quote, toggle, callout) in place, or archive it. The agent can fix a page " +
                "without rewriting it. Forge mode only, asks for approval.",
            jsonObjectProps(
                "blockId" to "Block id from notion_read_page (call it with include_block_ids=true; " +
                    "ids appear as (block_id: …)).",
                "content" to "New text for the block (leading '# '/'- ' hints the block type).",
                "blockType" to "Optional explicit block type (paragraph, heading_1, …).",
                "archive" to "Optional 'true' to archive the block (remove it).",
            ),
            required = listOf("blockId", "content"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_upload_file",
            "Upload a project file to Notion via the File Upload API (single part, ≤ 20 MB) and " +
                "optionally attach it to a page as an image/file block. Forge mode only, asks " +
                "for approval (shows the path). Uploads expire after 1 hour if not attached.",
            jsonObjectProps(
                "path" to "Project-relative file path to upload.",
                "page" to "Optional page id or title to attach the file to.",
                "caption" to "Optional caption for the attached block.",
            ),
            required = listOf("path"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_create_comment",
            "Comment on a Notion page or discussion. Needs the 'insert comments' capability on " +
                "the integration token. Forge mode only, asks for approval.",
            jsonObjectProps(
                "content" to "Comment text.",
                "page" to "Page id or title (required unless discussionId is given).",
                "discussionId" to "Optional existing discussion/thread id to reply in.",
            ),
            required = listOf("content"),
        ) else null,
        if (notionEnabled) spec(
            "notion_query_database",
            "Query a Notion database and list its rows (the reader cannot flatten databases). " +
                "Read-only, both modes, no approval. Optional filter/sorts are raw JSON " +
                "(https://developers.notion.com/reference/post-database-query-filter).",
            jsonObjectProps(
                "database" to "Database id, or id/title from notion_search results marked [database].",
                "filter" to "Optional Notion filter JSON object, e.g. {\"property\":\"Status\",\"select\":{\"equals\":\"Done\"}}.",
                "sorts" to "Optional Notion sorts JSON array, e.g. [{\"property\":\"Name\",\"direction\":\"ascending\"}].",
                "limit" to "Optional max rows (1-100, default 20).",
            ),
            required = listOf("database"),
        ) else null,
        if (!planMode) spec(
            "download_binary",
            "Download a binary file from an HTTPS URL into the project. This tool is available " +
                "only in Forge mode and always requires explicit user approval. Saves raw response " +
                "bytes without executing or unpacking them; maximum size is 50 MiB. HTTPS redirects " +
                "are followed only when they remain HTTPS.",
            jsonObjectProps(
                "url" to "HTTPS URL of the file to download.",
                "path" to "Destination file path relative to the project root.",
            ),
            required = listOf("url", "path"),
        ) else null,
        if (!planMode) spec(
            "write_file",
            "Create a new file or completely overwrite an existing one, applying the change " +
                "directly in the IDE editor (undoable). For targeted changes prefer edit_file.",
            jsonObjectProps(
                "path" to "Relative file path.",
                "content" to "The complete new file content.",
            ),
            required = listOf("path", "content"),
        ) else null,
        if (!planMode) spec(
            "edit_file",
            "Replace an exact string in an existing file, applying the change directly in the " +
                "IDE editor (undoable). Read the file first and copy oldString exactly, " +
                "including indentation and whitespace.",
            jsonObjectProps(
                "path" to "Relative file path.",
                "oldString" to "The exact existing text to replace.",
                "newString" to "The replacement text.",
                "replaceAll" to "Optional 'true' to replace every occurrence; default replaces one (must be unique).",
            ),
            required = listOf("path", "oldString", "newString"),
        ) else null,
        )
    }

    private fun spec(
        name: String,
        description: String,
        props: Map<String, String>,
        required: List<String>,
    ): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    props.forEach { (n, d) ->
                        put(n, buildJsonObject {
                            put("type", if (n == "depth" || n == "startLine" || n == "endLine") "integer" else "string")
                            put("description", d)
                        })
                    }
                })
                put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
            },
        ),
    )

    private fun jsonObjectProps(vararg pairs: Pair<String, String>) = pairs.toMap()

    /**
     * Executes a tool call on a background thread. Command and download approval
     * callbacks run before their respective operations; returning false declines.
     */
    fun execute(
        project: Project,
        name: String,
        argumentsJson: String,
        planMode: Boolean,
        onDownloadRequested: ((String) -> Boolean)? = null,
        onCommandRequested: (String) -> Boolean,
    ): String = try {
        val args: JsonObject = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else Json.parseToJsonElement(argumentsJson).jsonObject
        when (name) {
            "list_files" -> VfsTools.listFiles(project, args)
            "read_file" -> VfsTools.readFile(project, args)
            "search_files" -> VfsTools.searchFiles(project, args)
            "web_search" -> WebSearchTool.search(args)
            "wiki_search" -> if (!wikiAllowed(project)) notEnabled("wiki_search")
                else TelaWikiTool.search(project, args)
            "wiki_read_page" -> if (!wikiAllowed(project)) notEnabled("wiki_read_page")
                else TelaWikiTool.readPage(project, args)
            "wiki_write_page" -> when {
                planMode -> notAllowedInPlan(name)
                !wikiAllowed(project) -> notEnabled(name)
                else -> TelaWikiTool.writePage(project, args)
            }
            "wiki_delete_page" -> when {
                planMode -> notAllowedInPlan(name)
                !wikiAllowed(project) -> notEnabled(name)
                else -> TelaWikiTool.deletePage(project, args)
            }
            "notion_search" -> if (!notionAllowed(project)) notEnabled("notion_search")
                else NotionTool.search(project, args)
            "notion_read_page" -> if (!notionAllowed(project)) notEnabled("notion_read_page")
                else NotionTool.readPage(project, args)
            "notion_write_page" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.writePage(project, args)
            }
            "notion_append_blocks" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.appendBlocks(project, args)
            }
            "notion_update_page" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.updatePage(project, args)
            }
            "notion_delete_page" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.deletePage(project, args)
            }
            "notion_update_block" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.updateBlock(project, args)
            }
            "notion_upload_file" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.uploadFile(project, args)
            }
            "notion_create_comment" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.createComment(project, args)
            }
            "notion_query_database" -> if (!notionAllowed(project)) notEnabled("notion_query_database")
                else NotionTool.queryDatabase(project, args)
            "run_command" -> CommandTool.run(project, args, planMode, onCommandRequested)
            "download_binary" -> if (planMode) notAllowedInPlan(name)
                else DownloadBinaryTool.download(project, args, onDownloadRequested ?: { false })
            "write_file" -> if (planMode) notAllowedInPlan(name) else EditorTools.writeFile(project, args)
            "edit_file" -> if (planMode) notAllowedInPlan(name) else EditorTools.editFile(project, args)
            else -> "Error: unknown tool '$name'"
        }
    } catch (e: Exception) {
        "Error executing $name: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun notAllowedInPlan(name: String): String =
        "Error: '$name' is not available in Foretell mode. Switch to Forge mode to modify files."

    /** Stale-spec guard: the wiki tools must be enabled AND configured for this project. */
    private fun wikiAllowed(project: Project): Boolean {
        val s = dev.tellforgeai.settings.TellForgeAIWikiState.getInstance(project)
        return s.enabled() && s.configured(project)
    }

    /** Stale-spec guard: the Notion tools must be enabled AND configured for this project. */
    private fun notionAllowed(project: Project): Boolean {
        val s = dev.tellforgeai.settings.NotionState.getInstance(project)
        return s.enabled() && s.configured(project)
    }

    private fun notEnabled(name: String): String =
        "Error: '$name' is disabled for this project. The user can enable it in the " +
            "'Configure Tela Wiki…' dialog (Wiki button in the tool window)."
}