package dev.tellforgeai.tools

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * Extension point `dev.tellforgeai.integration`: lets other plugins add a
 * tab to the per-project "Configure Integrations" dialog, next to the
 * built-in Wiki / Notion / Instructions / Sentry tabs.
 *
 * Contract:
 *  - [createPanel] is called on the EDT when the dialog opens; pre-fill the
 *    form from persisted state (typically a project-level
 *    PersistentStateComponent, with secrets in PasswordSafe via ApiKeyStore —
 *    never in settings XML).
 *  - [apply] is called only when the user presses OK. Persist the form
 *    here; throwing aborts nothing but is logged and the user's OK still
 *    closes the dialog.
 *  - Whether the integration's tools are offered is the provider's own
 *    concern (enable + configured checks in its tool specs, following the
 *    same enabled()/configured() pattern as the built-in states).
 */
interface IntegrationProvider {
    companion object {
        val EP_NAME: ExtensionPointName<IntegrationProvider> =
            ExtensionPointName.create("dev.tellforgeai.integration")
    }

    /** Tab label, e.g. "CMS". */
    val title: String

    /** One-line plain-text explanation, shown as the tab tooltip. */
    val info: String

    /** Build the tab's form, pre-filled from persisted state. Called on EDT. */
    fun createPanel(project: Project): JComponent

    /** Persist the form; only called on OK. */
    fun apply(project: Project)
}
