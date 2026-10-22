package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project Sentry integration settings (tools -> SentryTool).
 *
 * Nothing secret roams with the project; the Sentry auth token is stored
 * separately in PasswordSafe under a PER-PROJECT key
 * (see [ApiKeyStore.sentryTokenId]). [sentryEnabled] is the master on/off
 * switch: when false the sentry_* tools are not offered to the model at all.
 *
 * [org] is the Sentry organization SLUG (the readable part in the URL). It is
 * required: the REST API paths are org-scoped. [project] is an optional project
 * SLUG that PRE-FILTERS every query to a single project; when empty the tools
 * query across all projects in the org (and the agent lists projects to pick).
 *
 * [baseUrl] defaults to https://sentry.io and may be set to a self-hosted
 * instance. All tools are read-only; there are no write/approval flags here.
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAISentryState", storages = [Storage("tellforgeai.sentry.xml")])
class SentryState : PersistentStateComponent<SentryState.State> {

    class State {
        /** Master switch: expose the sentry_* tools to the model for this project. */
        var sentryEnabled: Boolean = false

        /** Sentry organization slug (the readable part before .sentry.io). */
        var org: String = ""

        /**
         * Optional project slug used to pre-filter every query. Empty = query
         * all projects in the org. Confirmed against the live instance by the
         * "Test connection" in the Configure Integrations dialog.
         */
        var project: String = ""

        /** Sentry instance base URL; https://sentry.io for the hosted product. */
        var baseUrl: String = "https://sentry.io"
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun enabled(): Boolean = state.sentryEnabled

    fun setEnabled(value: Boolean) {
        state.sentryEnabled = value
    }

    fun org(): String = state.org.trim()

    fun setOrg(value: String) {
        state.org = value.trim()
    }

    fun project(): String = state.project.trim()

    fun setProject(value: String) {
        state.project = value.trim()
    }

    fun baseUrl(): String {
        val url = state.baseUrl.trim().ifEmpty { "https://sentry.io" }
        return url.removeSuffix("/")
    }

    fun setBaseUrl(value: String) {
        state.baseUrl = value.trim().ifEmpty { "https://sentry.io" }
    }

    /** Stored per-project token from PasswordSafe, or null. */
    fun token(project: Project): String? = ApiKeyStore.get(ApiKeyStore.sentryTokenId(project))

    fun setToken(project: Project, value: String) {
        ApiKeyStore.set(ApiKeyStore.sentryTokenId(project), value)
    }

    fun configured(project: Project): Boolean = !token(project).isNullOrBlank()

    companion object {
        /**
         * Best-effort extraction of org + project hints from a Sentry DSN like
         * "https://<key>@o123456.ingest.sentry.io/4500000000000000". Returns a
         * pair of (orgId, projectId) as NUMERIC ids, or null when the string is
         * not a recognisable DSN.
         *
         * Note these are the numeric DSN ids, NOT the human-readable slugs the
         * REST query API prefers. This exists so a user can paste the same DSN
         * their application already uses and have the dialog pre-fill; the
         * actual slugs are resolved/confirmed against the live instance by the
         * Configure Integrations dialog's "Test connection" (which lists the
         * org's projects with their real slugs).
         */
        fun parseDsn(dsn: String): Pair<String, String>? {
            val trimmed = dsn.trim()
            if (!trimmed.startsWith("http")) return null
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            // Hosted DSN host is "o<orgId>.ingest.sentry.io".
            val orgId = host.substringBefore('.').takeIf { it.startsWith("o") && it.length > 1 }
                ?: return null
            val path = uri.path ?: return null
            val projectId = path.trim('/').substringBefore('/').takeIf { it.isNotEmpty() }
                ?: return null
            return orgId to projectId
        }

        @JvmStatic
        fun getInstance(project: Project): SentryState =
            project.getService(SentryState::class.java)
    }
}