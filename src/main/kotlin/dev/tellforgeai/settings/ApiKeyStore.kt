package dev.tellforgeai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import dev.tellforgeai.model.ProviderProfile

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

    /**
     * Legacy GLOBAL PasswordSafe key for the tela wiki token, used before
     * wiki tokens became per-project. Kept only for the one-time migration in
     * [wikiTokenId]; do not read/write it elsewhere.
     */
    private const val LEGACY_WIKI_TOKEN_ID: String = "TellForgeAI:tela-wiki-token"

    /** Prefix used for every TellForgeAI-scoped PasswordSafe entry. */
    private const val PREFIX = "TellForgeAI:"

    /**
     * Per-project PasswordSafe key for the tela wiki token. Derived from the
     * project base path so it is stable across IDE restarts and renames while
     * never leaking the raw path into the keyring.
     */
    fun wikiTokenId(project: Project): String {
        migrateLegacyWikiToken(project)
        return "tela-wiki-token:" + scopeId(project)
    }

    /** Per-project PasswordSafe key for the Notion integration token. */
    fun notionTokenId(project: Project): String =
        "notion-token:" + scopeId(project)

    /** Per-project PasswordSafe key for the Sentry auth token. */
    fun sentryTokenId(project: Project): String =
        "sentry-token:" + scopeId(project)

    /**
     * Stable per-project scope suffix for PasswordSafe keys. "default" covers
     * the default/scratch project where [Project.getBasePath] is null.
     */
    private fun scopeId(project: Project): String =
        project.basePath?.let { Integer.toHexString(it.hashCode()) } ?: "default"

    /**
     * One-time migration: a wiki token stored under the legacy GLOBAL key
     * ("TellForgeAI:tela-wiki-token") is moved into the first project that
     * asks for it, and the global entry is removed so other projects no
     * longer inherit it silently.
     */
    private fun migrateLegacyWikiToken(project: Project) {
        val legacyAttrs = CredentialAttributes(LEGACY_WIKI_TOKEN_ID)
        val legacy = PasswordSafe.instance.get(legacyAttrs)
            ?.getPasswordAsString()?.trim()?.ifBlank { null } ?: return
        val perProjectAttrs = CredentialAttributes(PREFIX + wikiTokenId(project))
        if (PasswordSafe.instance.get(perProjectAttrs) == null) {
            PasswordSafe.instance.set(perProjectAttrs, Credentials("TellForgeAI", legacy))
        }
        PasswordSafe.instance.set(legacyAttrs, null)
    }

    private fun attributes(profileId: String) = CredentialAttributes(PREFIX + profileId)

    /** Read and normalize legacy entries as well as newly saved keys. */
    fun get(profileId: String): String? =
        PasswordSafe.instance.get(attributes(profileId))?.getPasswordAsString()?.trim()?.ifBlank { null }

    /**
     * Resolve from the transient in-memory profile value when available, then
     * PasswordSafe. Normalize both so pasted/legacy surrounding whitespace does
     * not make the Authorization header malformed.
     */
    fun resolve(profile: ProviderProfile): String =
        profile.apiKey.trim().takeIf { it.isNotEmpty() } ?: get(profile.id).orEmpty()

    /** Store only normalized credentials; blank input removes an existing key. */
    fun set(profileId: String, apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isEmpty()) {
            remove(profileId)
        } else {
            PasswordSafe.instance.set(attributes(profileId), Credentials("TellForgeAI", normalized))
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
     * Re-links a PasswordSafe entry to a live profile id by copying the password
     * from [fromId] into [toId]. Used by the orphan re-adopt flow.
     */
    fun reattach(fromId: String, toId: String) {
        get(fromId)?.let { set(toId, it) }
    }
}
