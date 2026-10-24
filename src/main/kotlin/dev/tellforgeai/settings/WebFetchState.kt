package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project webfetch integration settings (tools -> WebFetchTool).
 *
 * Points the agent's `web_fetch` tool at a self-hosted webfetch service
 * (tiered URL -> markdown fetch, Jina-style REST shape). Only the service
 * base URL roams with the project; the optional bearer token is stored
 * separately in PasswordSafe under a per-project key
 * (see [ApiKeyStore.webFetchTokenId]). [webFetchEnabled] is the master
 * on/off switch: when false the web_fetch tool is not offered to the model.
 *
 * The bearer token is OPTIONAL: a local/unprotected webfetch instance needs
 * none, while a shared deployment can require one (sent as
 * `Authorization: Bearer <token>`).
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAIWebFetchState", storages = [Storage("tellforgeai.webfetch.xml")])
class WebFetchState : PersistentStateComponent<WebFetchState.State> {

    class State {
        /** Master switch: expose the web_fetch tool to the model for this project. */
        var webFetchEnabled: Boolean = false

        /** webfetch service base URL, e.g. http://localhost:4937. */
        var baseUrl: String = "http://localhost:4937"
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun enabled(): Boolean = state.webFetchEnabled

    fun setEnabled(value: Boolean) {
        state.webFetchEnabled = value
    }

    fun baseUrl(): String {
        val url = state.baseUrl.trim().ifEmpty { "http://localhost:4937" }
        return url.removeSuffix("/")
    }

    fun setBaseUrl(value: String) {
        state.baseUrl = value.trim().ifEmpty { "http://localhost:4937" }
    }

    /** Stored per-project bearer token from PasswordSafe, or null. */
    fun token(project: Project): String? = ApiKeyStore.get(ApiKeyStore.webFetchTokenId(project))

    fun setToken(project: Project, value: String) {
        ApiKeyStore.set(ApiKeyStore.webFetchTokenId(project), value)
    }

    /** A URL is the only required setting; the bearer token is optional. */
    fun configured(project: Project): Boolean = baseUrl().isNotEmpty()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): WebFetchState =
            project.getService(WebFetchState::class.java)
    }
}
