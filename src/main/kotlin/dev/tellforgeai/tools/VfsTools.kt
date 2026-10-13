package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Virtual-file-level tools: list, read, search. All paths stay inside the project root. */
object VfsTools {

    const val MAX_LIST_RESULTS = 200
    const val MAX_READ_CHARS = 24_000
    const val MAX_SEARCH_RESULTS = 60

    fun root(project: Project): File = File(project.basePath ?: ".")

    fun rules(project: Project): IgnoreRules = IgnoreRules.loadFresh(root(project))

    fun rel(project: Project, f: File): String =
        f.relativeTo(root(project)).invariantSeparatorsPath.ifEmpty { "." }

    fun resolve(project: Project, rel: String?): File {
        val p = rel?.trim().orEmpty()
        val f = File(root(project), p.ifEmpty { "." }).canonicalFile
        if (!f.path.startsWith(root(project).canonicalPath)) {
            throw IllegalArgumentException("Path '$p' is outside the project root")
        }
        val r = IgnoreRules.loadFresh(root(project))
        val relNorm = rel(project, f)
        if (r.isPathIgnored(relNorm, f.isDirectory)) {
            val src = r.sources.joinToString(", ")
            throw IllegalArgumentException(
                "Path '$p' is excluded by the project's ignore rules ($src); do not retry it — use other files"
            )
        }
        return f
    }

    private fun relPath(project: Project, f: File): String = rel(project, f)

    internal fun str(o: JsonObject, key: String): String? =
        o[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    internal fun intArg(o: JsonObject, key: String): Int? =
        o[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }

    fun listFiles(project: Project, args: JsonObject): String {
        val dir = resolve(project, str(args, "path") ?: ".")
        if (!dir.isDirectory) return "Error: '${relPath(project, dir)}' is not a directory"
        val depth = (intArg(args, "depth") ?: 2).coerceIn(1, 6)
        val lines = mutableListOf<String>()
        walk(project, dir, 0, depth, lines)
        return if (lines.isEmpty()) "(empty)" else
            lines.joinToString("\n") + "\n(excluding paths matched by: ${rules(project).sources.joinToString(", ")})"
    }

    private fun walk(project: Project, dir: File, depth: Int, maxDepth: Int, lines: MutableList<String>) {
        val rules = rules(project)
        val children = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return
        for (c in children) {
            if (lines.size >= MAX_LIST_RESULTS) {
                lines.add("... (truncated)")
                return
            }
            if (rules.isPathIgnored(rel(project, c), c.isDirectory)) continue
            lines.add(relPath(project, c) + if (c.isDirectory) "/" else "")
            if (c.isDirectory && depth + 1 < maxDepth) {
                walk(project, c, depth + 1, maxDepth, lines)
            }
        }
    }

    fun readFile(project: Project, args: JsonObject): String {
        val f = resolve(project, str(args, "path") ?: throw IllegalArgumentException("path required"))
        if (!f.isFile) return "Error: not a file: ${relPath(project, f)}"
        val r = rules(project)
        if (r.isPathIgnored(relPath(project, f), false)) {
            return "Error: '${relPath(project, f)}' is excluded by ignore rules (${r.sources.joinToString(", ")})"
        }
        if (f.length() > 1_000_000) return "Error: file too large to read (${f.length()} bytes)"
        val allLines = f.readLines()
        val start = (intArg(args, "startLine") ?: 1).coerceAtLeast(1)
        val end = (intArg(args, "endLine") ?: allLines.size).coerceAtMost(allLines.size)
        if (start > end) return "Error: startLine ($start) is after endLine ($end)"
        val sb = StringBuilder()
        var chars = 0
        for (i in start..end) {
            val line = "${i.toString().padStart(5)}: ${allLines[i - 1]}"
            if (chars + line.length > MAX_READ_CHARS) {
                sb.appendLine("... (truncated at line ${i - 1} of ${allLines.size})")
                break
            }
            sb.appendLine(line)
            chars += line.length
        }
        return sb.toString().trimEnd().ifEmpty { "(empty file)" }
    }

    fun searchFiles(project: Project, args: JsonObject): String {
        val query = (str(args, "query") ?: throw IllegalArgumentException("query required")).lowercase()
        val base = resolve(project, str(args, "path") ?: ".")
        if (!base.isDirectory) return "Error: '${relPath(project, base)}' is not a directory"
        val results = mutableListOf<String>()
        val rules = rules(project)
        val queue = ArrayDeque(listOf(base))
        outer@ while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (c in dir.listFiles() ?: continue) {
                if (results.size >= MAX_SEARCH_RESULTS) break@outer
                if (rules.isPathIgnored(relPath(project, c), c.isDirectory)) continue
                if (c.isDirectory) {
                    queue.add(c)
                    continue
                }
                if (c.name.lowercase().contains(query)) {
                    results.add("name match: ${relPath(project, c)}")
                } else if (c.length() < 512_000 && isTextFile(c)) {
                    try {
                        c.useLines { lines ->
                            var n = 0
                            for (l in lines) {
                                n++
                                if (l.lowercase().contains(query)) {
                                    results.add("${relPath(project, c)}:$n: ${l.trim().take(200)}")
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) { /* unreadable: skip */ }
                }
            }
        }
        return if (results.isEmpty()) "No matches for '$query'"
        else results.joinToString("\n") +
            (if (results.size >= MAX_SEARCH_RESULTS) "\n... (more results truncated)" else "")
    }

    private fun isTextFile(f: File): Boolean = f.extension.lowercase() in setOf(
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "xml", "html", "css",
        "scss", "less", "md", "txt", "yml", "yaml", "sh", "gradle", "properties", "php",
        "vue", "env", "toml", "cfg", "conf",
    )
}