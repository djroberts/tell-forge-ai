package dev.tellforgeai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import dev.tellforgeai.settings.ApiKeyStore
import dev.tellforgeai.settings.NotionState
import dev.tellforgeai.settings.TellForgeAIWikiState
import dev.tellforgeai.settings.WikiWriteAction
import dev.tellforgeai.tools.NotionTool
import dev.tellforgeai.tools.TelaWikiTool
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

/**
 * Per-project configuration for the wiki/Notion agent tools, opened via the
 * Wiki button in the tool window. Two tabs:
 *
 *  - Tela wiki: enable toggle, instance URL, per-project access token
 *    (stored in PasswordSafe under a per-project key — dots in the field mean
 *    a token IS saved for THIS project), connection test, write opt-in with
 *    per-action "Ask permission" toggles (add / update / delete page).
 *  - Notion: enable toggle, internal-integration token, connection test,
 *    write opt-in with the same per-action toggles.
 *
 * Unticking an enable toggle removes the wiki_* and notion_* tools from the
 * model's tool list for this project entirely.
 */
class ConfigureWikiDialog(private val project: Project) : DialogWrapper(project) {

    private val wikiState = TellForgeAIWikiState.getInstance(project)
    private val notionState = NotionState.getInstance(project)

    // ---- Tela wiki tab
    private val wikiEnable = JBCheckBox("Enable Tela wiki tools for this project", wikiState.enabled())
    private val wikiUrlField = JTextField(wikiState.wikiUrl(), 34)
    private val wikiTokenField = JBPasswordField().apply {
        // Pre-fill from THIS project's PasswordSafe entry (or empty when none).
        wikiState.token(project)?.let { text = it }
    }
    private val wikiWrite = JBCheckBox(
        "Allow the agent to create, update and delete wiki pages",
        wikiState.writeEnabled(),
    )
    private val wikiAskCreate = JBCheckBox("Ask permission to add page", !wikiState.skipApproval(WikiWriteAction.CREATE))
    private val wikiAskUpdate = JBCheckBox("Ask permission to update page", !wikiState.skipApproval(WikiWriteAction.UPDATE))
    private val wikiAskDelete = JBCheckBox("Ask permission to delete page", !wikiState.skipApproval(WikiWriteAction.DELETE))
    private val wikiTestLabel = JLabel(" ")

    // ---- Notion tab
    private val notionEnable = JBCheckBox("Enable Notion tools for this project", notionState.enabled())
    private val notionTokenField = JBPasswordField().apply {
        notionState.token(project)?.let { text = it }
    }
    private val notionWrite = JBCheckBox(
        "Allow the agent to create, update and delete Notion pages",
        notionState.writeEnabled(),
    )
    private val notionAskCreate = JBCheckBox("Ask permission to add page", !notionState.skipApproval(WikiWriteAction.CREATE))
    private val notionAskUpdate = JBCheckBox("Ask permission to update page", !notionState.skipApproval(WikiWriteAction.UPDATE))
    private val notionAskDelete = JBCheckBox("Ask permission to delete page", !notionState.skipApproval(WikiWriteAction.DELETE))
    private val notionTestLabel = JLabel(" ")

    init {
        title = "Configure Tela Wiki"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val wikiTab = FormBuilder.createFormBuilder()
            .addComponent(wikiEnable, 4)
            .addLabeledComponent("Wiki URL:", wikiUrlField)
            .addLabeledComponent("Access token:", wikiTokenField)
            .addComponent(wikiTestButton(), 4)
            .addComponent(wikiTestLabel, 4)
            .addComponent(wikiWrite, 4)
            .addComponent(wikiAskCreate, 4)
            .addComponent(wikiAskUpdate, 4)
            .addComponent(wikiAskDelete, 4)
            .panel

        val notionTab = FormBuilder.createFormBuilder()
            .addComponent(notionEnable, 4)
            .addLabeledComponent("Integration token:", notionTokenField)
            .addComponent(notionTestButton(), 4)
            .addComponent(notionTestLabel, 4)
            .addComponent(notionWrite, 4)
            .addComponent(notionAskCreate, 4)
            .addComponent(notionAskUpdate, 4)
            .addComponent(notionAskDelete, 4)
            .panel

        val tabs = JBTabbedPane()
        tabs.addTab("Tela wiki", wikiTab)
        tabs.addTab("Notion", notionTab)
        return tabs
    }

    private fun wikiTestButton() = JButton("Test connection").apply {
        addActionListener {
            val token = String(wikiTokenField.password).trim()
            wikiTestLabel.text = TelaWikiTool.testConnection(wikiUrlField.text.trim(), token)
        }
    }

    private fun notionTestButton() = JButton("Test connection").apply {
        addActionListener {
            val token = String(notionTokenField.password).trim()
            notionTestLabel.text = NotionTool.testConnection(token)
        }
    }

    override fun doOKAction() {
        // ---- Tela wiki
        wikiState.setEnabled(wikiEnable.isSelected)
        wikiState.setWikiUrl(wikiUrlField.text)
        val wikiToken = String(wikiTokenField.password).trim()
        if (wikiToken.isEmpty()) {
            // Empty field = clear this project's stored token (dots only appear
            // when a token was actually saved; a cleared field stays cleared).
            wikiState.setToken(project, "")
        } else {
            wikiState.setToken(project, wikiToken)
        }
        // Writes can only be enabled with a working, saved configuration.
        wikiState.setWriteEnabled(wikiWrite.isSelected && wikiEnable.isSelected && wikiState.configured(project))
        wikiState.setSkipApproval(WikiWriteAction.CREATE, !wikiAskCreate.isSelected)
        wikiState.setSkipApproval(WikiWriteAction.UPDATE, !wikiAskUpdate.isSelected)
        wikiState.setSkipApproval(WikiWriteAction.DELETE, !wikiAskDelete.isSelected)

        // ---- Notion
        notionState.setEnabled(notionEnable.isSelected)
        val notionToken = String(notionTokenField.password).trim()
        if (notionToken.isEmpty()) {
            notionState.setToken(project, "")
        } else {
            notionState.setToken(project, notionToken)
        }
        notionState.setWriteEnabled(notionWrite.isSelected && notionEnable.isSelected && notionState.configured(project))
        notionState.setSkipApproval(WikiWriteAction.CREATE, !notionAskCreate.isSelected)
        notionState.setSkipApproval(WikiWriteAction.UPDATE, !notionAskUpdate.isSelected)
        notionState.setSkipApproval(WikiWriteAction.DELETE, !notionAskDelete.isSelected)

        super.doOKAction()
    }

    companion object {
        fun show(project: Project) {
            ConfigureWikiDialog(project).show()
        }
    }
}
