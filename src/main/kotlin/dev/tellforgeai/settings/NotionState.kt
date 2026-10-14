package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project Notion integration settings (tools -> NotionTool).
 *
 * Nothing secret roams with the project; the internal-integration token is
 * stored separately in PasswordSafe under a PER-PROJECT key
 * (see [ApiKeyStore.notionTokenId]). [notionEnabled] is the master on/off
 * switch: when false the notion_* tools are not offered to the model at all.
 * [notionWriteEnabled] is the one-time opt-in that lets the model mutate
 * Notion pages in Forge mode; it is only settable from the Configure Tela
 * Wiki dialog after a successful connection test.
 *
 * Approval per action ([notionSkipCreate], [notionSkipUpdate],
 * [notionSkipDelete], see [WikiWriteAction]): true = the per-write
 * confirmation dialog is skipped for that action; false (the default) = each
 * mutating call asks via a dialog ("Ask permission to add/update/delete
 * page" checkboxes in the Configure Tela Wiki dialog).
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAINotionState", storages = [Storage("tellforgeai.notion.xml")])
class NotionState : PersistentStateComponent<NotionState.State> {

    class State {
        /** Master switch: expose the notion_* tools to the model for this project. */
        var notionEnabled: Boolean = false

        /** One-time opt-in: allow mutating notion_* tools in Forge mode. */
        var notionWriteEnabled: Boolean = false

        // ---- Per-action approval (true = skip the confirmation dialog) ----
        var skipApprovalCreate: Boolean = false
        var skipApprovalUpdate: Boolean = false
        var skipApprovalDelete: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun enabled(): Boolean = state.notionEnabled

    fun setEnabled(value: Boolean) {
        state.notionEnabled = value
    }

    fun writeEnabled(): Boolean = state.notionWriteEnabled

    fun setWriteEnabled(value: Boolean) {
        state.notionWriteEnabled = value
    }

    /** True when the confirmation dialog should be skipped for [action]. */
    fun skipApproval(action: WikiWriteAction): Boolean = when (action) {
        WikiWriteAction.CREATE -> state.skipApprovalCreate
        WikiWriteAction.UPDATE -> state.skipApprovalUpdate
        WikiWriteAction.DELETE -> state.skipApprovalDelete
    }

    fun setSkipApproval(action: WikiWriteAction, skip: Boolean) {
        when (action) {
            WikiWriteAction.CREATE -> state.skipApprovalCreate = skip
            WikiWriteAction.UPDATE -> state.skipApprovalUpdate = skip
            WikiWriteAction.DELETE -> state.skipApprovalDelete = skip
        }
    }

    /** Stored per-project token from PasswordSafe, or null. */
    fun token(project: Project): String? = ApiKeyStore.get(ApiKeyStore.notionTokenId(project))

    fun setToken(project: Project, value: String) {
        ApiKeyStore.set(ApiKeyStore.notionTokenId(project), value)
    }

    fun configured(project: Project): Boolean = !token(project).isNullOrBlank()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): NotionState =
            project.getService(NotionState::class.java)
    }
}
