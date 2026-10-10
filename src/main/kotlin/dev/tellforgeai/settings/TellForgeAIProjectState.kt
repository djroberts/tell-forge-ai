package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.project.Project
import dev.tellforgeai.model.ProviderProfile

/**
 * Per-project selection of the active provider profile.
 *
 * Persists the selection per project (workspace-local: profile ids are machine-local,
 * so roaming is disabled). If the stored profile id no longer exists (profile deleted),
 * falls back to the first available profile and persists that correction.
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAIProjectState", storages = [Storage("tellforgeai.workspace.xml", roamingType = RoamingType.DISABLED)])
class TellForgeAIProjectState(private val project: Project) : PersistentStateComponent<TellForgeAIProjectState.State> {

    class State {
        var selectedProfileId: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var selectedProfileId: String?
        get() = state.selectedProfileId
        set(value) {
            state.selectedProfileId = value
        }

    /**
     * The selected profile, falling back to the first available profile when the
     * persisted id no longer resolves (e.g. the profile was deleted). The fallback
     * is persisted so subsequent calls are stable.
     */
    fun selectedProfile(): ProviderProfile? {
        val settings = TellForgeAISettings.getInstance()
        val selected = settings.find(state.selectedProfileId)
        if (selected != null) return selected

        val fallback = settings.profiles().firstOrNull()
        if (fallback?.id != state.selectedProfileId) {
            state.selectedProfileId = fallback?.id
        }
        return fallback
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): TellForgeAIProjectState =
            project.getService(TellForgeAIProjectState::class.java)
    }
}
