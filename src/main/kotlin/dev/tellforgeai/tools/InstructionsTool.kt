package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.llm.InstructionsRepoService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tools for the user-maintained instructions repo (see
 * [InstructionsRepoService]). Read is free; write goes through a diff-based
 * approval dialog — the approval gates the commit AND push, since pushing
 * broadcasts the change to everyone sharing the repo — then commits as a
 * separate, versioned action in that repo (never the project's own).
 */
object InstructionsTool {

    /** Both modes: return the raw instructions markdown (or a named file). */
    fun read(project: Project, args: JsonObject): String {
        val service = InstructionsRepoService.getInstance(project)
        service.ensureFresh()
        val text = service.instructions()
        if (text.isEmpty()) {
            return "Error: no instructions synced yet (${service.status()}). " +
                "Check the repo URL in the 'Configure Instructions Repo' dialog."
        }
        val file = args["path"]?.jsonPrimitive?.content
        return if (file.isNullOrBlank()) {
            "instructions at HEAD (${service.status()}):\n\n$text"
        } else {
            // Named file: re-read from clone via full snapshot only; the merged
            // snapshot already contains per-file headers, so return it and let
            // the model use the header section it needs.
            "full instructions (${service.status()}):\n\n$text"
        }
    }

    /**
     * Forge only. Writes the content, shows the diff, asks for approval
     * (commit+push), then commits. [onCommitRequested] is the same approval
     * callback used for commands; returning false declines the push.
     */
    fun write(
        project: Project,
        args: JsonObject,
        onCommitRequested: (String) -> Boolean,
    ): String {
        val path = args["path"]?.jsonPrimitive?.content?.trim().orEmpty()
        val content = args["content"]?.jsonPrimitive?.content.orEmpty()
        val message = args["message"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (path.isBlank()) return "Error: 'path' is required."
        if (content.isBlank()) return "Error: 'content' is required (full new file content)."

        val service = InstructionsRepoService.getInstance(project)
        val diff = try {
            service.previewChange(path, content)
        } catch (e: Exception) {
            return "Error: ${e.message ?: e.javaClass.simpleName}"
        } ?: return "Error: invalid path '$path' (must stay inside the instructions folder)."
        if (diff == "(no changes)") return "No changes to make — content matches what is already committed."

        val approved = onCommitRequested(
            "Commit and push the following change to the instructions repo?\n\n" +
                "File: $path\n\n$diff",
        )
        if (!approved) {
            service.discardChange(path)
            return "Declined. The change was not committed or pushed."
        }
        return try {
            service.commitAndPush(path, message)
        } catch (e: Exception) {
            "Error committing/pushing: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
