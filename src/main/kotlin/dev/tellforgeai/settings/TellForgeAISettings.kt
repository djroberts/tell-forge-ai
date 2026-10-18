package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.application.ApplicationManager
import dev.tellforgeai.model.ProviderProfile

class ModelInfoCacheEntry {
    var key: String = ""
    var contextWindow: Int = 0
    var inputPricePerMillion: Double? = null
    var cachedInputPricePerMillion: Double? = null
    var outputPricePerMillion: Double? = null
    var lookupInputTokens: Long = 0
    var lookupOutputTokens: Long = 0
    var source: String = ""
    var checkedAt: Long = 0
    var status: String = ""
}

@State(name = "TellForgeAISettings", storages = [Storage("tellforgeai.xml")])
class TellForgeAISettings : PersistentStateComponent<TellForgeAISettings.State> {

    class State {
        var profiles: MutableList<ProviderProfile> = mutableListOf()
        /** Whether command execution requires explicit user approval. */
        var askBeforeRunCommands: Boolean = true
        /** Max seconds a terminal command may run before being killed. */
        var commandTimeoutSeconds: Int = 30
        /** Web-search backend: "duckduckgo" (keyless) or "brave" (needs API key). */
        var webSearchProvider: String = "duckduckgo"
        /**
         * Two-layer fallback: on a hard failure of the primary provider (error,
         * no key, empty results) the other provider is tried automatically, and
         * the model is allowed to re-call web_search with the other provider
         * when it judges the results unsatisfactory. The Brave API key (stored
         * in PasswordSafe) is used whenever Brave is attempted — as primary or
         * as the fallback target.
         */
        var webSearchFallback: Boolean = true
        /** Explicit opt-in: use the selected LLM for a billable model-pricing lookup. */
        var aiPricingLookupEnabled: Boolean = false
        /**
         * Max longest-side size (px) for images pasted into the chat input before
         * they are sent to the model. 0 = send at original resolution.
         */
        var pastedImageMaxSize: Int = 1024
        /** Provider/model keyed pricing and context lookup cache; entries expire after one day. */
        var modelInfoCache: MutableList<ModelInfoCacheEntry> = mutableListOf()
        /**
         * Persistent cache of resolved absolute paths for bare command names
         * (e.g. "php" -> "/opt/homebrew/bin/php"). Populated by CommandResolver
         * after a successful probe; entries are re-validated on every use and
         * dropped when stale.
         */
        var commandPaths: MutableMap<String, String> = mutableMapOf()
        /**
         * Ids of every profile ever applied. Survives a tellforgeai.xml profile
         * list reset so orphaned PasswordSafe keys (entries whose profile was
         * lost) can still be enumerated and offered back to the user.
         */
        var knownProfileIds: MutableSet<String> = mutableSetOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun profiles(): List<ProviderProfile> = state.profiles.toList()

    fun upsert(profile: ProviderProfile) {
        val idx = state.profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) state.profiles[idx] = profile else state.profiles.add(profile)
    }

    fun delete(id: String) {
        state.profiles.removeAll { it.id == id }
    }

    fun find(id: String?): ProviderProfile? = state.profiles.firstOrNull { it.id == id }

    fun knownProfileIds(): Set<String> = state.knownProfileIds.toSet()

    fun forgetProfileId(id: String) {
        state.knownProfileIds.remove(id)
    }

    fun askBeforeRunCommands(): Boolean = state.askBeforeRunCommands

    fun setAskBeforeRunCommands(value: Boolean) {
        state.askBeforeRunCommands = value
    }

    fun commandTimeoutSeconds(): Int = state.commandTimeoutSeconds.coerceIn(5, 600)

    fun commandPath(name: String): String? = state.commandPaths[name]

    fun setCommandPath(name: String, path: String?) {
        if (path == null) state.commandPaths.remove(name) else state.commandPaths[name] = path
    }

    fun setCommandTimeoutSeconds(value: Int) {
        state.commandTimeoutSeconds = value.coerceIn(5, 600)
    }

    fun webSearchProvider(): String = state.webSearchProvider

    fun setWebSearchProvider(value: String) {
        state.webSearchProvider = value
    }

    fun webSearchFallbackEnabled(): Boolean = state.webSearchFallback

    fun setWebSearchFallback(value: Boolean) {
        state.webSearchFallback = value
    }

    fun aiPricingLookupEnabled(): Boolean = state.aiPricingLookupEnabled

    fun setAiPricingLookupEnabled(value: Boolean) {
        state.aiPricingLookupEnabled = value
    }

    /** 0 = original resolution; anything else is coerced into the sane range. */
    fun pastedImageMaxSize(): Int {
        val v = state.pastedImageMaxSize
        return when {
            v <= 0 -> 0
            else -> v.coerceIn(256, 8192)
        }
    }

    fun setPastedImageMaxSize(value: Int) {
        state.pastedImageMaxSize = value
    }

    fun modelInfoCache(key: String): ModelInfoCacheEntry? =
        state.modelInfoCache.firstOrNull {
            it.key == key && it.status.isNotBlank() && it.status != "Fetching" &&
                System.currentTimeMillis() - it.checkedAt < 24 * 60 * 60 * 1000L
        }

    fun expiredModelInfoCache(key: String): ModelInfoCacheEntry? =
        state.modelInfoCache.firstOrNull { it.key == key }

    fun putModelInfoCache(entry: ModelInfoCacheEntry) {
        state.modelInfoCache.removeAll { it.key == entry.key }
        state.modelInfoCache.add(entry)
        state.modelInfoCache.removeAll { System.currentTimeMillis() - it.checkedAt >= 7 * 24 * 60 * 60 * 1000L }
    }

    companion object {
        @JvmStatic
        fun getInstance(): TellForgeAISettings =
            ApplicationManager.getApplication()
                .getService(TellForgeAISettings::class.java)
    }
}
