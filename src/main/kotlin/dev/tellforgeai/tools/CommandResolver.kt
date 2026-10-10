package dev.tellforgeai.tools

import dev.tellforgeai.settings.TellForgeAISettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves command names to absolute executable paths.
 *
 * Why: the tool spawns a plain `/bin/sh -c` process whose PATH is inherited from
 * the IDE, which is often a sanitized subset of the user's login PATH (e.g. on
 * macOS Homebrew lives in /opt/homebrew/bin, which is added by ~/.zprofile and
 * may be missing). A command like `php` then fails with "not found" even though
 * it is installed — a false negative that derails the agent's plan.
 *
 * Resolution ladder (first hit wins):
 *  1. Session cache (per IDE session, in-memory).
 *  2. Persistent cache (TellForgeAISettings, app-level "tellforgeai.xml") — validated with
 *     [File.canExecute] on every load; stale entries fall through and are replaced.
 *  3. Probe well-known install directories (Homebrew, /usr/local, version-manager
 *     shims such as pyenv/asdf/mise/nvm).
 *  4. Login shell probe (`bash -lc` / `zsh -lc 'command -v <name>'`) which picks up
 *     the user's own PATH setup.
 *
 * Successful resolutions are written back to both caches so the probe ladder only
 * ever runs once per command per machine.
 */
object CommandResolver {

    /** Session-scoped cache: command name -> absolute path. */
    private val sessionCache = ConcurrentHashMap<String, String>()

    /** Commands already probed unsuccessfully this session (negative cache). */
    private val unresolved = ConcurrentHashMap.newKeySet<String>()

    /** Only the leading token of a command is resolved; commands already using an
     *  absolute/relative path (containing '/') are left untouched. */
    fun resolveCommand(command: String): String {
        if (CommandTool.IS_WINDOWS) return command // cmd.exe resolves its own PATH
        val token = command.split(Regex("\\s+"), limit = 2).firstOrNull() ?: return command
        if (token.isEmpty() || token.contains('/') || token.contains('\\')) return command
        val resolved = resolve(token) ?: return command
        return command.removePrefix(token).let { rest -> resolved + rest }
    }

    /** True when a resolution attempt is worthwhile (leading token is a bare name). */
    fun isResolvable(command: String): Boolean {
        if (CommandTool.IS_WINDOWS) return false
        val token = command.split(Regex("\\s+"), limit = 2).firstOrNull() ?: return false
        return token.isNotEmpty() && !token.contains('/') && !token.contains('\\')
    }

    /** Resolves a bare command name; returns null if all steps fail. */
    fun resolve(name: String): String? {
        sessionCache[name]?.let { p -> if (File(p).canExecute()) return p else sessionCache.remove(name) }

        TellForgeAISettings.getInstance().commandPath(name)?.let { p ->
            if (File(p).canExecute()) {
                sessionCache[name] = p
                return p
            }
            // Stale entry — drop it and fall through to probing.
            TellForgeAISettings.getInstance().setCommandPath(name, null)
        }

        if (name in unresolved) return null

        probeWellKnownDirs(name)?.let {
            remember(name, it, source = "probe")
            return it
        }
        probeLoginShell(name)?.let {
            remember(name, it, source = "login-shell")
            return it
        }
        unresolved.add(name)
        return null
    }

    private fun remember(name: String, path: String, source: String) {
        sessionCache[name] = path
        TellForgeAISettings.getInstance().setCommandPath(name, path)
        CommandTool.LOG.info("Resolved '$name' -> '$path' (via $source)")
    }

    /** Well-known install locations, per OS. Ordered by likelihood. */
    private fun wellKnownDirs(): List<String> {
        val home = System.getProperty("user.home")
        return if (CommandTool.IS_WINDOWS) {
            listOf(
                "C:\\Program Files", "C:\\Program Files (x86)",
                "$home\\AppData\\Local\\Programs", "$home\\scoop\\shims",
            )
        } else {
            listOf(
                // Apple Silicon / Intel Homebrew
                "/opt/homebrew/bin", "/opt/homebrew/sbin",
                "/usr/local/bin", "/usr/local/sbin",
                // Version-manager shims (stable across tool version upgrades —
                // prefer these over deep versioned paths)
                "$home/.pyenv/shims", "$home/.asdf/shims", "$home/.local/bin",
                "$home/.cargo/bin", "$home/go/bin",
                // nvm resolves via its current-symlink layout
                "$home/.nvm/versions/node",
                // Base system
                "/usr/bin", "/bin", "/usr/sbin", "/sbin",
            )
        }
    }

    private fun probeWellKnownDirs(name: String): String? {
        for (dir in wellKnownDirs()) {
            val candidate = File(dir, name)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            // nvm: versions/node/<semver>/bin/<name> — pick the highest version present.
            if (dir.endsWith(".nvm/versions/node") && candidate.isDirectory) {
                candidate.listFiles { f -> f.isDirectory }
                    ?.maxByOrNull { it.name }
                    ?.let { v ->
                        val exe = File(v, "bin/$name")
                        if (exe.isFile && exe.canExecute()) return exe.absolutePath
                    }
            }
        }
        return null
    }

    /**
     * Ask a login shell, which sources the user's profile files and therefore sees
     * their full PATH (aliases, shellinit, mise/ direnv hooks, …).
     */
    private fun probeLoginShell(name: String): String? {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"
        val shells = listOf(shell, "/bin/bash", "/bin/zsh").distinct()
        for (sh in shells) {
            if (!File(sh).canExecute()) continue
            try {
                val proc = ProcessBuilder(sh, "-lc", "command -v -- ${shellQuote(name)}")
                    .redirectErrorStream(false)
                    .start()
                if (proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    if (proc.exitValue() == 0 && out.startsWith("/")) {
                        val f = File(out)
                        if (f.canExecute()) return out
                    }
                } else proc.destroyForcibly()
            } catch (_: Exception) {
                // Shell unavailable or timed out — try the next candidate.
            }
        }
        return null
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Dropped on plugin settings reset / stale entries; exposed for completeness. */
    fun invalidateSessionCache() {
        sessionCache.clear()
        unresolved.clear()
    }
}
