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

    /** Tool specs sent to the model. [planMode] restricts the command tool. */
    fun specs(planMode: Boolean): List<Map<String, Any>> = listOfNotNull(
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
                "NEVER use pipes (|), redirects (>), command substitution (\$(...), backticks) or " +
                "compound commands (;, &&, ||) — these are always refused even for read-only " +
                "commands. Run a single simple command instead; if you need grep-like output " +
                "filtering, use search_files or read_file rather than 'grep ... | head'.\n" +
                "Do NOT claim a mutation is impossible without first TRYING it: if the user " +
                "is confident a command should work, run it once and report the real error " +
                "instead of asserting tool restrictions from memory.\n" +
                "Destructive commands (rm, sudo, force-push) still need explicit user request.",
            jsonObjectProps("command" to "The shell command to execute. Single command only — no pipes, redirects, or command chaining."),
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
        // File-modification tools exist only in Forge mode and go through the IDE editor.
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
}