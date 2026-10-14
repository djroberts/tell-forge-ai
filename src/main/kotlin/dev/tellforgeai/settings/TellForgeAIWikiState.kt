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
 * access token is stored separately in PasswordSafe (see [ApiKeyStore.WIKI_TOKEN_ID]).
 * [wikiWriteEnabled] is the one-time opt-in that lets the model create/update
 * wiki pages in Forge mode; it is only settable from the Configure Tela Wiki
 * dialog after a successful connection test.
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAIWikiState", storages = [Storage("tellforgeai.wiki.xml")])
class TellForgeAIWikiState : PersistentStateComponent<TellForgeAIWikiState.State> {

    class State {
        /** Base URL of the tela instance, e.g. "https://telawiki.com". */
        var wikiUrl: String = "https://telawiki.com"

        /** MCP endpoint path on the instance. */
        var mcpPath: String = "/api/mcp"

        /** One-time opt-in: allow wiki_write_page in Forge mode. */
        var wikiWriteEnabled: Boolean = false
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

    fun writeEnabled(): Boolean = state.wikiWriteEnabled

    fun setWriteEnabled(value: Boolean) {
        state.wikiWriteEnabled = value
    }

    fun configured(): Boolean = wikiUrl().isNotEmpty() &&
        !ApiKeyStore.get(ApiKeyStore.WIKI_TOKEN_ID).isNullOrBlank()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TellForgeAIWikiState =
            project.getService(TellForgeAIWikiState::class.java)
    }
}
