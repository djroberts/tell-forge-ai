package dev.tellforgeai.tools

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * Extensions registered for [this] extension point, resolved for [project]
 * without ever letting the platform's "Missing extension point"
 * [IllegalArgumentException] escape.
 *
 * `ExtensionPointName.getExtensions(areaInstance)` performs a non-fallback
 * lookup: it throws if the point is not registered in the container it is
 * handed. That happens when a project container exists without this
 * plugin's extension points registered in it (e.g. the plugin jar was
 * reloaded while projects were already open, or the plugin's descriptor
 * failed to register EPs in an open project's container). Instead of
 * crashing the caller we degrade: prefer the project's registrations, then
 * the application (root) area's, else an empty list — logging at debug so
 * the situation is visible but harmless.
 */
internal fun <T : Any> ExtensionPointName<T>.getExtensionsSafe(project: Project?): List<T> {
    project?.extensionArea?.takeIf { it.hasExtensionPoint(name) }?.let { area ->
        return area.getExtensionPoint(this).getExtensionList()
    }
    val root = Extensions.getRootArea()
    if (root.hasExtensionPoint(name)) {
        return root.getExtensionPoint(this).getExtensionList()
    }
    thisLogger().debug("Extension point '$name' is not registered in project or root area; returning no extensions")
    return emptyList()
}

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
