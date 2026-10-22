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
     * enabled in the Configure Integrations dialog.
     */
    fun specs(project: Project?, planMode: Boolean): List<Map<String, Any>> {
        val wikiEnabled = project != null &&
            dev.tellforgeai.settings.TellForgeAIWikiState.getInstance(project).enabled() &&
            dev.tellforgeai.settings.TellForgeAIWikiState.getInstance(project).configured(project)
        val notionEnabled = project != null &&
            dev.tellforgeai.settings.NotionState.getInstance(project).enabled() &&
            dev.tellforgeai.settings.NotionState.getInstance(project).configured(project)
        val instructionsEnabled = project != null &&
            dev.tellforgeai.settings.InstructionsRepoState.getInstance(project).enabled()
        val instructionsWriteEnabled = project != null &&
            dev.tellforgeai.settings.InstructionsRepoState.getInstance(project).writeEnabled()
        val sentryEnabled = project != null &&
            dev.tellforgeai.settings.SentryState.getInstance(project).enabled() &&
            dev.tellforgeai.settings.SentryState.getInstance(project).configured(project)
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
                "NEVER use redirects (>, >>, 2>&1), command substitution (\$(...), backticks) " +
                "or compound commands (;, &&, ||) — these are always refused. " +
                "In Foretell mode simple pipelines into pure readers ARE allowed: " +
                "grep -n \"foo\" file | head -20, git log --oneline | wc -l, cat f | sort. " +
                "Allowed tail commands: head, tail, wc, sort, uniq, cut, column, tac, rev. " +
                "No awk/sed/sed -i. You do NOT need '2>&1': BOTH stdout and stderr are " +
                "captured and returned together (stderr is labeled '[stderr]'), so plain " +
                "output redirection only discards information.\n" +
                "Run a single simple command instead; if you need grep-like output " +
                "filtering, use search_files or read_file rather than 'grep ... | head'.\n" +
                "Long-running commands (builds, installs) may pass a per-call timeout in " +
                "seconds (max 300, 5 minutes); the default is the global setting.\n" +
                "Do NOT claim a mutation is impossible without first TRYING it: if the user " +
                "is confident a command should work, run it once and report the real error " +
                "instead of asserting tool restrictions from memory.\n" +
                "Destructive commands (rm, sudo, force-push) still need explicit user request.",
            jsonObjectProps("command" to "The shell command to execute. A single plain command — " +
                "no redirects (including 2>&1) or chaining; pipes into pure readers " +
                "(head/tail/wc/sort/uniq/cut) are permitted in read-only mode. stdout and " +
                "stderr are both returned automatically.",
                "timeout" to ("Optional per-command timeout in seconds for long-running " +
                    "commands (builds, installs). Default is the global setting; hard cap 300 " +
                    "(5 minutes), higher values are clamped down.")),
            required = listOf("command"),
        ),
        spec(
            "web_search",
            "Search the web for documentation, error messages, library APIs, or current " +
                "information beyond your training data. Prefer official docs in the results. " +
                "This is read-only and needs no user approval. The result's Provider: line " +
                "says which backend served the query. If the results are empty, an error, " +
                "or clearly don't answer the query, you may re-call this tool with " +
                "'provider' set to the other backend (fallback); otherwise answer from " +
                "your own knowledge and say so — do not retry repeatedly.",
            jsonObjectProps(
                "query" to "Search query (keep it short and specific).",
                "provider" to ("Optional backend: 'brave' or 'duckduckgo'. Omit for the " +
                    "configured primary; pass the OTHER provider when results were " +
                    "unsatisfactory (LLM-initiated fallback)."),
            ),
            required = listOf("query"),
        ),
        if (wikiEnabled) spec(
            "wiki_search",
            "Keyword-search the project's tela wiki (configured via 'Configure Integrations…', " +
                "Tela wiki tab). Read-only, both modes, no approval. " +
                "If no wiki is configured the call fails — do not retry; say so and move on. " +
                "Search this before any wiki page creation to rule out existing duplicates.",
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
                "'Ask permission' settings). When the user configured a write root, creates are " +
                "pinned as children of that root and updates outside its subtree are refused. " +
                "Forge mode only. Verify the exact title with wiki_search first; a call with an " +
                "existing title REPLACES that page's body in one shot — that is the correct " +
                "way to update a page. Do not spin up variant titles like 'Foo', 'Foo 2', " +
                "'Foo Notes' — keep one page per topic; to extend an existing page include " +
                "the full new body in one call.",
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
        if (instructionsEnabled) spec(
            "tellforge_instructions_read",
            "Read the user's TellForge instructions (raw, with original section " +
                "markers). Read-only, both modes, no approval. Call this before " +
                "editing so you see original section markers.",
            jsonObjectProps(),
            required = listOf(),
        ) else null,
        if (instructionsEnabled && !planMode) spec(
            "tellforge_instructions_write",
            "Edit the user's TellForge instructions: supply the COMPLETE new " +
                "instructions text in [content] — it replaces the instructions " +
                "wholesale. When the user says 'update the TellForge instructions', " +
                "use these tellforge_instructions_* tools. The instructions live OUTSIDE " +
                "the project — never create or edit instruction files (AGENTS.md, " +
                "INSTRUCTIONS.md, CLAUDE.md, etc.) in the user's project. The API " +
                "shows the user a diff and, after approval, saves it. Forge mode only " +
                "and requires the user's write opt-in — this never touches project " +
                "files. Read the instructions with tellforge_instructions_read first; " +
                "keep edits focused. A commit message is optional.",
            jsonObjectProps(
                "content" to "The complete new instructions text.",
                "message" to "Optional commit message (Conventional Commit style).",
            ),
            required = listOf("content"),
        ) else null,
        if (notionEnabled) spec(
            "notion_search",
            "Keyword-search the user's Notion workspace for pages the integration can access " +
                "(configured via the Notion tab of 'Configure Integrations…'). Read-only, both " +
                "modes, no approval. Returns page ids/titles; use notion_read_page to read one. " +
                "To write, PREFER updating an existing page (notion_append_blocks / " +
                "notion_update_block) over creating a new one.",
            jsonObjectProps(
                "query" to "Search query; short and specific works best. Empty lists all accessible pages. " +
                    "Search before any page creation to rule out existing duplicates.",
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
                "to Notion paragraph/heading/list/code blocks). When the user configured a " +
                "write root, the parent is pinned to that root (a database root makes the " +
                "page a row) and parents outside its subtree are refused. Forge mode only. " +
                "Requires the user to have enabled Notion writes, and each write asks for " +
                "approval. BEFORE creating: run notion_search on the title; if a page with " +
                "the same or a similar title already exists, update it instead " +
                "(notion_append_blocks / notion_update_block) — creating near-duplicate " +
                "pages (e.g. 'Foo', 'Foo 2', 'Foo Notes') scatters the user's workspace. " +
                "Only create when the content truly does not fit any existing page.",
            jsonObjectProps(
                "title" to "New page title. Must not duplicate an existing page — check with notion_search first.",
                "content" to "Page body in markdown (headings, bullets, code fences; 100 blocks max).",
                "parent" to "Parent page id or exact title. Notion requires a parent page.",
            ),
            required = listOf("title", "content", "parent"),
        ) else null,
        if (notionEnabled && !planMode) spec(
            "notion_append_blocks",
            "PREFERRED WRITE TOOL: append markdown content to an EXISTING Notion page (or " +
                "block) instead of creating a duplicate page — use this, or notion_update_block " +
                "for surgical fixes, whenever the target page already exists. Extending a page " +
                "in place is always better than a sibling copy. Forge mode only, requires " +
                "Notion writes enabled, asks for approval per call.",
            jsonObjectProps(
                "page" to "Existing page (or block) id or exact page title. Find it with notion_search / notion_read_page.",
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
                "quote, toggle, callout) in place, or archive it. File/image/video/pdf attachment " +
                "blocks cannot have their text replaced, but they CAN be deleted with " +
                "archive=true — attachment block ids are marked (block_id: …) in " +
                "notion_read_page output next to [file]/[image] lines. The agent can fix a page " +
                "or remove an unwanted attachment without rewriting it. Forge mode only, asks " +
                "for approval.",
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
            "notion_move_file",
            "Move a file attachment or image BLOCK between Notion pages. The Notion API cannot " +
                "re-parent file blocks, so this works by downloading the file to a temp location, " +
                "re-uploading it to the target page, then archiving the original block (a " +
                "copy-then-delete; the temp file is always deleted afterwards, and external-URL " +
                "attachments are moved by re-embedding the URL without any download). Forge mode " +
                "only, requires Notion writes enabled, asks for approval per call. Get the block " +
                "id from notion_read_page with include_block_ids=true.",
            jsonObjectProps(
                "block_id" to "Block id of the source file/image block (from notion_read_page with include_block_ids=true).",
                "page" to "Target page id or title.",
                "caption" to "Optional new caption for the moved block.",
            ),
            required = listOf("block_id", "page"),
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
                "(https://developers.notion.com/reference/post-database-query-filter). " +
                "Call notion_describe_database FIRST to get exact property names/types/options " +
                "instead of guessing.",
            jsonObjectProps(
                "database" to "Database id, or id/title from notion_search results marked [database].",
                "filter" to "Optional Notion filter JSON object, e.g. {\"property\":\"Status\",\"select\":{\"equals\":\"Done\"}}.",
                "sorts" to "Optional Notion sorts JSON array, e.g. [{\"property\":\"Name\",\"direction\":\"ascending\"}].",
                "limit" to "Optional max rows (1-100, default 20).",
            ),
            required = listOf("database"),
        ) else null,
        if (notionEnabled) spec(
            "notion_describe_database",
            "Describe a Notion database's schema: property names, types and select/status/ " +
                "multi-select options (GET /v1/databases/{id}). Read-only, both modes, no " +
                "approval. Call it before notion_query_database filter/sorts, or whenever a " +
                "filter fails with validation_error.",
            jsonObjectProps(
                "database" to "Database id, or id/title from notion_search results marked [database].",
            ),
            required = listOf("database"),
        ) else null,
        // Sentry tools, gated by the per-project Sentry integration. All
        // read-only: available in both modes, no approval.
        if (sentryEnabled) spec(
            "sentry_list_projects",
            "List the Sentry projects in the configured organization (slugs + ids), so you know " +
                "where to look. Read-only, both modes, no approval. Configured via the Sentry tab " +
                "of 'Configure Integrations…'.",
            jsonObjectProps(
                "query" to "Optional search hint (ignored by the API); kept for call symmetry.",
            ),
            required = listOf(),
        ) else null,
        if (sentryEnabled) spec(
            "sentry_search_issues",
            "Search grouped Sentry issues (error patterns) in the configured project, or across " +
                "the whole org when no project slug is set. Supports Sentry search syntax in " +
                "query: free text plus filters like is:unresolved, level:error, " +
                "browser.name:Chrome, release:, environment:, user:, status:. Returns issues " +
                "with counts and first/last seen so you see patterns, not noise. Read-only, both " +
                "modes, no approval. Use sentry_issue_events on an issue id for raw events.",
            jsonObjectProps(
                "query" to "Sentry search query (free text and/or is:/level:/browser.name:/release:/environment: filters).",
                "statsPeriod" to "Time window, e.g. 14d, 24h (default 14d).",
                "sort" to "Sort: freq (default), new, date, priority.",
                "limit" to "Max issues to return (1-50, default 10).",
            ),
            required = listOf("query"),
        ) else null,
        if (sentryEnabled) spec(
            "sentry_issue_events",
            "Fetch the raw log/error events for one Sentry issue (grouped by stacktrace " +
                "fingerprint), with timestamps, messages, context and top stack frames. Read-only, " +
                "both modes, no approval. Pass the issue id from sentry_search_issues.",
            jsonObjectProps(
                "issue" to "Issue id (from sentry_search_issues).",
                "limit" to "Max events to return (1-20, default 5).",
            ),
            required = listOf("issue"),
        ) else null,
        if (sentryEnabled) spec(
            "sentry_event_detail",
            "Fetch one full Sentry event with complete stacktrace, breadcrumbs, request, user and " +
                "context — the forensic deep-dive for 'what went wrong'. Read-only, both modes, no " +
                "approval. Pass the event id from sentry_issue_events.",
            jsonObjectProps(
                "event" to "Event id (from sentry_issue_events).",
            ),
            required = listOf("event"),
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
        onCommitRequested: (String) -> Boolean = onCommandRequested,
        /** Chat id owning this call — lets Stop kill in-flight commands. */
        chatId: String? = null,
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
            "notion_move_file" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.moveFile(project, args)
            }
            "notion_create_comment" -> when {
                planMode -> notAllowedInPlan(name)
                !notionAllowed(project) -> notEnabled(name)
                else -> NotionTool.createComment(project, args)
            }
            "notion_query_database" -> if (!notionAllowed(project)) notEnabled("notion_query_database")
                else NotionTool.queryDatabase(project, args)
            "notion_describe_database" -> if (!notionAllowed(project)) notEnabled("notion_describe_database")
                else NotionTool.describeDatabase(project, args)
            "sentry_list_projects" -> if (!sentryAllowed(project)) notEnabled("sentry_list_projects")
                else SentryTool.listProjects(project, args)
            "sentry_search_issues" -> if (!sentryAllowed(project)) notEnabled("sentry_search_issues")
                else SentryTool.searchIssues(project, args)
            "sentry_issue_events" -> if (!sentryAllowed(project)) notEnabled("sentry_issue_events")
                else SentryTool.issueEvents(project, args)
            "sentry_event_detail" -> if (!sentryAllowed(project)) notEnabled("sentry_event_detail")
                else SentryTool.eventDetail(project, args)
            "tellforge_instructions_read" -> if (!instructionsAllowed(project)) notEnabled("tellforge_instructions_read")
                else dev.tellforgeai.tools.InstructionsTool.read(project, args)
            "tellforge_instructions_write" -> when {
                planMode -> notAllowedInPlan(name)
                !instructionsAllowed(project) -> notEnabled(name)
                !dev.tellforgeai.settings.InstructionsRepoState.getInstance(project).writeEnabled() ->
                    "Error: 'tellforge_instructions_write' requires the user's write opt-in in the " +
                        "'Configure Instructions Repo' dialog (Config button in the tool window)."
                else -> dev.tellforgeai.tools.InstructionsTool.write(
                    project, args,
                    onCommitRequested = onCommitRequested,
                )
            }
            "run_command" -> CommandTool.run(project, args, planMode, onCommandRequested, chatId)
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

    /** Stale-spec guard: the Sentry tools must be enabled AND configured for this project. */
    private fun sentryAllowed(project: Project): Boolean {
        val s = dev.tellforgeai.settings.SentryState.getInstance(project)
        return s.enabled() && s.configured(project)
    }

    /** Stale-spec guard: the instructions tools must be enabled for this project. */
    private fun instructionsAllowed(project: Project): Boolean =
        dev.tellforgeai.settings.InstructionsRepoState.getInstance(project).enabled()

    private fun notEnabled(name: String): String =
        "Error: '$name' is disabled for this project. The user can enable it in the " +
            "'Configure Integrations…' dialog (Integrations button in the tool window)."
}