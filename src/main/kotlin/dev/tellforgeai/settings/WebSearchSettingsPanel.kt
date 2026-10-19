package dev.tellforgeai.settings

import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import dev.tellforgeai.tools.SearchOutcome
import dev.tellforgeai.tools.SearchProviders
import dev.tellforgeai.tools.SearchProvider
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.concurrent.thread

/**
 * Reusable "Web Search" settings panel (a [JPanel], embedded by the
 * Configurable of the same name): primary provider, fallback provider
 * (None / Auto / a specific other provider — replaces the old boolean
 * checkbox), per-provider API-key fields and the SearXNG instance URL
 * (keys + URL are written on Apply), plus a canned "Test search" button.
 *
 * A [SearchProvider] appears here automatically once it is registered in
 * [SearchProviders.all]: the combo items come from the registry, a keyed
 * provider renders a key field when it has a non-null [SearchProvider.keyId],
 * and `urlConfigurable` providers (SearXNG) render the extra config field
 * declared there.
 */
class WebSearchSettingsPanel {

    private val primaryCombo = JComboBox(SearchProviders.all.map { it.displayName }.toTypedArray())

    /** None (disabled) / Auto (first other available) / one specific provider. */
    private val fallbackCombo = JComboBox<String>()

    /** cardId (provider id) -> key field, one per keyed provider. */
    private val keyFields = mutableMapOf<String, JBPasswordField>()

    /** Providers that need a config *value* rather than a key (SearXNG: URL). */
    private val urlField = JTextField()

    private val testButton = JButton("Test search")
    private var testInFlight = false

    /** Set by the owning Configurable when a field changes. */
    var onChanged: () -> Unit = {}

    val root: JComponent = JPanel(BorderLayout(0, 8)).apply {
        isOpaque = false
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)
        column.isOpaque = false
        column.add(labeled("Primary search provider (try this first)", primaryCombo))
        fallbackCombo.addItem(FALLBACK_LABEL_NONE)
        fallbackCombo.addItem(FALLBACK_LABEL_AUTO)
        SearchProviders.all.forEach { fallbackCombo.addItem(it.displayName) }
        column.add(labeled("Fallback provider when the primary fails (None = off; Auto = first other available; specific = exactly this one). The model may still deliberately call web_search with any provider via its `provider` argument.", fallbackCombo))
        column.add(labeled(
            "SearXNG instance base URL (e.g. https://searx.be — the instance must allow the JSON API: format=json)",
            urlField,
        ))
        for (provider in SearchProviders.all) {
            val keyId = provider.keyId ?: continue
            val field = JBPasswordField()
            keyFields[provider.id] = field
            column.add(labeled("${provider.keyLabel ?: provider.displayName} (global)", field))
            field.document.addDocumentListener(SimpleDoc { onChanged() })
        }
        column.add(javax.swing.Box.createVerticalStrut(4))
        column.add(testButton)
        add(column, BorderLayout.NORTH)
    }

    init {
        primaryCombo.addActionListener {
            if (loading) return@addActionListener
            onChanged()
        }
        fallbackCombo.addActionListener { if (!loading) onChanged() }
        urlField.document.addDocumentListener(SimpleDoc { if (!loading) onChanged() })
        testButton.addActionListener { onTestSearch() }
        testButton.isEnabled = false
    }

    private companion object {
        const val FALLBACK_LABEL_NONE = "None (no automatic fallback)"
        const val FALLBACK_LABEL_AUTO = "Auto (first other available provider)"
    }

    /** Suppress listeners while values are loaded programmatically. */
    private var loading = false

    /** Runs [block] with change listeners suppressed (programmatic loads). */
    private inline fun withoutListeners(block: () -> Unit) {
        loading = true
        try {
            block()
        } finally {
            loading = false
        }
    }

    /** Populate the panel from application settings (call on reset/create). */
    fun load() {
        val s = TellForgeAISettings.getInstance()
        withoutListeners {
            val id = SearchProviders.normalize(s.webSearchProvider())
                ?: SearchProviders.all.first().id
            primaryCombo.selectedIndex = SearchProviders.all.indexOfFirst { it.id == id }
                .takeIf { it >= 0 } ?: 0
            fallbackCombo.selectedItem = when (val fp = s.fallbackProvider()) {
                TellForgeAISettings.FALLBACK_NONE -> FALLBACK_LABEL_NONE
                TellForgeAISettings.FALLBACK_AUTO -> FALLBACK_LABEL_AUTO
                else ->
                    SearchProviders.all.firstOrNull { it.id == fp }?.displayName
                        ?: FALLBACK_LABEL_AUTO
            }
            urlField.text = s.searxngBaseUrl()
            for (provider in SearchProviders.all) {
                val field = keyFields[provider.id] ?: continue
                field.text = ApiKeyStore.get(provider.keyId!!).orEmpty()
            }
        }
        testButton.isEnabled = true
    }

    /**
     * Write current values into [TellForgeAISettings] / PasswordSafe (call on
     * apply). Key fields: blank = clear the stored key, mirroring [ApiKeyStore.set].
     */
    fun apply() {
        val s = TellForgeAISettings.getInstance()
        SearchProviders.all.firstOrNull { it.displayName == primaryCombo.selectedItem }
            ?.let { s.setWebSearchProvider(it.id) }
        s.setFallbackProvider(
            when (fallbackCombo.selectedItem) {
                FALLBACK_LABEL_NONE -> TellForgeAISettings.FALLBACK_NONE
                FALLBACK_LABEL_AUTO -> TellForgeAISettings.FALLBACK_AUTO
                else -> SearchProviders.all.firstOrNull { it.displayName == fallbackCombo.selectedItem }
                    ?.id ?: TellForgeAISettings.FALLBACK_AUTO
            }
        )
        s.setSearxngBaseUrl(urlField.text)
        for ((id, field) in keyFields) {
            val provider = SearchProviders.byId(id)!!
            ApiKeyStore.set(provider.keyId!!, String(field.password).trim())
        }
    }

    /** Fires one canned query via the *currently selected primary* (no routing). */
    private fun onTestSearch() {
        if (testInFlight) return
        val provider = SearchProviders.all.firstOrNull { it.displayName == primaryCombo.selectedItem }
            ?: return
        val keyField = keyFields[provider.id]
        // Use the field's in-editor value so an unsaved key can be tested.
        if (provider.keyId != null && keyField != null) {
            ApiKeyStore.set(provider.keyId!!, String(keyField.password).trim())
        }
        testInFlight = true
        testButton.isEnabled = false
        testButton.text = "Searching…"
        thread(name = "TellForgeAI-test-search") {
            val outcome = runCatching { provider.search("TellForgeAI test") }
                .getOrElse { SearchOutcome.Fail("Error: ${it.message ?: it.javaClass.simpleName}") }
            javax.swing.SwingUtilities.invokeLater {
                testInFlight = false
                testButton.isEnabled = true
                testButton.text = "Test search"
                when (outcome) {
                    is SearchOutcome.OK ->
                        Messages.showInfoMessage(
                            root, "${provider.displayName}: ${outcome.text.trim()}",
                            "TellForgeAI — Test Search",
                        )
                    is SearchOutcome.Fail ->
                        Messages.showErrorDialog(
                            root, "${provider.displayName}: ${outcome.text}",
                            "TellForgeAI — Test Search",
                        )
                }
            }
        }
    }

    private class SimpleDoc(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    }

    private fun labeled(label: String, field: JComponent): JComponent {
        val panel = JPanel(BorderLayout(4, 2))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Int.MAX_VALUE, 60)
        panel.preferredSize = Dimension(480, 56)
        val l = JLabel(label)
        l.alignmentX = 0.0f
        panel.add(l, BorderLayout.NORTH)
        panel.add(field, BorderLayout.CENTER)
        return panel
    }
}
