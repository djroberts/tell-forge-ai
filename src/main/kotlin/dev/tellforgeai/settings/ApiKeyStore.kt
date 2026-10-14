package dev.tellforgeai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores provider API keys in the IDE's PasswordSafe (macOS Keychain,
 * Windows Credential Manager, KDE/GNOME Keyring, or KeePass depending on the
 * user's Settings -> Passwords configuration) instead of plain-text settings XML.
 *
 * Keys are keyed by profile id; [ProviderProfile.apiKey] is @Transient and
 * never written to tellforgeai.xml.
 */
object ApiKeyStore {

    /** PasswordSafe key for the optional Brave Search API key (web search). */
    const val BRAVE_KEY_ID: String = "TellForgeAI:brave-api-key"

    /** PasswordSafe key for the per-project Tela wiki personal access token. */
    const val WIKI_TOKEN_ID: String = "TellForgeAI:tela-wiki-token"

    /** Prefix used for every TellForgeAI-scoped PasswordSafe entry. */
    private const val PREFIX = "TellForgeAI:"

    private fun attributes(profileId: String) = CredentialAttributes(PREFIX + profileId)

    fun get(profileId: String): String? =
        PasswordSafe.instance.get(attributes(profileId))?.getPasswordAsString()

    fun set(profileId: String, apiKey: String) {
        if (apiKey.isBlank()) {
            remove(profileId)
        } else {
            PasswordSafe.instance.set(attributes(profileId), Credentials("TellForgeAI", apiKey))
        }
    }

    fun remove(profileId: String) {
        PasswordSafe.instance.set(attributes(profileId), null)
    }

    /**
     * True when a PasswordSafe entry exists for [profileId] but TellForgeAI has no
     * stored profile with that id — i.e. the key survives but the profile that
     * references it was lost (settings XML reset, sync rollback…).
     */
    fun isOrphaned(settings: TellForgeAISettings, profileId: String): Boolean =
        !get(profileId).isNullOrBlank() && settings.find(profileId) == null

    /**
     * Re-links a PasswordSafe entry to a live profile id:
     * copies the password from [fromId] into [toId]]. Used by the orphan
     * "re-adopt" flow so a lost settings file can recover its keys.
     */
    fun reattach(fromId: String, toId: String) {
        get(fromId)?.let { set(toId, it) }
    }
}
