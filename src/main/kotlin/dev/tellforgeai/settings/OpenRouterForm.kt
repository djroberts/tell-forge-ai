package dev.tellforgeai.settings

import com.intellij.ui.components.JBTextField
import dev.tellforgeai.model.ProviderProfile
import java.awt.Dimension
import javax.swing.*

/**
 * Collapsible/conditional panel of OpenRouter-specific form fields.
 * Shown/hidden in [TellForgeAIConfigurable] when the selected protocol is OPENROUTER.
 *
 * Each field maps to a property on [ProviderProfile].
 */
class OpenRouterForm(
    private val markModified: () -> Unit,
) {
    // ── Attribution ──
    private val siteUrlField = JBTextField()
    private val appTitleField = JBTextField()
    // ── Provider routing ──
    private val providerOrderField = JBTextField()
    private val allowFallbacksCheck = JCheckBox("Allow fallbacks")
    private val providerSortCombo = JComboBox(arrayOf("", "latency", "price", "throughput"))
    private val providerIgnoreField = JBTextField()
    private val providerOnlyField = JBTextField()
    private val quantizationsField = JBTextField()
    private val dataCollectionCombo = JComboBox(arrayOf("", "allow", "deny"))
    // ── Advanced ──
    private val zdrCheck = JCheckBox("ZDR (Zero Data Retention)")
    private val requireParamsCheck = JCheckBox("Require parameters")
    private val maxPricePromptField = JBTextField()
    private val maxPriceCompletionField = JBTextField()
    private val preferredMinThroughputField = JBTextField()
    private val preferredMaxLatencyField = JBTextField()
    private val pluginsJsonField = JBTextField()
    private val extraBodyJsonField = JBTextField()

    /** The root panel — add this to the settings form. */
    val root: JPanel = JPanel().also { buildUi(it) }

    private fun buildUi(parent: JPanel) {
        parent.layout = BoxLayout(parent, BoxLayout.Y_AXIS)
        parent.add(JSeparator())
        parent.add(JLabel(" "))
        parent.add(JLabel("<html><b>OpenRouter Options</b></html>"))

        // Attribution section
        parent.add(sectionLabel("Attribution (for openrouter.ai rankings)"))
        parent.add(labeled("Site URL (HTTP-Referer)", siteUrlField, "https://mysite.com"))
        parent.add(labeled("App title (X-Title)", appTitleField, "My TellForgeAI"))

        // Provider routing section
        parent.add(JSeparator())
        parent.add(JLabel(" "))
        parent.add(sectionLabel("Provider Routing"))
        parent.add(labeled("Provider order (comma-separated slugs)", providerOrderField, "anthropic, openai, google"))
        parent.add(wrapRow(allowFallbacksCheck))
        parent.add(labeled("Sort provider by", providerSortCombo))
        parent.add(labeled("Ignore providers (comma-separated)", providerIgnoreField, "deepinfra, novita"))
        parent.add(labeled("Only providers (comma-separated)", providerOnlyField, "anthropic"))
        parent.add(labeled("Quantizations (comma-separated)", quantizationsField, "fp8, bf16"))
        parent.add(labeled("Data collection", dataCollectionCombo))

        // Advanced section
        parent.add(JSeparator())
        parent.add(JLabel(" "))
        parent.add(sectionLabel("Advanced"))
        parent.add(wrapRow(zdrCheck))
        parent.add(wrapRow(requireParamsCheck))
        parent.add(labeled("Max price — prompt ($/M tokens)", maxPricePromptField, "0.5"))
        parent.add(labeled("Max price — completion ($/M tokens)", maxPriceCompletionField, "1.5"))
        parent.add(labeled("Preferred min throughput (JSON)", preferredMinThroughputField, """{"p50":100,"p90":50}"""))
        parent.add(labeled("Preferred max latency (JSON)", preferredMaxLatencyField, """{"p50":1,"p90":3,"p99":5}"""))
        parent.add(labeled("Plugins JSON", pluginsJsonField, """[{"id":"web"}]"""))
        parent.add(labeled("Extra body JSON (catch-all)", extraBodyJsonField, """{"transforms":["middle-out"]}"""))

        // Mark modified on any change
        listOf(
            siteUrlField, appTitleField, providerOrderField, providerIgnoreField,
            providerOnlyField, quantizationsField, maxPricePromptField,
            maxPriceCompletionField, preferredMinThroughputField,
            preferredMaxLatencyField, pluginsJsonField, extraBodyJsonField,
        ).forEach { field ->
            field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = markModified()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = markModified()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = markModified()
            })
        }
        listOf(allowFallbacksCheck, zdrCheck, requireParamsCheck).forEach { cb ->
            cb.addActionListener { markModified() }
        }
        listOf(providerSortCombo, dataCollectionCombo).forEach { combo ->
            combo.addActionListener { markModified() }
        }

        addDummy(parent)
    }

    /** Loads [profile]'s OpenRouter fields into the form widgets. */
    fun load(profile: ProviderProfile) {
        siteUrlField.text = profile.openRouterSiteUrl.orEmpty()
        appTitleField.text = profile.openRouterAppTitle.orEmpty()
        providerOrderField.text = profile.openRouterProviderOrder.orEmpty()
        allowFallbacksCheck.isSelected = profile.openRouterAllowFallbacks ?: true
        providerSortCombo.selectedItem = profile.openRouterProviderSort.orEmpty().takeIf { it.isNotEmpty() } ?: ""
        providerIgnoreField.text = profile.openRouterProviderIgnore.orEmpty()
        providerOnlyField.text = profile.openRouterProviderOnly.orEmpty()
        quantizationsField.text = profile.openRouterQuantizations.orEmpty()
        dataCollectionCombo.selectedItem = profile.openRouterDataCollection.orEmpty().takeIf { it.isNotEmpty() } ?: ""
        zdrCheck.isSelected = profile.openRouterZdr ?: false
        requireParamsCheck.isSelected = profile.openRouterRequireParameters ?: false
        maxPricePromptField.text = profile.openRouterMaxPricePrompt?.toString().orEmpty()
        maxPriceCompletionField.text = profile.openRouterMaxPriceCompletion?.toString().orEmpty()
        preferredMinThroughputField.text = profile.openRouterPreferredMinThroughput.orEmpty()
        preferredMaxLatencyField.text = profile.openRouterPreferredMaxLatency.orEmpty()
        pluginsJsonField.text = profile.openRouterPluginsJson.orEmpty()
        extraBodyJsonField.text = profile.openRouterExtraBodyJson.orEmpty()
    }

    /** Writes current form values into [profile]. */
    fun saveInto(profile: ProviderProfile) {
        profile.openRouterSiteUrl = siteUrlField.text.trim().ifBlank { null }
        profile.openRouterAppTitle = appTitleField.text.trim().ifBlank { null }
        profile.openRouterProviderOrder = providerOrderField.text.trim().ifBlank { null }
        profile.openRouterAllowFallbacks = allowFallbacksCheck.isSelected
        profile.openRouterProviderSort = (providerSortCombo.selectedItem as? String)?.trim()?.ifBlank { null }
        profile.openRouterProviderIgnore = providerIgnoreField.text.trim().ifBlank { null }
        profile.openRouterProviderOnly = providerOnlyField.text.trim().ifBlank { null }
        profile.openRouterQuantizations = quantizationsField.text.trim().ifBlank { null }
        profile.openRouterDataCollection = (dataCollectionCombo.selectedItem as? String)?.trim()?.ifBlank { null }
        profile.openRouterZdr = zdrCheck.isSelected.takeIf { it }
        profile.openRouterRequireParameters = requireParamsCheck.isSelected.takeIf { it }
        profile.openRouterMaxPricePrompt = maxPricePromptField.text.trim().toDoubleOrNull()
        profile.openRouterMaxPriceCompletion = maxPriceCompletionField.text.trim().toDoubleOrNull()
        profile.openRouterPreferredMinThroughput = preferredMinThroughputField.text.trim().ifBlank { null }
        profile.openRouterPreferredMaxLatency = preferredMaxLatencyField.text.trim().ifBlank { null }
        profile.openRouterPluginsJson = pluginsJsonField.text.trim().ifBlank { null }
        profile.openRouterExtraBodyJson = extraBodyJsonField.text.trim().ifBlank { null }
    }

    // ── UI helpers ──

    private fun sectionLabel(text: String): JComponent =
        JLabel("<html><b>$text</b></html>")

    private fun labeled(label: String, field: JComponent, placeholder: String? = null): JComponent {
        if (field is JBTextField && placeholder != null) {
            field.emptyText.text = placeholder
        }
        val panel = JPanel(java.awt.BorderLayout(4, 2))
        panel.isOpaque = false
        panel.maximumSize = Dimension(Int.MAX_VALUE, 60)
        val l = JLabel(label)
        l.alignmentX = 0.0f
        panel.add(l, java.awt.BorderLayout.NORTH)
        panel.add(field, java.awt.BorderLayout.CENTER)
        val wrapper = JPanel(java.awt.BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(panel, java.awt.BorderLayout.NORTH)
        return wrapper
    }

    private fun wrapRow(component: JComponent): JComponent {
        val panel = JPanel(java.awt.BorderLayout())
        panel.isOpaque = false
        panel.add(component, java.awt.BorderLayout.WEST)
        val wrapper = JPanel(java.awt.BorderLayout())
        wrapper.isOpaque = false
        wrapper.add(panel, java.awt.BorderLayout.NORTH)
        return wrapper
    }

    private fun addDummy(parent: JPanel) {
        parent.add(Box.createVerticalGlue())
    }
}
