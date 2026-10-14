package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.model.injectOpenRouterBody
import dev.tellforgeai.settings.ApiKeyStore
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
    private val helperScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    )

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

    /** Requests a non-streaming, tool-free summary using the same provider and selected mode model. */
    fun summarize(
        profile: ProviderProfile,
        planMode: Boolean,
        messages: List<ChatMessage>,
        maxTokens: Int,
        onResult: (Result<String>) -> Unit,
    ) {
        helperScope.launch {
            try {
                val model = profile.modelFor(planMode)
                val apiKey = ApiKeyStore.resolve(profile)
                val responsesApi = profile.apiProtocol.connector == ApiProtocol.OPENAI_RESPONSES
                val requestJson = if (responsesApi) {
                    buildJsonObject {
                        put("model", model)
                        put("input", JsonArray(messages.map { message ->
                            buildJsonObject {
                                put("role", if (message.role == "system") "developer" else message.role)
                                put("content", message.content)
                            }
                        }))
                        put("max_output_tokens", maxTokens.coerceAtLeast(128))
                    }
                } else {
                    buildJsonObject {
                        put("model", model)
                        put("stream", false)
                        put("messages", JsonArray(messages.map { message ->
                            buildJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        }))
                        put("max_tokens", maxTokens.coerceAtLeast(128))
                        injectOpenRouterBody(profile)
                    }
                }
                val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
                val endpoint = if (responsesApi) "/responses" else "/chat/completions"
                val request = okhttp3.Request.Builder()
                    .url(profile.trimmedBaseUrl() + endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .apply {
                        if (profile.isOpenRouter) {
                            profile.openRouterSiteUrl?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let { header("HTTP-Referer", it) }
                            profile.openRouterAppTitle?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let { header("X-Title", it) }
                        }
                    }
                    .post(requestBody)
                    .build()
                Shared.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Summary request failed (HTTP ${response.code})")
                    val root = Shared.json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val text = if (responsesApi) {
                        root["output_text"]?.jsonPrimitive?.contentOrNull
                            ?: (root["output"] as? JsonArray)?.flatMap { item ->
                                (item.jsonObject["content"] as? JsonArray).orEmpty().mapNotNull { part ->
                                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                }
                            }?.joinToString("\n")
                    } else {
                        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    }
                    if (text.isNullOrBlank()) error("Summary response contained no text")
                    onResult(Result.success(text))
                }
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
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
        fetchContextWindowWithApiKey(profile, profile.model, null, onResult)
    }

    /** As above, for a specific model (e.g. the plan-mode model), not the profile default. */
    fun fetchContextWindow(profile: ProviderProfile, model: String, onResult: (Int?) -> Unit) {
        fetchContextWindowWithApiKey(profile, model, null, onResult)
    }

    fun fetchContextWindowWithApiKey(
        profile: ProviderProfile,
        model: String = profile.model,
        apiKey: String?,
        onResult: (Int?) -> Unit,
    ) {
        fetchModelsRaw(profile, apiKey) { body ->
            onResult(body?.let { ContextLimitParser.fromModelsJson(it, model) })
        }
    }

    /**
     * Verifies a provider profile by requesting `{baseUrl}{modelsPath}`.
     * Invokes [onResult] on a background thread.
     */
    fun testConnection(profile: ProviderProfile, onResult: (String) -> Unit) {
        testConnectionWithApiKey(profile, null, onResult)
    }

    fun testConnectionWithApiKey(
        profile: ProviderProfile,
        apiKey: String?,
        onResult: (String) -> Unit,
    ) {
        val request = ChatCompletionsConnector.modelsRequest(profile, profile.apiProtocol.connector, apiKey)
        Shared.http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                onResult("Failed: ${e.message ?: e.javaClass.simpleName}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.isSuccessful) {
                        onResult("Connection OK")
                    } else {
                        val finalRequest = it.request
                        val authPresent = !finalRequest.header("Authorization").isNullOrBlank()
                        val detail = it.body?.string().orEmpty().take(500).ifBlank { it.message }
                        onResult(
                            "Failed: HTTP ${it.code} from ${finalRequest.url.host}${finalRequest.url.encodedPath} " +
                                "(Authorization header ${if (authPresent) "present on final request" else "missing on final request"}): $detail",
                        )
                    }
                }
            }
        })
    }

    /**
     * Fetches the list of model ids from `{baseUrl}{modelsPath}` (path
     * resolved from the profile's [ApiProtocol]). Returns at most 200 ids so
     * the settings dropdown stays responsive.
     */
    fun fetchModels(profile: ProviderProfile, onResult: (List<String>) -> Unit) {
        fetchModelsWithApiKey(profile, null, onResult)
    }

    fun fetchModelsWithApiKey(
        profile: ProviderProfile,
        apiKey: String?,
        onResult: (List<String>) -> Unit,
    ) {
        fetchModelsRaw(profile, apiKey) { body ->
            val ids = body?.let { parseModelIds(it) }.orEmpty()
            onResult(ids.take(200))
        }
    }

    /** Issues the GET and hands the raw (nullable) body to [onBody]. */
    private fun fetchModelsRaw(profile: ProviderProfile, apiKey: String? = null, onBody: (String?) -> Unit) {
        val request = ChatCompletionsConnector.modelsRequest(profile, profile.apiProtocol.connector, apiKey)
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
