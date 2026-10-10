package dev.tellforgeai.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.JsonObject

/**
 * File-modification tools that apply changes through the IDE's editor/document
 * layer (WriteCommandAction + Document), so edits are undoable, visible in the
 * WebStorm editor, and never touch a shell. Forge mode only.
 */
object EditorTools {

    /** Create a new file or overwrite an existing one with [content]. */
    fun writeFile(project: Project, args: JsonObject): String {
        val rel = VfsTools.str(args, "path")?.trim()
            ?: throw IllegalArgumentException("path required")
        val content = VfsTools.str(args, "content")
            ?: throw IllegalArgumentException("content required")
        val target = VfsTools.resolve(project, rel)

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val existing = VfsUtil.findFileByIoFile(target, true)
                    if (existing != null && existing.isDirectory) {
                        result = "Error: '$rel' is a directory"
                        return@runWriteCommandAction
                    }
                    val vf = existing ?: run {
                        val parent = VfsUtil.createDirectoryIfMissing(target.canonicalFile.parent)
                        if (parent == null) {
                            result = "Error: could not create parent directory for '$rel'"
                            return@runWriteCommandAction
                        }
                        parent.createChildData(this, target.name)
                    }
                    val doc = FileDocumentManager.getInstance().getDocument(vf)
                    if (doc == null) {
                        result = "Error: cannot open document for '$rel'"
                        return@runWriteCommandAction
                    }
                    doc.setText(content)
                    FileDocumentManager.getInstance().saveDocument(doc)
                    openInEditor(project, vf)
                    result = if (existing == null) "Created $rel" else "Overwrote $rel"
                } catch (e: Exception) {
                    result = "Error writing '$rel': ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        return result
    }

    /** Replace an exact string in a file (IDE document layer, undoable). */
    fun editFile(project: Project, args: JsonObject): String {
        val rel = VfsTools.str(args, "path")?.trim()
            ?: throw IllegalArgumentException("path required")
        val oldString = VfsTools.str(args, "oldString")
            ?: throw IllegalArgumentException("oldString required")
        val newString = VfsTools.str(args, "newString")
            ?: throw IllegalArgumentException("newString required")
        val replaceAll = VfsTools.str(args, "replaceAll") == "true"
        if (oldString.isEmpty()) return "Error: oldString must not be empty"
        val target = VfsTools.resolve(project, rel)

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val vf = VfsUtil.findFileByIoFile(target, true)
                    if (vf == null || !vf.exists()) {
                        result = "Error: file not found: $rel"
                        return@runWriteCommandAction
                    }
                    val doc = FileDocumentManager.getInstance().getDocument(vf)
                    if (doc == null) {
                        result = "Error: cannot open document for '$rel'"
                        return@runWriteCommandAction
                    }
                    val text = doc.text
                    val count = countOccurrences(text, oldString)
                    when {
                        count == 0 -> result =
                            "Error: oldString not found in $rel. Read the file first and copy the exact text (including whitespace)."
                        count > 1 && !replaceAll -> result =
                            "Error: oldString occurs $count times in $rel. Provide more surrounding context or set replaceAll=true."
                        else -> {
                            doc.setText(if (replaceAll) text.replace(oldString, newString)
                            else text.replaceFirst(oldString, newString))
                            FileDocumentManager.getInstance().saveDocument(doc)
                            openInEditor(project, vf)
                            result = "Edited $rel (${if (replaceAll) count else 1} replacement(s))"
                        }
                    }
                } catch (e: Exception) {
                    result = "Error editing '$rel': ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        return result
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun openInEditor(project: Project, vf: com.intellij.openapi.vfs.VirtualFile) {
        FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, vf), false
        )
    }
}
