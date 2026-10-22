package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.TellForgeAISettings
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * CLI execution tool. In Foretell mode only read-only commands are allowed;
 * every command requires explicit user approval via [onCommandRequested].
 */
object CommandTool {

    internal val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CommandTool::class.java)

    const val DEFAULT_TIMEOUT_SECONDS = 30L

    /** Hard ceiling for any command timeout (per-call or configured): 5 minutes. */
    const val MIN_TIMEOUT_SECONDS = 1L
    const val MAX_TIMEOUT_SECONDS = 300L

    /**
     * Optional per-call `timeout` (seconds) from the tool arguments, clamped to
     * [MIN_TIMEOUT_SECONDS]..[MAX_TIMEOUT_SECONDS]. Null when absent or non-numeric
     * — the caller then falls back to the global setting.
     */
    internal fun requestedTimeoutSeconds(args: JsonObject): Long? =
        VfsTools.intArg(args, "timeout")
            ?.let { it.toLong().coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS) }
    const val MAX_OUTPUT_CHARS = 8_000

    val IS_WINDOWS: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    /** Read-only command prefixes (POSIX). */
    private val READ_ONLY_PREFIXES_UNIX = listOf(
        "ls", "cat", "head", "tail", "find", "grep", "rg", "pwd", "wc", "file",
        "git status", "git log", "git diff", "git show", "git branch", "git remote",
        "git blame", "git stash list", "git tag", "git describe", "git rev-parse",
        "git ls-files", "git ls-remote", "git config --get", "git config --list",
        "node ", "node --", "npm ", "npx ", "yarn ", "pnpm ", "php ", "composer ",
        "which", "echo", "du ", "df ", "whoami", "tree",
    )
    /** Read-only command prefixes (Windows cmd / PowerShell-friendly equivalents). */
    private val READ_ONLY_PREFIXES_WINDOWS = listOf(
        "dir", "type", "more", "where", "findstr", "systeminfo", "ver", "tasklist",
        "ipconfig", "netstat -an", "git status", "git log", "git diff", "git show",
        "git branch", "git remote", "git blame", "git stash list", "git tag",
        "git describe", "git rev-parse", "git ls-files", "git ls-remote",
        "git config --get", "git config --list",
        "node ", "node --", "npm ", "npx ", "yarn ",
        "pnpm ", "php ", "composer ", "echo", "tree",
    )
    private val READ_ONLY_PREFIXES: List<String>
        get() = if (IS_WINDOWS) READ_ONLY_PREFIXES_WINDOWS else READ_ONLY_PREFIXES_UNIX

    /** Whether a command starts with one of the prefixes permitted in Foretell mode. */
    internal fun isReadOnlyCommand(command: String): Boolean =
        READ_ONLY_PREFIXES.any { command.startsWith(it) }

    /** Foretell rejection text includes every allowed prefix, avoiding a misleading abbreviated list. */
    internal fun planModeRejection(command: String): String =
        "Error: in Plan mode only read-only commands are allowed " +
            "(${READ_ONLY_PREFIXES.joinToString(", ")}). '$command' was NOT executed.\n" +
            "Tip: prefer the dedicated tools instead of shell commands — search_files to " +
            "search file names/contents, read_file to read, list_files to explore."

    private val FORBIDDEN = listOf(
        "rm ", "rm\t", "rmdir ", "del ", "rd ", "sudo", "shutdown", "reboot", "mkfs",
        " dd ", "chmod ", "chown ", "format ", "diskpart", "reg add", "reg delete",
        "taskkill", "remove-item", "ri ", "set-itemproperty",
    )

    /**
     * Matches metacharacters not escaped with a backslash (escaped ones are literals,
     * safe): pipes, semicolons, ampersands, backticks, redirects and `$(` command
     * substitution. `$(` is expanded even inside double quotes, so it is checked on
     * the raw segment as well as the quote-stripped copy.
     */
    private val UNESCAPED_META = Regex("""(?<!\\)([|;&`>]|\$\()""")

    /**
     * Pipeline tail commands that are pure readers (or non-destructive filters) and are
     * therefore allowed after an unescaped pipe in read-only mode. Deliberately excludes
     * awk/sed (both can write: awk `system()`, `sed -i`).
     */
    private val SAFE_PIPE_TAIL = Regex("""^(head|tail|wc|sort|uniq|cut|column|tac|rev)\b""")

    /**
     * Splits [command] into pipeline segments on unescaped, unquoted `|`, respecting
     * single/double quotes and backslash escapes so string literals like `"a|b"` are
     * never treated as operators.
     */
    internal fun splitPipeline(command: String): List<String> {
        val segments = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (c in command) {
            when {
                escaped -> { sb.append(c); escaped = false }
                c == '\\' && !inSingle -> { sb.append(c); escaped = true }
                c == '\'' && !inDouble -> { inSingle = !inSingle; sb.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; sb.append(c) }
                c == '|' && !inSingle && !inDouble -> { segments.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
        }
        segments.add(sb.toString().trim())
        return segments
    }

    /**
     * Validates a read-only (Foretell) command. Allows pipelines whose first stage is a
     * read-only prefix command and whose tail stages are safe readers ([SAFE_PIPE_TAIL]).
     * Redirects [> >>], command substitution [` $(`], chaining [; &] and forbidden
     * commands are still refused in every segment. Returns null when the command is
     * acceptable, otherwise a rejection message.
     */
    internal fun readOnlyRejection(command: String): String? {
        val segments = splitPipeline(command)
        if (segments.firstOrNull()?.let { !isReadOnlyCommand(it) } == true) {
            return planModeRejection(command)
        }
        val unescapedSegments = segments.map { it.replace(Regex("""\\(.)"""), "$1") }
        for (segment in unescapedSegments) {
            val lowered = segment.lowercase()
            FORBIDDEN.firstOrNull { lowered.contains(it) }?.let {
                return "Error: command refused ('$command' was NOT executed): contains forbidden " +
                    "command '$it'. Use the dedicated tools instead: search_files (search " +
                    "contents/names), read_file, list_files. Do not retry variations of this command."
            }
            // Quoted substrings are literals ("a|b"), strip them before the meta check.
            val outsideQuotes = stripQuotes(segment)
            // Any metacharacter other than the pipes we split on is unsafe.
            UNESCAPED_META.find(outsideQuotes)?.let { m ->
                return redirectBlock(command, m.value)
            }
        }
        if (segments.size > 1) {
            tails@ for (tail in segments.drop(1).map { it.trimStart() }) {
                if (!SAFE_PIPE_TAIL.containsMatchIn(tail)) {
                    // Empty or non-allowlisted tail: fall back to the general block so the
                    // model understands the pipeline itself was the problem.
                    return redirectBlock(command, "|")
                }
            }
        }
        return null
    }

    /** Removes single- and double-quoted substrings (replaces with spaces) from [segment]. */
    private fun stripQuotes(segment: String): String =
        Regex("""'[^']*'|"[^"]*"""").replace(segment, " ")

    private fun redirectBlock(command: String, op: String): String =
        "Blocked: '$op' is not allowed" +
            (if (op == "|") " in this position. Only pipelines into pure readers are allowed " +
                "in read-only mode: | head, | tail, | wc, | sort, | uniq, | cut. "
            else " (redirects, command substitution and compound commands are always refused). ") +
            "The command '$command' was NOT executed.\n" +
            "Instead:\n" +
            "  - Search file contents/names -> search_files(query=\"...\", path=\"...\") " +
            "(one call per term; calls can be made in parallel)\n" +
            "  - Read a file with line numbers -> read_file(path=\"...\")\n" +
            "  - Explore structure -> list_files(path=\"...\")\n" +
            "A plain single command without operators is fine. Do not retry variations of " +
            "this command."

    fun run(
        project: Project,
        args: JsonObject,
        planMode: Boolean,
        onCommandRequested: (String) -> Boolean,
        /** Optional chat id — when supplied, Stop can kill the running process. */
        chatId: String? = null,
    ): String {
        val command = VfsTools.str(args, "command")?.trim()
            ?: throw IllegalArgumentException("command required")
        if (planMode) {
            readOnlyRejection(command)?.let { return it }
        }
        val unescaped = command.replace(Regex("""\\(.)"""), "$1")
        // Case-insensitive on purpose: Windows commands are case-insensitive
        // (DEL/del, REG ADD/reg add…), and the list is stored lowercase.
        FORBIDDEN.firstOrNull { unescaped.lowercase().contains(it) }?.let {
            return "Error: command refused ('$command' was NOT executed): contains forbidden " +
                "command '$it'. Use the dedicated tools instead: search_files (search " +
                "contents/names), read_file, list_files. Do not retry variations of this command."
        }
        UNESCAPED_META.find(unescaped)?.let { m ->
            val op = m.value
            return redirectBlock(command, op)
        }
        if (!onCommandRequested(command)) return "Command declined by user."
        val timeoutSeconds = requestedTimeoutSeconds(args)
            ?: TellForgeAISettings.getInstance().commandTimeoutSeconds().toLong()
                .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        return try {
            val builder = if (IS_WINDOWS) {
                // cmd.exe /c covers .bat/.cmd and builtins (dir, type, where…).
                // PowerShell isn't used: its quoting rules differ and cmd is the
                // safe default for arbitrary single commands.
                ProcessBuilder("cmd.exe", "/d", "/c", command)
            } else {
                // Resolve bare command names to absolute paths: the IDE-spawned
                // shell often has a sanitized PATH (e.g. no /opt/homebrew/bin on
                // macOS), which would fail on installed commands like php/node.
                val effective = CommandResolver.resolveCommand(command)
                ProcessBuilder("/bin/sh", "-c", effective)
            }
            val proc = builder
                .directory(VfsTools.root(project))
                .start()
            // Register so ChatEngine.stop() can destroyForcibly() this process;
            // aborted via Stop, waitFor returns and the result reports "aborted".
            val registry = chatId?.let { dev.tellforgeai.chat.ToolProcessRegistry }
            if (registry != null) registry.register(chatId, proc)
            val finished: Boolean = try {
                proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (registry != null) registry.unregister(chatId, proc)
                if (registry != null && registry.wasAborted(chatId, proc)) {
                    return "Error: command aborted by user (Stop)."
                }
                if (registry != null) registry.clearAborted(chatId, proc)
            }
            if (!finished) {
                proc.destroyForcibly()
                return "Error: command timed out after ${timeoutSeconds}s (adjustable in Settings → Tools → TellForgeAI Providers)"
            }
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            var combined = buildString {
                if (out.isNotBlank()) append(out)
                if (err.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr]\n").append(err)
                }
                append("\n[exit code: ${proc.exitValue()}]")
            }
            // Exit 127 = command not found. We already resolved bare names via
            // CommandResolver, so this means the executable is genuinely absent
            // (or only reachable through tooling this environment can't see).
            if (proc.exitValue() == 127) {
                combined += "\nNote: 'command not found' (127). Bare command names were " +
                    "already resolved to absolute paths where possible (Homebrew, " +
                    "/usr/local, version-manager shims, login shell). If this tool is " +
                    "installed, invoke it by absolute path or check that it is really installed."
            }
            if (combined.length > MAX_OUTPUT_CHARS) {
                combined.take(MAX_OUTPUT_CHARS) + "\n... (output truncated)"
            } else combined
        } catch (e: Exception) {
            "Error running command: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}