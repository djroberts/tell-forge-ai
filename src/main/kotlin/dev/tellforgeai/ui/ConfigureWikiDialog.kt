package dev.tellforgeai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.tellforgeai.settings.ApiKeyStore
import dev.tellforgeai.settings.TellForgeAIWikiState
import dev.tellforgeai.tools.TelaWikiTool
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Per-project tela wiki configuration (Wiki button in the tool window):
 * base URL, personal access token (PasswordSafe, masked) and the one-time
 * write opt-in. "Test connection" verifies the token against
 * {url}/api/auth/me before anything is saved.
 */
class ConfigureWikiDialog(private val project: Project) : DialogWrapper(project) {

    private val state = TellForgeAIWikiState.getInstance(project)

    private val urlField = JBTextField(state.wikiUrl(), 40)
    private val tokenField = JBPasswordField().apply {
        columns = 40
        toolTipText = "tela personal access token (tela: Settings → API Keys). " +
            "Stored in the IDE PasswordSafe — never written to settings files. " +
            "Pre-filled with the stored token; paste a new value to replace it."
        // Show dots for the already-stored token so the user can see it is saved.
        text = ApiKeyStore.get(ApiKeyStore.WIKI_TOKEN_ID).orEmpty()
    }
    private val writeCheckBox = JBCheckBox(
        "Allow the agent to create and update wiki pages (Forge mode)",
        state.writeEnabled(),
    )
    private val testLabel = JBLabel(" ")

    init {
        title = "Configure Tela Wiki (this project)"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val help = JBLabel(
            "<html><body style='width:380px'>Base URL of your tela instance (e.g. " +
                "<code>https://telawiki.com</code>). Create a token in tela: <b>Settings → API Keys</b> " +
                "(scope <b>read</b> for search/read, <b>write</b> to let the agent push pages). " +
                "The raw <code>tela_pat_…</code> value is shown only once in tela — paste it here. " +
                "It is stored in the IDE PasswordSafe, not in project files.</body></html>",
        )
        val testButton = JButton("Test connection").apply {
            addActionListener { testConnection() }
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(help)
            .addLabeledComponent("Wiki URL:", urlField)
            .addLabeledComponent("Access token:", tokenField)
            .addComponentToRightColumn(writeCheckBox)
            .addComponent(testButton)
            .addComponent(testLabel)
            .panel

        return JPanel(BorderLayout()).apply { add(form, BorderLayout.CENTER) }
    }

    private fun tokenToUse(): String =
        String(tokenField.password).trim().ifEmpty {
            ApiKeyStore.get(ApiKeyStore.WIKI_TOKEN_ID).orEmpty()
        }

    private fun testConnection() {
        testLabel.text = "Testing…"
        Thread {
            // Never leave the label stuck on "Testing…" if anything throws.
            val result = runCatching {
                TelaWikiTool.testConnection(urlField.text.trim(), tokenToUse())
            }.getOrElse { "✗ ${it.message ?: it.javaClass.simpleName}" }
            // SwingUtilities.invokeLater bypasses IntelliJ's modality queue so the
            // label update always dispatches even while this modal dialog blocks
            // NON_MODAL runnables.
            SwingUtilities.invokeLater { testLabel.text = result }
        }.apply { isDaemon = true }.start()
    }

    override fun doOKAction() {
        state.setWikiUrl(urlField.text) // normalized in wikiUrl()
        val token = String(tokenField.password).trim()
        if (token.isNotEmpty()) {
            ApiKeyStore.set(ApiKeyStore.WIKI_TOKEN_ID, token)
        }
        // Writes can only be enabled with a working, saved configuration.
        state.setWriteEnabled(writeCheckBox.isSelected && state.configured())
        super.doOKAction()
    }

    companion object {
        fun show(project: Project) {
            ConfigureWikiDialog(project).show()
        }
    }
}
