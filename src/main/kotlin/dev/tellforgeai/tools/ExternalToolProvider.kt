package dev.tellforgeai.tools

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * Extension point `dev.tellforgeai.tool`: lets other plugins contribute chat
 * tools that appear in the model's tool list alongside the built-ins.
 *
 * Contract:
 *  - [specs] returns tool specs in the OpenAI function-calling format (the
 *    same shape [ToolRegistry.spec] emits). Specs may vary by [project] and
 *    [planMode]; providers are responsible for their own Foretell (plan) /
 *    Forge gating — if a tool must not run in plan mode, exclude its spec
 *    and refuse it in [execute].
 *  - [execute] runs on a background thread (never the EDT) and returns a
 *    plain string result, exactly like the built-in tools. Returning an
 *    "Error: …" string is the conventional failure shape.
 *  - [handles] decides which tool names this provider owns. The default
 *    derives them from the provider's specs; override if specs are
 *    expensive to build.
 *
 * Implementations must be defensive: a throwing provider degrades to its
 * tools being unavailable, never to a broken chat.
 */
interface ExternalToolProvider {
    companion object {
        val EP_NAME: ExtensionPointName<ExternalToolProvider> =
            ExtensionPointName.create("dev.tellforgeai.tool")
    }

    /** Tool specs in OpenAI function-calling format. */
    fun specs(project: Project?, planMode: Boolean): List<Map<String, Any>> = emptyList()

    /**
     * Execute a tool owned by this provider. [args] is the parsed
     * arguments object; [chatId] identifies the owning chat run.
     */
    fun execute(
        project: Project,
        name: String,
        args: JsonObject,
        planMode: Boolean,
        chatId: String?,
    ): String

    /** Whether this provider owns tool [name]. */
    fun handles(name: String, project: Project?): Boolean =
        specs(project, planMode = false).any {
            (it["function"] as? Map<*, *>)?.get("name") == name
        }
}
