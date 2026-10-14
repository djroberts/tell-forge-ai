package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project Tela wiki connection settings (tools -> TelaWikiTool).
 *
 * URL and endpoint roam with the project (they are not secret); the personal
 * access token is stored separately in PasswordSafe under a PER-PROJECT key
 * (see [ApiKeyStore.wikiTokenId]). [wikiEnabled] is the master on/off switch:
 * when false the wiki_* tools are not offered to the model at all.
 * [wikiWriteEnabled] is the one-time opt-in that lets the model create/update
 * wiki pages in Forge mode; it is only settable from the Configure Tela Wiki
 * dialog after a successful connection test.
 *
 * Approval per action (see [WikiWriteAction]): [wikiSkipCreate] /
 * [wikiSkipUpdate] / [wikiSkipDelete] — true skips the confirmation dialog
 * for that action, false (default) asks every time.
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAIWikiState", storages = [Storage("tellforgeai.wiki.xml")])
class TellForgeAIWikiState : PersistentStateComponent<TellForgeAIWikiState.State> {

    class State {
        /** Base URL of the tela instance, e.g. "https://telawiki.com". */
        var wikiUrl: String = "https://telawiki.com"

        /** MCP endpoint path on the instance. */
        var mcpPath: String = "/api/mcp"

        /** Master switch: expose the wiki tools to the model for this project. */
        var wikiEnabled: Boolean = false

        /** One-time opt-in: allow mutating wiki_* tools in Forge mode. */
        var wikiWriteEnabled: Boolean = false

        // ---- Per-action approval (true = skip the confirmation dialog) ----
        var wikiSkipCreate: Boolean = false
        var wikiSkipUpdate: Boolean = false
        var wikiSkipDelete: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun wikiUrl(): String {
        var u = state.wikiUrl.trim().trimEnd('/')
        // Users often paste the MCP endpoint or /api — strip known suffixes so
        // the stored value is always a bare base URL.
        for (suffix in listOf("/api/mcp", "/api", "/mcp")) {
            if (u.endsWith(suffix)) {
                u = u.removeSuffix(suffix)
                break
            }
        }
        return u.trimEnd('/')
    }

    fun setWikiUrl(value: String) {
        state.wikiUrl = value.trim()
    }

    fun mcpUrl(): String = wikiUrl() + (state.mcpPath.trim().ifEmpty { "/api/mcp" })

    fun setMcpPath(value: String) {
        state.mcpPath = value.trim()
    }

    fun enabled(): Boolean = state.wikiEnabled

    fun setEnabled(value: Boolean) {
        state.wikiEnabled = value
    }

    fun writeEnabled(): Boolean = state.wikiWriteEnabled

    fun setWriteEnabled(value: Boolean) {
        state.wikiWriteEnabled = value
    }

    fun skipApproval(action: WikiWriteAction): Boolean = when (action) {
        WikiWriteAction.CREATE -> state.wikiSkipCreate
        WikiWriteAction.UPDATE -> state.wikiSkipUpdate
        WikiWriteAction.DELETE -> state.wikiSkipDelete
    }

    fun setSkipApproval(action: WikiWriteAction, skip: Boolean) {
        when (action) {
            WikiWriteAction.CREATE -> state.wikiSkipCreate = skip
            WikiWriteAction.UPDATE -> state.wikiSkipUpdate = skip
            WikiWriteAction.DELETE -> state.wikiSkipDelete = skip
        }
    }

    /** Stored per-project token from PasswordSafe, or null. */
    fun token(project: Project): String? = ApiKeyStore.get(ApiKeyStore.wikiTokenId(project))

    fun setToken(project: Project, value: String) {
        ApiKeyStore.set(ApiKeyStore.wikiTokenId(project), value)
    }

    fun configured(project: Project): Boolean = wikiUrl().isNotEmpty() && !token(project).isNullOrBlank()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TellForgeAIWikiState =
            project.getService(TellForgeAIWikiState::class.java)
    }
}
