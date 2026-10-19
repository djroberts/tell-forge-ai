package dev.tellforgeai.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * Settings page: Tools -> TellForgeAI Web Search. Owns everything web-search
 * related (provider choice, fallback opt-in, per-provider API keys) via
 * [WebSearchSettingsPanel], so the Providers page stays profile-only.
 *
 * State lives in the shared `tellforgeai.xml` ([TellForgeAISettings]); only
 * the UI is split, so no persistence migration was needed.
 */
class TellForgeAIWebSearchConfigurable : Configurable {

    private val panel = WebSearchSettingsPanel()

    override fun getDisplayName(): String = "TellForgeAI Web Search"

    override fun createComponent(): JComponent {
        panel.onChanged = { panelModified = true }
        panel.load()
        panelModified = false
        return panel.root
    }

    private var panelModified = false

    override fun isModified(): Boolean = panelModified

    override fun apply() {
        panel.apply()
        panelModified = false
    }

    override fun reset() {
        panel.load()
        panelModified = false
    }
}
