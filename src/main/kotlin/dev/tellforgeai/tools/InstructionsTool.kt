package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.llm.InstructionsRepoService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tools over the user-maintained AI instructions. Deliberately capability-
 * abstracted: the model never sees a repo, a path, a branch or a file name —
 * only "the instructions". The file is always the service's canonical
 * [InstructionsRepoService.CANONICAL_FILE]; any legacy `path` argument with a
 * different value is rejected instead of honoured, so the tool surface cannot
 * be talked into creating a second file in that repo.
 */
object InstructionsTool {

    /**
     * Only the canonical file is writable. Returns the normalized path when
     * [supplied] is the canonical file (or null/absent, meaning "default"),
     * else null when a different file was requested — a hard error: the
     * instruction surface is a single document and no second file may ever
     * be created next to it.
     */
    internal fun canonicalOrReject(supplied: String?): String? =
        if (supplied == null || supplied.trim().equals(InstructionsRepoService.CANONICAL_FILE, ignoreCase = true))
            InstructionsRepoService.CANONICAL_FILE
        else null

    /** Both modes: return the raw instructions markdown. */
    fun read(project: Project, @Suppress("UNUSED_PARAMETER") args: JsonObject): String {
        val service = InstructionsRepoService.getInstance(project)
        service.ensureFresh()
        val text = service.instructions()
        if (text.isEmpty()) {
            return "Error: no instructions synced yet. " +
                "Check the instructions configuration in the integrations dialog."
        }
        return "instructions (${service.toolStatus()}):\n\n$text"
    }

    /**
     * Forge only. Writes the whole new instructions content, shows the diff,
     * asks for approval (commit+push), then commits. A legacy `path` argument
     * is accepted only when it names the canonical file; anything else is a
     * hard error — the tool surface is single-file by design.
     * [onCommitRequested] is the same approval callback used for commands;
     * returning false declines the push.
     */
    fun write(
        project: Project,
        args: JsonObject,
        onCommitRequested: (String) -> Boolean,
    ): String {
        // Legacy/rogue callers may still pass `path`; only the canonical file
        // is writable, and anything else is rejected before touching the repo.
        val supplied = args["path"]?.jsonPrimitive?.content?.trim()
        if (canonicalOrReject(supplied) == null) {
            return "Error: the instructions are a single document — do not pass a " +
                "'path'; call instructions_write with just 'content'."
        }
        val content = args["content"]?.jsonPrimitive?.content.orEmpty()
        val message = args["message"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (content.isBlank()) return "Error: 'content' is required (full new instructions text)."

        val service = InstructionsRepoService.getInstance(project)
        val file = InstructionsRepoService.CANONICAL_FILE
        val diff = try {
            service.previewChange(file, content)
        } catch (e: Exception) {
            return "Error: ${e.message ?: e.javaClass.simpleName}"
        } ?: return "Error: instructions are not synced yet — ask the user to sync first."
        if (diff == "(no changes)") {
            return "No changes to make — the content matches what is already saved."
        }

        val approved = onCommitRequested(
            "Save the following change to the AI instructions?\n\n$diff",
        )
        if (!approved) {
            service.discardChange(file)
            return "Declined. The change was not saved."
        }
        return try {
            service.commitAndPush(file, message)
        } catch (e: Exception) {
            "Error saving: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
