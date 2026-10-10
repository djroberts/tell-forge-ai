package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.settings.ApiKeyStore
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/**
 * Dispatches requests to the connector matching the profile's configured
 * [ApiProtocol] (the actual wire work lives in the connector classes).
 *
 * Also hosts the non-streaming helpers shared by the settings UI:
 * [stream], [whenIdle], [cancel], [fetchContextWindow], [testConnection] and
 * [fetchModels] — each persisted-protocol-aware via [ApiProtocol.modelsPath].
 */
class LlmClient {

    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        /** Prompt tokens served from cache, when the provider reports them. */
        val cachedPromptTokens: Int? = null,
    )

    sealed interface Event {
        data class Delta(val text: String) : Event
        data class Reasoning(val text: String) : Event
        data class Done(
            val fullText: String,
            val toolCalls: List<ToolCall> = emptyList(),
            val finishReason: String? = null,
            val usage: Usage? = null,
        ) : Event
        data class Error(val message: String) : Event
    }

    private val chatCompletions = ChatCompletionsConnector()
    private val responses = OpenaiResponsesConnector()

    private fun getClient(protocol: ApiProtocol): LlmConnector = when (protocol.connector) {
        ApiProtocol.OPENAI_RESPONSES -> responses
        else -> chatCompletions
    }

    fun isRunning(): Boolean =
        chatCompletions.isRunning() || responses.isRunning()

    /**
     * Runs [action] when neither connector is streaming. Bridges to whichever
     * connector is busy (its stream deferreds follow-up turns after tool
     * results) or runs [action] immediately when idle.
     */
    fun whenIdle(action: () -> Unit) {
        val busy = listOf(chatCompletions, responses).firstOrNull { it.isRunning() }
        if (busy == null) action() else busy.whenIdle(action)
    }

    fun stream(
        profile: ProviderProfile,
        planMode: Boolean,
        history: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        onEvent: (Event) -> Unit,
    ) = getClient(profile.apiProtocol).stream(
        profile, planMode, history, tools, profile.apiProtocol.connector, onEvent,
    )

    fun cancel() {
        chatCompletions.cancel()
        responses.cancel()
    }

    /**
     * Shared OkHttp client + SSE/protocol helpers, backed by the connector
     * classes so a single pool, timeout and JSON instance exist per process.
     */
    object Shared {
        val http: OkHttpClient = ChatCompletionsConnector.HTTP
        val json = ChatCompletionsConnector.JSON
    }

    /**
     * Tries to auto-detect the model's context window from the protocol-built
     * `{baseUrl}{modelsPath}` endpoint. See [ContextLimitParser] for the
     * nonstandard context-length field keys recognized across OpenAI-compatible
     * backends.
     */
    fun fetchContextWindow(profile: ProviderProfile, onResult: (Int?) -> Unit) {
        fetchContextWindow(profile, profile.model, onResult)
    }

    /** As above, for a specific model (e.g. the plan-mode model), not the profile default. */
    fun fetchContextWindow(profile: ProviderProfile, model: String, onResult: (Int?) -> Unit) {
        fetchModelsRaw(profile) { body ->
            onResult(body?.let { ContextLimitParser.fromModelsJson(it, model) })
        }
    }

    /**
     * Verifies a provider profile by requesting `{baseUrl}{modelsPath}`.
     * Invokes [onResult] on a background thread.
     */
    fun testConnection(profile: ProviderProfile, onResult: (String) -> Unit) {
        fetchModelsRaw(profile) { body ->
            onResult(body?.let { "Connection OK" }
                ?: "Failed: endpoint unreachable or returned an error")
        }
    }

    /**
     * Fetches the list of model ids from `{baseUrl}{modelsPath}` (path
     * resolved from the profile's [ApiProtocol]). Returns at most 200 ids so
     * the settings dropdown stays responsive.
     */
    fun fetchModels(profile: ProviderProfile, onResult: (List<String>) -> Unit) {
        fetchModelsRaw(profile) { body ->
            val ids = body?.let { parseModelIds(it) }.orEmpty()
            onResult(ids.take(200))
        }
    }

    /** Issues the GET and hands the raw (nullable) body to [onBody]. */
    private fun fetchModelsRaw(profile: ProviderProfile, onBody: (String?) -> Unit) {
        val request = ChatCompletionsConnector.modelsRequest(profile, profile.apiProtocol.connector)
        Shared.http.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    onBody(null)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        onBody(if (it.isSuccessful) it.body?.string() else null)
                    }
                }
            })
    }

    private fun parseModelIds(modelsJson: String): List<String> = try {
        val entries = Shared.json.parseToJsonElement(modelsJson).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        entries.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}
