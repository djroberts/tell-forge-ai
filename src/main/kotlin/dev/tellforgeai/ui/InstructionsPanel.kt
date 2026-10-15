package dev.tellforgeai.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.tellforgeai.llm.InstructionsRepoService
import dev.tellforgeai.settings.InstructionsRepoState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ActionListener
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The "Instructions" tab in the TellForgeAI tool window: a live editor for the
 * canonical `INSTRUCTIONS.md` of the configured instructions repo.
 *
 * Design decisions:
 *  - Plain Swing editor pane (markdown wraps, no extra editor dependency);
 *    heavier validation stays available via "Open in IDE" which opens the file
 *    from the clone in the normal editor with full diffs/undo/local history.
 *  - Saving goes through the same preview → approve (diff) → commit+push
 *    machinery the agent uses, so user edits are reviewed and versioned
 *    exactly like agent edits, and the write opt-in is honoured in one place.
 *  - The status line is re-evaluated on a 5s Swing timer (cheap: no git, just
 *    memory + a stat of the clone dir) so it stays current without any git.
 */
class InstructionsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val LOG = Logger.getInstance(InstructionsPanel::class.java)

    private val state = InstructionsRepoState.getInstance(project)
    private val service get() = InstructionsRepoService.getInstance(project)

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(font.size2D - 1f)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val openInIdeButton = JButton("Open in editor", AllIcons.Actions.MenuOpen).apply {
        toolTipText = "Open the instructions file from the local clone in the IDE editor"
    }
    private val syncButton = JButton("Sync now", AllIcons.Actions.Refresh).apply {
        toolTipText = "Force a clone/fetch of the instructions repo"
    }
    private val configButton = JButton("Configure…", AllIcons.General.Settings).apply {
        toolTipText = "Configure the instructions repo (URL, branch, write opt-in)"
    }
    private val editor = JEditorPane().apply {
        contentType = "text/plain"
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        isEditable = false
    }
    private val dirty = JLabel(" ").apply {
        font = font.deriveFont(font.size2D - 1f)
        foreground = com.intellij.ui.JBColor(0x808080, 0x9DA3A8)
    }
    private val saveButton = JButton("Save & push…", AllIcons.Actions.Commit).apply {
        toolTipText = "Commit and push the edited INSTRUCTIONS.md (shows the diff first)"
        isEnabled = false
    }
    private val revertButton = JButton("Revert", AllIcons.Actions.Rollback).apply {
        toolTipText = "Discard edits back to the last synced content"
        isEnabled = false
    }

    /** Content shown in the editor when it was last (re)loaded from the clone. */
    private var loadedText: String = ""
    /**suppresses the dirty-marker while a programmatic setText runs. */
    private var loading = false

    init {
        border = JBUI.Borders.empty(8)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = markDirty()
            override fun removeUpdate(e: DocumentEvent) = markDirty()
            override fun changedUpdate(e: DocumentEvent) = markDirty()
        })

        // Toolbar: left = file identity, right = actions.
        val left = JPanel().apply {
            isOpaque = false
            add(JLabel(InstructionsRepoService.CANONICAL_FILE).apply { font = font.deriveFont(Font.BOLD) })
        }
        val right = JPanel().apply {
            isOpaque = false
            add(openInIdeButton); add(syncButton); add(configButton)
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
        val statusRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(statusLabel, BorderLayout.CENTER)
            add(dirty, BorderLayout.EAST)
        }
        val buttonsRow = JPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(saveButton); add(revertButton)
        }
        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buttonsRow, BorderLayout.WEST)
            add(statusRow, BorderLayout.SOUTH)
        }
        val scroll = JScrollPane(editor).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(480, 300)
        }
        add(toolbar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)

        openInIdeButton.addActionListener { openInIde() }
        syncButton.addActionListener { sync() }
        configButton.addActionListener { ConfigureIntegrationsDialog.show(project) }
        saveButton.addActionListener { save() }
        revertButton.addActionListener { revert() }

        // Cheap, git-free poll of the sync status so it stays current even when
        // the agent or the config dialog synced in the background.
        Timer(5000, ActionListener { refreshStatus() }).apply { isRepeats = true }.start()
        refresh()
    }

    // ------------------------------------------------------------------
    // Refresh / status
    // ------------------------------------------------------------------

    /** Reloads the editor content from the clone and refreshes the status line. */
    fun refresh() {
        // Skip if the user has unsaved edits — don't wipe their work silently.
        val canReload = !isDirty() || loadedText.isEmpty()
        val content = service.readCanonicalFile()
        loadedText = content ?: ""
        if (canReload) {
            loading = true
            try {
                editor.text = loadedText
                editor.isEditable = content != null && state.enabled()
            } finally {
                loading = false
                updateDirtyMarker()
            }
        }
        syncButton.isEnabled = !service.isSyncing()
        openInIdeButton.isEnabled = service.currentCloneDir()?.let {
            val dir = it; java.io.File(dir, InstructionsRepoService.CANONICAL_FILE).isFile
        } ?: false
        refreshStatus()
    }

    private fun refreshStatus() {
        statusLabel.text = when {
            !state.enabled() -> "Not enabled — configure the instructions repo to start."
            else -> service.status()
        }
        saveButton.isEnabled = isDirty() && state.enabled() && state.writeEnabled()
        revertButton.isEnabled = isDirty()
        dirty.text = when {
            !state.writeEnabled() && isDirty() -> "read-only (write opt-in off)"
            isDirty() -> "● unsaved"
            else -> ""
        }
    }

    private fun isDirty(): Boolean = !loading && editor.text != loadedText

    private fun markDirty() {
        if (loading) return
        refreshStatus()
    }

    private fun updateDirtyMarker() {
        editor.isEnabled = true
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun sync() {
        syncButton.isEnabled = false
        statusLabel.text = "syncing…"
        AppExecutorUtil.getAppExecutorService().submit {
            val result = try {
                val snap = service.syncNow()
                if (snap.error.isNotEmpty()) snap.error else null
            } catch (e: Exception) {
                LOG.warn("manual instructions sync failed", e)
                e.message ?: e.javaClass.simpleName
            }
            val err = result
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeLater({
                    if (err != null) {
                        statusLabel.text = "error: $err"
                        Messages.showErrorDialog(this@InstructionsPanel, err, "Instructions Sync")
                    } else {
                        statusLabel.text = service.status()
                        refresh()
                    }
                    syncButton.isEnabled = true
                })
        }
    }

    private fun save() {
        val newContent = editor.text
        if (newContent == loadedText) return
        saveButton.isEnabled = false
        try {
            val diff = service.previewChange(InstructionsRepoService.CANONICAL_FILE, newContent)
            if (diff == null) {
                statusLabel.text = "error: invalid content root"
                return
            }
            if (diff == "(no changes)") {
                statusLabel.text = "no changes to push"
                loadedText = newContent
                refreshStatus()
                return
            }
            val approved = Messages.showOkCancelDialog(
                this@InstructionsPanel,
                "Commit and push this change to the instructions repo?\n\n$diff",
                "Instructions: confirm commit & push", "Push", "Cancel", Messages.getQuestionIcon(),
            ) == Messages.OK
            if (!approved) {
                service.discardChange(InstructionsRepoService.CANONICAL_FILE)
                statusLabel.text = "not pushed — change discarded"
                return
            }
            try {
                statusLabel.text = service.commitAndPush(InstructionsRepoService.CANONICAL_FILE, "edit INSTRUCTIONS.md from UI")
                loadedText = newContent
            } catch (e: Exception) {
                statusLabel.text = "error: ${e.message ?: e.javaClass.simpleName}"
                Messages.showErrorDialog(
                    this@InstructionsPanel,
                    "${e.message ?: e.javaClass.simpleName}\n\nThe change was committed locally, not pushed.",
                    "Instructions push failed",
                )
            }
        } catch (e: Exception) {
            statusLabel.text = "error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            saveButton.isEnabled = true
            refreshStatus()
        }
    }

    private fun revert() {
        loading = true
        try {
            editor.text = loadedText
        } finally {
            loading = false
            refreshStatus()
        }
    }

    /** Opens the canonical file from the clone in the IDE editor (read-only view). */
    private fun openInIde() {
        val dir = service.currentCloneDir() ?: return
        val spec = state.spec() ?: return
        val root = if (spec.subpath.isBlank()) dir else java.io.File(dir, spec.subpath)
        val f = java.io.File(root, InstructionsRepoService.CANONICAL_FILE)
        if (!f.isFile) {
            statusLabel.text = "no ${InstructionsRepoService.CANONICAL_FILE} in the clone yet — sync first"
            return
        }
        try {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(f)
            if (vf != null) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            }
        } catch (e: Exception) {
            LOG.warn("failed to open instructions file in IDE", e)
        }
    }
}
