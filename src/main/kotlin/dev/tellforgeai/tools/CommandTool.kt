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

    private val FORBIDDEN = listOf(
        "rm ", "rm\t", "rmdir ", "del ", "rd ", "sudo", "shutdown", "reboot", "mkfs",
        " dd ", "chmod ", "chown ", "format ", "diskpart", "reg add", "reg delete",
        "taskkill", "remove-item", "ri ", "set-itemproperty",
    )

    /** Matches metacharacters not escaped with a backslash (escaped ones are literals, safe). */
    private val UNESCAPED_META = Regex("""(?<!\\)[|;&`>|]""")

    fun run(
        project: Project,
        args: JsonObject,
        planMode: Boolean,
        onCommandRequested: (String) -> Boolean,
    ): String {
        val command = VfsTools.str(args, "command")?.trim()
            ?: throw IllegalArgumentException("command required")
        if (planMode) {
            val ok = READ_ONLY_PREFIXES.any { command.startsWith(it) }
            if (!ok) return "Error: in Plan mode only read-only commands are allowed " +
                "(${READ_ONLY_PREFIXES.take(12).joinToString(", ")} ...). '$command' was NOT executed.\n" +
                "Tip: prefer the dedicated tools instead of shell commands — search_files to " +
                "search file names/contents, read_file to read, list_files to explore."
        }
        // Strip escaped metacharacters (\| \; \& \` \$ etc. are literals in shell/regex,
        // not operators), then check for real injection patterns.
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
            return "Blocked: '$op' is not allowed, even for read-only commands " +
                "(pipes, redirects, command substitution and compound commands are always refused). " +
                "Instead:\n" +
                "  - Search file contents/names -> search_files(query=\"...\", path=\"...\") " +
                "(one call per term; calls can be made in parallel)\n" +
                "  - Read a file with line numbers -> read_file(path=\"...\")\n" +
                "  - Explore structure -> list_files(path=\"...\")\n" +
                "A plain single command without operators is fine. Do not retry variations of " +
                "this command."
        }
        if (!onCommandRequested(command)) return "Command declined by user."
        val timeoutSeconds = TellForgeAISettings.getInstance().commandTimeoutSeconds().toLong()
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
            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
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