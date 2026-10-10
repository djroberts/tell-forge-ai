package dev.tellforgeai.llm

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.tellforgeai.tools.IgnoreRules
import java.io.File

/**
 * Builds a compact always-on context block describing the current project,
 * injected into the system prompt so the model is grounded before answering.
 */
object ProjectContext {

    private const val MAX_TREE_ENTRIES = 250

    /** Human/model-readable snapshot of the project. Call on a background thread. */
    fun build(project: Project): String {
        val basePath = project.basePath ?: return ""
        val sb = StringBuilder()
        sb.appendLine("## Project context")
        sb.appendLine("Project: ${project.name}")
        sb.appendLine("Root: $basePath")
        val branch = gitBranch(basePath)
        if (branch.isNotBlank()) sb.appendLine("Git branch: $branch")
        sb.appendLine()
        sb.appendLine("Directory tree (depth 3, generated files ignored):")
        sb.append(tree(basePath))
        return sb.toString()
    }

    private fun gitBranch(basePath: String): String = try {
        val p = ProcessBuilder("git", "branch", "--show-current")
            .directory(File(basePath))
            .redirectErrorStream(true)
            .start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    } catch (_: Exception) {
        ""
    }

    private fun tree(basePath: String): String {
        val rootFile = File(basePath)
        val root = VfsUtil.findFileByIoFile(rootFile, true) ?: return "(project root not found)"
        val rules = IgnoreRules.load(rootFile)
        val lines = mutableListOf<String>()
        walk(root, "", 0, 3, lines, rules)
        return if (lines.isEmpty()) "(empty)" else lines.joinToString("\n").prependIndent("  ")
    }

    private fun walk(dir: VirtualFile, relPrefix: String, depth: Int, maxDepth: Int, lines: MutableList<String>, rules: IgnoreRules) {
        if (lines.size >= MAX_TREE_ENTRIES) return
        val children = dir.children
            .sortedWith(compareByDescending<VirtualFile> { it.isDirectory }.thenBy { it.name })
        for (child in children) {
            val rel = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
            if (rules.isPathIgnored(rel, child.isDirectory)) continue
            if (lines.size >= MAX_TREE_ENTRIES) {
                lines.add("... (truncated)")
                return
            }
            val indent = "  ".repeat(depth)
            lines.add("$indent${child.name}${if (child.isDirectory) "/" else ""}")
            if (child.isDirectory && depth + 1 < maxDepth) walk(child, rel, depth + 1, maxDepth, lines, rules)
        }
    }
}