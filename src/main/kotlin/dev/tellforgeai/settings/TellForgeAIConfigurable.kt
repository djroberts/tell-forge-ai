package dev.tellforgeai.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Settings page: Tools -> TellForgeAI Providers.
 * Manage named OpenAI-compatible profiles (name, URL, key, model).
 */
class TellForgeAIConfigurable(private val project: com.intellij.openapi.project.Project) : Configurable {

    private val nameField = JBTextField()
    private val urlField = JBTextField()
    private val keyField = JPasswordField()
    private val modelField = JBTextField()
    private val planModelField = JBTextField()
    private val reasoningEffortField = JBTextField()
    private val contextWindowField = JBTextField()
    private val timeoutField = JBTextField()
    /** Longest-side cap for pasted images: 256/512/1024/2048/Original. */
    private val pastedImageSizeCombo = JComboBox(arrayOf("256", "512", "1024", "2048", "Original"))
    /**
     * Per-project chat-store GC cap in MB. Blank/0 = disabled; the default
     * placeholder shows the shipped default (50).
     */
    private val chatGcMaxMbField = JBTextField()
    private val aiPricingLookupCheckBox = JCheckBox("Use the selected LLM for a billable pricing lookup (cached for 24 hours)")
    /** API protocol selector (OpenAI-compatible / OpenAI Responses / Gemini preset). */
    private val protocolCombo = JComboBox(ApiProtocol.entries.map { it.displayName }.toTypedArray())
    /**
     * Dropdown of model ids fetched from `{baseUrl}{modelsPath}`. This is a
     * sibling of, not a replacement for, the free-text model field — picking an
     * entry writes it back into [modelField], which stays the single source of
     * truth (a typed-over / truncated-from-list model must still be usable).
     */
    private val modelCombo = JComboBox<String>()
    private val fetchModelsButton = JButton("\u21c5 Fetch")

    private val listModel = DefaultListModel<ProviderProfile>()
    private val list = JBList(listModel)
    private val testButton = JButton("Test connection")
    private val testClient = dev.tellforgeai.llm.LlmClient()

    /** OpenRouter-specific form fields, shown/hidden based on the selected protocol. */
    private val openRouterForm = OpenRouterForm { modified = true }

    private lateinit var formFields: List<JComponent>

    private var modified = false
    /**
     * Ids seen at some point during this settings session (init/reset/apply)
     * so apply() can tell "user deleted this profile deliberately" apart from
     * "the whole settings file was reset underneath us".
     */
    private val deletedThisSession = mutableSetOf<String>()
    /** Orphaned ids deferred for possible re-adopt rather than hard deletion. */
    private val knownOrphanCandidates = mutableSetOf<String>()
    /** Profile ids whose key the user explicitly cleared this session (blank field). */
    private val clearedThisSession = mutableSetOf<String>()
    /**
     * Index of the profile currently shown in the form. Track it explicitly —
     * Swing often fires selection events during list mutation (add/delete),
     * and `getSelectedIndex()` is unreliable inside those callbacks.
     */
    private var currentEditorIndex = -1

    init {
        deletedThisSession.addAll(TellForgeAISettings.getInstance().profiles().map { it.id })
        TellForgeAISettings.getInstance().profiles()
            .sortedBy { it.name.lowercase() }
            .forEach { p ->
                listModel.addElement(p.copy(apiKey = ApiKeyStore.get(p.id).orEmpty()))
            }
        adoptOrphansIfAny()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? ProviderProfile)?.let { "${it.name}  ·  ${it.model}" } ?: "",
                index, selected, focused,
            )
        }
        list.addListSelectionListener { onSelectionChanged() }
        listOf(nameField, urlField, modelField, planModelField, reasoningEffortField, contextWindowField, timeoutField).forEach { field ->
            field.document.addDocumentListener(SimpleDocListener { if (!suppressFieldListeners) modified = true })
        }
        keyField.document.addDocumentListener(
            SimpleDocListener {
                if (suppressFieldListeners) return@SimpleDocListener
                modified = true
                // Only a deliberate user edit to blank counts as a key deletion;
                // programmatic clears (reset/reload) must not wipe PasswordSafe.
                if (keyField.password.isEmpty() && currentEditorIndex in 0 until listModel.size) {
                    clearedThisSession.add(listModel[currentEditorIndex].id)
                } else if (currentEditorIndex in 0 until listModel.size) {
                    clearedThisSession.remove(listModel[currentEditorIndex].id)
                }
            }
        )
        pastedImageSizeCombo.addActionListener { if (!suppressFieldListeners) modified = true }
        aiPricingLookupCheckBox.addActionListener { if (!suppressFieldListeners) modified = true }
        // API protocol: switching swaps the base-url default and toggles the
        // OpenRouter-specific options panel but never discards the user's URL.
        protocolCombo.addActionListener {
            if (suppressFieldListeners) return@addActionListener
            modified = true
            val proto = ApiProtocol.entries.firstOrNull { it.displayName == protocolCombo.selectedItem }
            urlField.emptyText.text = proto?.defaultBaseUrl.orEmpty()
            openRouterForm.root.isVisible = proto == ApiProtocol.OPENROUTER
        }
        fetchModelsButton.addActionListener { onFetchModels() }
        modelCombo.isEditable = false
        // Picking a suggestion writes it into modelField — the free-text field
        // stays the source of truth, so users can still override.
        modelCombo.addActionListener {
            val sel = modelCombo.selectedItem as? String
            if (sel != null && sel != TRUNCATION_NOTICE) {
                if (suppressModelComboAction) {
                    suppressModelComboAction = false
                } else if (sel != modelField.text.trim()) {
                    modelField.text = sel
                    modified = true
                }
            }
        }
    }

    /**
     * Guards against the programmatic setSelectedItem during popup refresh.
     * When true, model-combo action events are ignored so that programmatically
     * populated suggestions (including the truncation notice) are never written
     * into [modelField].
     */
    private var suppressModelComboAction = false

    /**
     * Suppresses the form's field-change listeners while fields are populated
     * programmatically (selection switch, reset, global-field load). Without
     * this, merely *viewing* a profile fired every listener and flipped
     * [modified] — making Apply/OK always enabled and triggering spurious
     * "unsaved changes" prompts with zero user edits.
     */
    private var suppressFieldListeners = false

    /** Runs [block] with field listeners suppressed so loading state never dirties the form. */
    private inline fun withoutFieldListeners(block: () -> Unit) {
        suppressFieldListeners = true
        try {
            block()
        } finally {
            suppressFieldListeners = false
        }
    }

    /** Null-safe element access on [DefaultListModel] (no built-in getOrNull). */
    private fun DefaultListModel<ProviderProfile>.getOrNull(index: Int): ProviderProfile? =
        if (index in 0 until size) this[index] else null

    /** Sentinel combo item shown when /models returned ≥200 entries; never written into [modelField]. */
    private companion object {
        const val TRUNCATION_NOTICE = "\u2026 (list truncated at 200 — type in the field above)"
    }

    /** Fetch /models (path per profile's protocol) and fill the suggestion combo. */
    private fun onFetchModels() {
        if (list.selectedIndex < 0) return
        saveEditsIntoSelected()
        val profile = list.selectedValue ?: return
        // Capture the profile id: if the user switches to another profile while
        // the request is in flight, the reply must not fill the form with this
        // (now hidden) profile's model list.
        val requestedProfileId = profile.id
        fetchModelsButton.isEnabled = false
        fetchModelsButton.text = "Fetching\u2026"
        testClient.fetchModelsWithApiKey(profile, profile.apiKey) { ids ->
            javax.swing.SwingUtilities.invokeLater {
                if (list.selectedValue?.id != requestedProfileId) {
                    // Stale reply — the form now shows a different profile.
                    // Restore the button but drop the results.
                    fetchModelsButton.isEnabled = true
                    fetchModelsButton.text = "\u21c5 Fetch"
                    return@invokeLater
                }
                fetchModelsButton.isEnabled = true
                fetchModelsButton.text = "\u21c5 Fetch"
                suppressModelComboAction = true
                modelCombo.removeAllItems()
                ids.forEach { modelCombo.addItem(it) }
                if (ids.size >= 200) modelCombo.addItem(TRUNCATION_NOTICE)
                suppressModelComboAction = false
                if (ids.isEmpty()) {
                    // Empty result doesn't block the user — the field stays editable.
                    Messages.showInfoMessage(
                        list,
                        "No models returned (or endpoint has no model list)." +
                            "\nThe Model field remains editable; type the model id manually.",
                        "TellForgeAI — Model suggestions",
                    )
                }
            }
        }
    }

    private class SimpleDocListener(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    }

    override fun getDisplayName(): String = "TellForgeAI Providers"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(8, 0))

        val listPanel = JPanel(BorderLayout(4, 4))
        listPanel.add(JBScrollPane(list), BorderLayout.CENTER)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val add = JButton("Add")
        val delete = JButton("Delete")
        add.addActionListener { onAdd() }
        delete.addActionListener { onDelete() }
        testButton.toolTipText = "Verify the base URL / API key by requesting /models"
        testButton.addActionListener { onTestConnection() }
        buttons.add(add)
        buttons.add(delete)
        listPanel.add(buttons, BorderLayout.SOUTH)
        listPanel.preferredSize = Dimension(260, 0)

        val form = JPanel()
        form.layout = BoxLayout(form, BoxLayout.Y_AXIS)
        form.add(labeled("Profile name", nameField))
        form.add(labeled("API protocol", protocolCombo))
        form.add(labeledWithAction("Base URL (OpenAI-compatible, e.g. https://api.openai.com/v1)", urlField, testButton))
        form.add(labeled("API key (stored in the IDE PasswordSafe, not in settings files)", keyField))
        form.add(labeledWithAction("Model (Forge mode / default; \u21c5 fills suggestions — remains freely editable)", modelField, fetchModelsButton))
        modelCombo.maximumSize = Dimension(Int.MAX_VALUE, modelCombo.preferredSize.height)
        form.add(labeled("Model suggestions (from /models; picking one fills the field above, overridable)", modelCombo))
        form.add(labeled("Model for Foretell mode (optional, blank = same)", planModelField))
        form.add(labeled("Reasoning effort (optional, e.g. low/medium/high; sent as reasoning_effort)", reasoningEffortField))
        form.add(labeled("Context window (tokens; auto-detected on Test connection, manual override)", contextWindowField))
        // OpenRouter-specific options (hidden for other protocols)
        form.add(openRouterForm.root)
        openRouterForm.root.isVisible = false
        form.add(Box.createVerticalGlue())
        formFields = listOf(nameField, urlField, keyField, modelField, planModelField, reasoningEffortField, testButton, protocolCombo, fetchModelsButton, modelCombo)

        root.add(listPanel, BorderLayout.WEST)
        val center = JPanel()
        center.layout = BoxLayout(center, BoxLayout.Y_AXIS)
        center.add(form)
        center.add(JLabel(" "))
        center.add(JSeparator())
        center.add(JLabel(" "))
        center.add(labeled("Command timeout in seconds (5–600, applies to terminal tool; global)", timeoutField))
        center.add(aiPricingLookupCheckBox)
        center.add(labeled("Max pasted image size in px (longest side; Original = no resize; global)", pastedImageSizeCombo))
        chatGcMaxMbField.emptyText.text = "50 (default) — 0 disables chat GC"
        center.add(
            labeled(
                "Chat storage GC cap in MB for THIS project — oldest chats are deleted when the total exceeds it (0 = off)",
                chatGcMaxMbField,
            )
        )
        aiPricingLookupCheckBox.addActionListener { modified = true }
        root.add(center, BorderLayout.CENTER)
        // Initial population is programmatic — must not flip [modified] or
        // register cleared-key candidates before the user touches anything.
        suppressFieldListeners = true
        try {
            loadGlobalFields()
            if (!listModel.isEmpty) list.selectedIndex = 0
        } finally {
            suppressFieldListeners = false
        }
        if (listModel.isEmpty) setFormEnabled(false)
        return root
    }

    /** Populate global (non-profile) fields from application settings. */
    private fun loadGlobalFields() {
        val s = TellForgeAISettings.getInstance()
        timeoutField.text = s.commandTimeoutSeconds().toString()
        aiPricingLookupCheckBox.isSelected = s.aiPricingLookupEnabled()
        pastedImageSizeCombo.selectedItem =
            s.pastedImageMaxSize().takeIf { it > 0 }?.toString() ?: "Original"
        // Per-project GC cap: "" renders as the placeholder (default shown in emptyText).
        val projectCap = TellForgeAIProjectState.getInstance(project).chatGcMaxMb()
        chatGcMaxMbField.text = projectCap.toString()
    }

    private fun setFormEnabled(enabled: Boolean) {
        formFields.forEach { it.isEnabled = enabled }
    }

    private fun labeled(label: String, field: JComponent): JComponent {
        val panel = JPanel(BorderLayout(4, 2))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Int.MAX_VALUE, 60)
        val l = JLabel(label)
        l.alignmentX = 0.0f
        panel.add(l, BorderLayout.NORTH)
        panel.add(field, BorderLayout.CENTER)
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    /** Like [labeled], but places [action] inline to the right of [field]. */
    private fun labeledWithAction(label: String, field: JComponent, action: JComponent): JComponent {
        val panel = JPanel(BorderLayout(4, 2))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Int.MAX_VALUE, 60)
        val l = JLabel(label)
        l.alignmentX = 0.0f
        panel.add(l, BorderLayout.NORTH)
        val fieldRow = JPanel(BorderLayout(4, 0))
        fieldRow.isOpaque = false
        fieldRow.add(field, BorderLayout.CENTER)
        fieldRow.add(action, BorderLayout.EAST)
        panel.add(fieldRow, BorderLayout.CENTER)
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    /**
     * Id of the profile currently shown in the form (mirrors [currentEditorIndex]
     * but survives index shifts). Used to reset per-profile UI state — the model
     * suggestions combo — when the user switches to a different profile.
     */
    private var shownProfileId: String? = null

    private fun onSelectionChanged() {
        val idx = list.selectedIndex
        // Data-loss fix: when switching profiles, first flush any unsaved edits
        // (name, URL, API key, model…) back into the *previously selected*
        // profile. Without this, edits pending at the moment of the switch were
        // silently discarded, and a later Apply then persisted the stale/blank
        // values into PasswordSafe — wiping freshly-entered API keys.
        if (currentEditorIndex in 0 until listModel.size && currentEditorIndex != idx) {
            // Flush the *previous* profile's pending edits — by id (indices may
            // already have shifted in the event that triggered this callback).
            flushEditsInto(listModel.getOrNull(currentEditorIndex)?.id)
        }
        currentEditorIndex = idx
        val p = list.selectedValue ?: return
        // Per-profile UI state reset: the /models suggestions combo is a cache of
        // the last fetch for whichever profile was previously shown. Leaving it
        // populated after a switch showed the old provider's models against the
        // new one — a user picking an entry would silently save the wrong
        // provider's model id into the new profile. Clear on any id change.
        if (p.id != shownProfileId) {
            shownProfileId = p.id
            suppressModelComboAction = true
            modelCombo.removeAllItems()
            suppressModelComboAction = false
        }
        // Populate the fields programmatically — suppress the doc/checkbox/combo
        // listeners so merely *viewing* a profile doesn't flip [modified].
        suppressFieldListeners = true
        try {
            nameField.text = p.name
            protocolCombo.selectedItem = p.apiProtocol.displayName
            urlField.text = p.baseUrl
            // listModel is the source of truth for this settings session, including
            // an intentionally-cleared or newly-entered key that has not been applied.
            keyField.text = p.apiKey
            modelField.text = p.model
            planModelField.text = p.planModel.orEmpty()
            reasoningEffortField.text = p.reasoningEffort.orEmpty()
            contextWindowField.text = p.contextWindow.toString()
            openRouterForm.load(p)
            openRouterForm.root.isVisible = p.isOpenRouter
        } finally {
            suppressFieldListeners = false
        }
    }

    private fun onAdd() {
        // Flush pending edits into the previously selected profile before the
        // selection jump (below) would otherwise discard them — by id.
        if (currentEditorIndex in 0 until listModel.size) {
            flushEditsInto(listModel.getOrNull(currentEditorIndex)?.id)
        }
        val p = ProviderProfile(
            id = UUID.randomUUID().toString(),
            name = "New provider",
            // Seed the new profile with the protocol currently selected in the
            // form (and its default base URL) — previously this hardcoded the
            // OpenAI-compatible default even when the user had picked Gemini or
            // OpenRouter, so the new profile landed on the wrong endpoint.
            apiProtocol = ApiProtocol.fromDisplayName(protocolCombo.selectedItem?.toString()),
            baseUrl = ApiProtocol.fromDisplayName(protocolCombo.selectedItem?.toString())?.defaultBaseUrl
                ?: ApiProtocol.OPENAI_COMPATIBLE.defaultBaseUrl,
            apiKey = "",
            model = "",
            planModel = null,
        )
        // Insert at the sorted position (by name) so the list stays ordered.
        val insertAt = listModel.elements().toList()
            .indexOfFirst { it.name.lowercase() > p.name.lowercase() }
            .let { if (it >= 0) it else listModel.size }
        listModel.add(insertAt, p)
        list.selectedIndex = insertAt
        currentEditorIndex = insertAt
        // A brand-new profile has no fetched models — drop any suggestions left
        // over from the previously shown profile. Cleared here defensively (not
        // only via the selection callback) so the combo is guaranteed empty
        // before the user sees the fresh form.
        shownProfileId = null
        suppressModelComboAction = true
        modelCombo.removeAllItems()
        suppressModelComboAction = false
        // The selection jump above fires onSelectionChanged, which loads the new
        // (empty) profile into the fields — that programmatic load must not flip
        // [modified]; onAdd sets it explicitly below.
        suppressFieldListeners = true
        try {
            setFormEnabled(true)
        } finally {
            suppressFieldListeners = false
        }
        modified = true
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun onDelete() {
        val idx = list.selectedIndex
        if (idx < 0) return
        deletedThisSession.add(listModel[idx].id)
        listModel.remove(idx)
        modified = true
        currentEditorIndex = if (!listModel.isEmpty) idx.coerceAtMost(listModel.size - 1) else -1
        if (!listModel.isEmpty) list.selectedIndex = currentEditorIndex
        else {
            // No selection callback fires when the list empties — clear per-profile
            // state (model suggestions) and disable the form explicitly.
            shownProfileId = null
            suppressModelComboAction = true
            modelCombo.removeAllItems()
            suppressModelComboAction = false
            setFormEnabled(false)
        }
    }

    private fun onTestConnection() {
        if (list.selectedIndex < 0) return
        saveEditsIntoSelected()
        val profile = list.selectedValue ?: return
        val requestedProfileId = profile.id
        testButton.isEnabled = false
        testButton.text = "Testing…"
        testClient.testConnectionWithApiKey(profile, profile.apiKey) { result ->
            // Try to auto-detect the context window from /models regardless of
            // the connection result; prefill the field when a value is found and
            // the user hasn't entered a specific one (blank or the 128k default).
            testClient.fetchContextWindowWithApiKey(profile, apiKey = profile.apiKey) { detected ->
                javax.swing.SwingUtilities.invokeLater {
                    if (list.selectedValue?.id != requestedProfileId) {
                        // Stale reply — the form now shows a different profile.
                        // Restore the button but don't touch its fields.
                        testButton.isEnabled = true
                        testButton.text = "Test connection"
                        return@invokeLater
                    }
                    testButton.isEnabled = true
                    testButton.text = "Test connection"
                    val atDefault = contextWindowField.text.trim().let {
                        it.isEmpty() || it == ProviderProfile().contextWindow.toString()
                    }
                    if (detected != null && atDefault) contextWindowField.text = detected.toString()
                    val suffix = when {
                        detected != null -> "\n\nContext window: $detected tokens (auto-detected from /models)"
                        atDefault -> "\n\nContext window: not exposed by /models — keeping default ${ProviderProfile().contextWindow} (editable)"
                        else -> null
                    }
                    if (result.startsWith("Connection OK")) {
                        Messages.showInfoMessage(
                            list,
                            "${profile.name}: $result${suffix.orEmpty()}",
                            "TellForgeAI — Test Connection",
                        )
                    } else {
                        Messages.showErrorDialog(
                            list,
                            "${profile.name}: $result${suffix.orEmpty()}",
                            "TellForgeAI — Test Connection",
                        )
                    }
                }
            }
        }
    }

    private fun saveEditsIntoSelected(warn: Boolean = false) {
        // Flush the profile whose data is actually in the form ([shownProfileId],
        // not a possibly-stale index) — indices shift during list mutation.
        val id = shownProfileId ?: listModel.getOrNull(currentEditorIndex)?.id ?: return
        flushEditsInto(id, warn)
    }

    /**
     * Recover keys orphaned by a lost/reset settings file: PasswordSafe holds a
     * `TellForgeAI:<id>` entry but no profile with that id exists. Offers to add a
     * fresh profile per orphan carrying the old key, instead of leaving the
     * secret unreachable forever (previous failure mode: user re-typing keys).
     */
    private fun adoptOrphansIfAny() {
        val settings = TellForgeAISettings.getInstance()
        val liveIds = listModel.elements().toList().map { it.id }.toSet()
        // Candidates: ids remembered across sessions (persisted in settings state)
        // plus ones deferred during this session. Only those whose PasswordSafe
        // entry still exists and whose profile is genuinely gone are orphans.
        val candidates = (settings.knownProfileIds() + knownOrphanCandidates)
            .filter { it !in liveIds && ApiKeyStore.isOrphaned(settings, it) }
        if (candidates.isEmpty()) return
        val msg = "TellForgeAI found ${candidates.size} API key(s) in the IDE PasswordSafe " +
            "with no matching provider profile (the settings file was likely reset).\n\n" +
            "Re-adopt them as new provider profiles?"
        if (Messages.showYesNoDialog(msg, "TellForgeAI — Recover API keys", Messages.getQuestionIcon())
            != Messages.YES
        ) return
        for (id in candidates) {
            val key = ApiKeyStore.get(id).orEmpty()
            val p = ProviderProfile(
                id = UUID.randomUUID().toString(),
                name = "Recovered provider",
                baseUrl = ApiProtocol.OPENAI_COMPATIBLE.defaultBaseUrl,
                model = "",
            )
            ApiKeyStore.set(p.id, key)
            ApiKeyStore.remove(id)
            settings.upsert(p)
            settings.forgetProfileId(id)
            listModel.addElement(p.copy(apiKey = key))
        }
        knownOrphanCandidates.clear()
        modified = true
    }

    /**
     * Writes the current form field values into the profile at [idx].
     * Kept separate from [saveEditsIntoSelected] so the selection-switch path
     * (which may need to flush an index other than `list.selectedIndex`)
     * can reuse it.
     */
    /**
     * Writes the current form field values into the profile with [profileId].
     * Kept separate from [saveEditsIntoSelected] so the selection-switch path
     * (which may need to flush a profile other than `list.selectedValue`)
     * can reuse it. Resolves the id to an index at flush time — indices shift
     * during list mutation (add/delete), so flushing a stale index could write
     * one profile's edits into another; ids are stable.
     * Set [warn] only on the Apply path — `isModified()` flushes on every IDE
     * poll and must never pop a modal dialog.
     */
    private fun flushEditsInto(profileId: String?, warn: Boolean = false) {
        if (profileId == null) return
        val idx = listModel.elements().toList().indexOfFirst { it.id == profileId }
        if (idx !in 0 until listModel.size) return
        val old = listModel[idx]
        val contextWindow = contextWindowField.text.trim().toIntOrNull()?.coerceAtLeast(1024) ?: run {
            // Garbage or below-minimum context-window input: previously silently
            // replaced by the default with no user feedback — the user believed
            // their value was kept.
            val entered = contextWindowField.text.trim()
            if (warn && entered.isNotEmpty() && entered.toIntOrNull() == null) {
                Messages.showWarningDialog(
                    list,
                    "\"$entered\" is not a valid context window in tokens — " +
                        "keeping the default ${old.contextWindow}.",
                    "TellForgeAI — Invalid context window",
                )
            }
            128_000
        }
        val updated = old.copy(
            name = nameField.text.trim(),
            apiProtocol = ApiProtocol.fromDisplayName(protocolCombo.selectedItem?.toString()),
            baseUrl = urlField.text.trim().removeSuffix("/"),
            apiKey = String(keyField.password).trim(),
            model = modelField.text.trim(),
            planModel = planModelField.text.trim().ifBlank { null },
            reasoningEffort = reasoningEffortField.text.trim().ifBlank { null },
            contextWindow = contextWindow,
        )
        // Save OpenRouter-specific fields if the protocol is OpenRouter
        openRouterForm.saveInto(updated)
        listModel[idx] = updated
    }

    override fun isModified(): Boolean {
        saveEditsIntoSelected()
        val projectState = TellForgeAIProjectState.getInstance(project)
        return modified || chatGcMaxMbField.text.trim() != projectState.chatGcMaxMb().toString()
    }

    override fun apply() {
        saveEditsIntoSelected(warn = true)
        val settings = TellForgeAISettings.getInstance()
        val timeout = timeoutField.text.trim().toIntOrNull()?.coerceIn(5, 600) ?: run {
            val entered = timeoutField.text.trim()
            // The label promises 5–600 — previously any parseable value outside
            // that range (or garbage) was silently applied/ignored with no
            // feedback. Clamp valid values, warn on garbage.
            if (entered.isNotEmpty() && entered.toIntOrNull() == null) {
                Messages.showWarningDialog(
                    list,
                    "\"$entered\" is not a valid command timeout in seconds — " +
                        "keeping the current ${settings.commandTimeoutSeconds()}s.",
                    "TellForgeAI — Invalid timeout",
                )
            }
            settings.commandTimeoutSeconds()
        }
        settings.setCommandTimeoutSeconds(timeout)
        settings.setAiPricingLookupEnabled(aiPricingLookupCheckBox.isSelected)
        settings.setPastedImageMaxSize(
            when (val sel = pastedImageSizeCombo.selectedItem as? String) {
                "Original" -> 0
                else -> sel?.toIntOrNull() ?: 1024
            },
        )
        // Per-project chat GC cap: garbage/blank keeps the current value, with
        // the same warn-on-garbage treatment as the other numeric fields.
        val projectState = TellForgeAIProjectState.getInstance(project)
        val gcEntered = chatGcMaxMbField.text.trim()
        val gcMb = gcEntered.toIntOrNull() ?: -1
        when {
            gcEntered.isEmpty() -> { /* keep as-is */ }
            gcMb < 0 -> Messages.showWarningDialog(
                list,
                "\"$gcEntered\" is not a valid chat GC cap in MB — keeping the current ${projectState.chatGcMaxMb()} MB.",
                "TellForgeAI — Invalid chat GC cap",
            )
            else -> projectState.setChatGcMaxMb(gcMb)
        }
        val ids = listModel.elements().toList().map { it.id }.toSet()
        // Remove PasswordSafe entries for profiles deleted through this settings
        // UI *this session* — not merely absent from the list. If the profile
        // list was lost (tellforgeai.xml reset) while keys survive in PasswordSafe,
        // blanket removal would destroy recoverable keys, so they're orphaned
        // instead and offered back by the re-adopt dialog on next open.
        val deletedIds = settings.profiles().map { it.id } - ids
        val deletable = deletedIds.filter { it in deletedThisSession || it !in knownOrphanCandidates }
        deletedIds.filter { it !in deletable }.forEach { knownOrphanCandidates.add(it) }
        deletable.forEach {
            ApiKeyStore.remove(it)
            settings.forgetProfileId(it)
        }
        settings.profiles().filter { it.id in deletedIds }.forEach { settings.delete(it.id) }
        // Persist API keys to the PasswordSafe; profiles themselves go to settings XML.
        // A blank field only deletes the stored key when the user cleared it
        // deliberately this session — otherwise a failed PasswordSafe read in
        // reset() would come back as blank and wipe the key on Apply.
        listModel.elements().toList().forEach {
            if (it.apiKey.isNotBlank() || it.id in clearedThisSession) {
                ApiKeyStore.set(it.id, it.apiKey)
            }
            settings.upsert(it.copy(apiKey = ""))
        }
        settings.state.knownProfileIds.addAll(ids)
        // Keep the key in the settings-session model so switching profiles after
        // Apply still displays it; it is only persisted to PasswordSafe, never XML.
        modified = false
    }

    override fun reset() {
        // Re-seed session-tracking state: previously these sets survived a reset,
        // so a key cleared before Reset (then reloaded unchanged) still wiped the
        // stored PasswordSafe entry on the next Apply, and orphan candidates were
        // re-prompted with stale data.
        deletedThisSession.clear()
        clearedThisSession.clear()
        knownOrphanCandidates.clear()
        suppressFieldListeners = true
        suppressModelComboAction = true
        try {
            listModel.clear()
            deletedThisSession.addAll(TellForgeAISettings.getInstance().profiles().map { it.id })
            TellForgeAISettings.getInstance().profiles()
                .sortedBy { it.name.lowercase() }
                .forEach { p ->
                    listModel.addElement(p.copy(apiKey = ApiKeyStore.get(p.id).orEmpty()))
                }
            currentEditorIndex = if (listModel.isEmpty) -1 else 0
            if (!listModel.isEmpty) list.selectedIndex = 0
            // Drop any model suggestions left over from the pre-reset session and
            // invalidate the shown-profile tracker (re-established on selection).
            shownProfileId = null
            modelCombo.removeAllItems()
            loadGlobalFields()
        } finally {
            suppressFieldListeners = false
            suppressModelComboAction = false
        }
        if (listModel.isEmpty) setFormEnabled(false)
        // Offer orphaned PasswordSafe keys for re-adoption (may add profiles /
        // show a dialog) — after the suppression scope so any resulting UI
        // updates register normally.
        adoptOrphansIfAny()
        modified = false
    }
}
