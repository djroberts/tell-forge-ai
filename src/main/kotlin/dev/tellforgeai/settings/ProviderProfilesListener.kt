package dev.tellforgeai.settings

import com.intellij.util.messages.Topic

/**
 * Notification topic fired whenever the set of provider profiles may have
 * changed (settings Apply, orphaned-key re-adopt). UI surfaces that display
 * providers (e.g. the chat panel's profile dropdowns) subscribe to this so
 * they refresh even when the settings page was opened outside their own
 * gear-button entry point (File -> Settings, Search Everywhere, …).
 */
interface ProviderProfilesListener {
    fun providerProfilesChanged()

    companion object {
        val TOPIC: Topic<ProviderProfilesListener> =
            Topic.create("TellForgeAI provider profiles changed", ProviderProfilesListener::class.java)
    }
}
