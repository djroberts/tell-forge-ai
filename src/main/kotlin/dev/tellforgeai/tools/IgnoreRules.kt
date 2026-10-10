package dev.tellforgeai.tools

import java.io.File

/**
 * Loads and evaluates gitignore-style ignore rules for a project.
 *
 * Sources, in order: project-local AI ignore files (`.aiignore`, `.aiderignore`,
 * `.cursorignore`, ...), then `.gitignore`, then a hardcoded default blocklist for
 * secrets and generated directories. Later rules win; `!` negation re-includes.
 *
 * Supported syntax (best-effort gitignore semantics):
 * - `#` comments and blank lines are skipped
 * - trailing `/` matches directories only
 * - a leading `/` or an embedded `/` anchors the pattern at the project root;
 *   otherwise the pattern matches at any depth
 * - `*`, `?`, `[...]` single-level globs; `**` spans directories
 */
class IgnoreRules private constructor(val sources: List<String>) {

    private data class Pattern(val regex: Regex, val dirOnly: Boolean, val negated: Boolean)

    private var patterns: List<Pattern> = emptyList()

    /** True when [relPath] (slash-separated, relative to project root, no leading slash) is ignored. */
    fun isIgnored(relPath: String, isDirectory: Boolean): Boolean {
        if (relPath.isEmpty() || relPath == ".") return false
        return patterns.fold(false) { acc, p ->
            if ((!p.dirOnly || isDirectory) && p.regex.matches(relPath)) !p.negated else acc
        }
    }

    /** True when [relPath] itself or any ancestor directory is ignored. */
    fun isPathIgnored(relPath: String, isDirectory: Boolean): Boolean {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        for (i in parts.indices) {
            // Intermediate segments are always directories; only the last one
            // uses the caller's isDirectory flag.
            val isDir = i != parts.lastIndex || isDirectory
            if (isIgnored(parts.subList(0, i + 1).joinToString("/"), isDir)) return true
        }
        return false
    }

    companion object {

        /** Ignore files recognized from AI coding agents. Order matters: later files override. */
        val AI_IGNORE_FILES = listOf(
            ".aiignore", ".aiderignore", ".cursorignore", ".claudeignore",
            ".windsurfignore", ".codeiumignore", ".continueignore", ".botignore",
        )

        private const val GITIGNORE = ".gitignore"

        /** Built-in fallback when a project declares no ignore files. */
        private val DEFAULT_PATTERNS = listOf(
            ".git/", ".idea/", ".gradle/", ".intellijPlatform/", "build/", "dist/", "out/",
            "target/", "node_modules/", "vendor/", "__pycache__/", ".next/", ".cache/",
            "*.env", ".env*", "*.pem", "*.key", "secrets/",
        )

        private val cache = java.util.concurrent.ConcurrentHashMap<String, IgnoreRules>()

        /** Load rules for [root]. Cached per canonical root path. */
        fun load(root: File): IgnoreRules = cache.getOrPut(root.canonicalPath) { read(root) }

        fun invalidate(root: File) { cache.remove(root.canonicalPath) }

        private fun read(root: File): IgnoreRules {
            val sources = mutableListOf<String>()
            val lines = mutableListOf<String>()
            for (name in listOf(GITIGNORE) + AI_IGNORE_FILES) {
                val f = File(root, name)
                if (f.isFile) {
                    sources.add(name)
                    lines.addAll(f.readLines())
                }
            }
            if (sources.isEmpty()) {
                sources.add("(built-in defaults)")
                lines.addAll(DEFAULT_PATTERNS)
            }
            return IgnoreRules(sources.toList()).apply { patterns = lines.mapNotNull { compile(it) } }
        }

        private fun compile(line: String): Pattern? {
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#") || raw == "!") return null
            var negated = false
            var s = raw
            if (s.startsWith("!")) { negated = true; s = s.substring(1) }
            if (s.startsWith("\\")) s = s.substring(1) // escaped '#'/'!'
            var dirOnly = false
            if (s.endsWith("/")) { dirOnly = true; s = s.trimEnd('/') }
            var anchored = s.startsWith("/")
            if (anchored) s = s.substring(1)
            if (s.contains('/')) anchored = true
            s = s.trimEnd('/')
            if (s.isEmpty()) return null
            val regex = globToRegex(s, anchored)
            return Pattern(regex, dirOnly, negated)
        }

        private fun globToRegex(glob: String, anchored: Boolean): Regex {
            val sb = StringBuilder()
            if (!anchored) sb.append("(?:.*/)?")
            var i = 0
            while (i < glob.length) {
                when (val c = glob[i]) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            if (i + 2 < glob.length && glob[i + 2] == '/') {
                                sb.append("(?:.*/)?")
                                i += 2
                            } else { sb.append(".*"); i++ }
                        } else sb.append("[^/]*")
                    }
                    '?' -> sb.append("[^/]")
                    '[' -> {
                        val end = glob.indexOf(']', i).takeIf { it > i + 1 } ?: glob.lastIndex
                        sb.append(glob.substring(i, (end + 1).coerceAtMost(glob.length)))
                        i = end
                    }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                if (i == glob.length) break
                i++
            }
            return try { Regex(sb.toString()) } catch (_: Exception) { Regex(Regex.escape(glob)) }
        }
    }
}
