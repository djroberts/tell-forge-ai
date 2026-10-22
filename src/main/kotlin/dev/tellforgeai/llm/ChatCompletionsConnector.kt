package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.model.injectOpenRouterBody
import com.intellij.openapi.diagnostic.Logger
import dev.tellforgeai.settings.ApiKeyStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Suspend wrapper turning an OkHttp enqueue into a cancellable await. */
internal suspend fun okhttp3.Call.await(): okhttp3.Response =
    suspendCancellableCoroutine { cont ->
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                cont.resumeWith(Result.success(response))
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

/**
 * Classical chat/completions wire format (OpenAI, OpenRouter, Ollama, vLLM,
 * Together, Gemini-via-compat-endpoint, ...). Extracted verbatim from the
 * former monolithic [LlmClient.stream]; behavior is unchanged.
 */
class ChatCompletionsConnector : LlmConnector {

    companion object {
        private val LOG = Logger.getInstance(ChatCompletionsConnector::class.java)
        internal val HTTP = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            // Log only auth-header presence (never its value) at each network hop.
            // OkHttp intentionally drops Authorization on cross-host redirects; this
            // makes that visible instead of guessing when a server reports it missing.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val hasAuthorization = !request.header("Authorization").isNullOrBlank()
                LOG.info(
                    "TellForgeAI HTTP ${request.method} ${request.url.host}${request.url.encodedPath} " +
                        "authorizationPresent=$hasAuthorization",
                )
                val response = chain.proceed(request)
                if (response.isRedirect) {
                    LOG.warn(
                        "TellForgeAI HTTP redirect ${response.code} from " +
                            "${request.url.host}${request.url.encodedPath} to " +
                            "${response.header("Location") ?: "<no Location>"}; " +
                            "OkHttp may remove Authorization across hosts",
                    )
                }
                response
            }
            .build()
        internal val JSON = Json { ignoreUnknownKeys = true }

        /** Converts a tool spec map (String/Map/List values) to JSON. */
        internal fun toolSpecJson(t: Map<String, Any>): JsonElement = toJsonValue(t)

        internal fun toJsonValue(v: Any): JsonElement = when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Map<*, *> -> buildJsonObject { v.forEach { (k, vv) -> put(k.toString(), toJsonValue(vv!!)) } }
            is List<*> -> JsonArray(v.map { toJsonValue(it!!) })
            is JsonElement -> v
            else -> JsonPrimitive(v.toString())
        }

        /**
         * Serializes a [ChatMessage] to the OpenAI wire format, including tool calls
         * (assistant) and tool results (role "tool").
         */
        internal fun ChatMessage.toJson(): JsonObject = buildJsonObject {
            put("role", role)
            // OpenAI vision format: messages with images use a content array of
            // text + image_url parts instead of a plain string.
            if (images.isEmpty()) {
                put("content", content)
            } else {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                    for (img in images) {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", img) })
                        })
                    }
                })
            }
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", JsonArray(toolCalls.map { c ->
                    buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", c.name)
                            put("arguments", c.arguments)
                        })
                    }
                }))
            }
            if (role == "tool") {
                // Strict OpenAI-compatible servers (e.g. deepinfra/GLM) reject tool
                // result messages without a tool_call_id. Never omit the field; fall
                // back to a placeholder (e.g. for tool calls restored from persisted
                // history that lost their id).
                put("tool_call_id", toolCallId.ifBlank { "call_unknown" })
            }
        }

        /** Shared simple GET request against `{baseUrl}{path}` with Bearer auth. */
        internal fun modelsRequest(
            profile: ProviderProfile,
            protocol: ApiProtocol,
            apiKey: String? = null,
        ): Request {
            val key = apiKey ?: ApiKeyStore.resolve(profile)
            val builder = Request.Builder()
                .url(profile.trimmedBaseUrl() + protocol.modelsPath)
            if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
            return builder.build()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentCall: okhttp3.Call? = null
    private var currentJob: Job? = null

    override fun isRunning(): Boolean = currentJob?.isActive == true

    /**
     * Runs [action] once no stream is active. If a stream is currently running
     * (its job is still finishing even after [LlmClient.Event.Done] was delivered),
     * [action] is deferred via [Job.invokeOnCompletion]; otherwise it runs
     * immediately. This prevents the follow-up turn after tool results from
     * being silently dropped by the `isRunning()` guard in [stream].
     */
    override fun whenIdle(action: () -> Unit) {
        val job = currentJob
        if (job == null || !job.isActive) action()
        else job.invokeOnCompletion { action() }
    }

    fun cancel() {
        currentJob?.cancel()
        currentCall?.cancel()
    }

    override fun stream(
        profile: ProviderProfile,
        planMode: Boolean,
        history: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        protocol: ApiProtocol,
        onEvent: (LlmClient.Event) -> Unit,
    ) {
        // Never silently drop a turn when a previous stream is still tearing
        // down (e.g. right after Stop: job cancellation is asynchronous and
        // isActive stays true briefly). Dropping used to strand the engine in
        // running=true forever — no event, no endTurn, every later Send
        // silently swallowed. Defer like the tool follow-up turn does.
        if (isRunning()) {
            whenIdle { doStream(profile, planMode, history, tools, protocol, onEvent) }
            return
        }
        doStream(profile, planMode, history, tools, protocol, onEvent)
    }

    private fun doStream(
        profile: ProviderProfile,
        planMode: Boolean,
        history: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        protocol: ApiProtocol,
        onEvent: (LlmClient.Event) -> Unit,
    ) {
        if (isRunning()) return
        val model = profile.modelFor(planMode)
        val apiKey = ApiKeyStore.resolve(profile)
        val url = profile.trimmedBaseUrl() + "/chat/completions"
        if (apiKey.isBlank()) {
            onEvent(LlmClient.Event.Error("No API key configured for provider '${profile.name}'. Add it in Settings → TellForgeAI Providers, click Apply, then retry."))
            return
        }

        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("messages", JsonArray(history.map { it.toJson() }))
            profile.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reasoning_effort", it) }
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { toolSpecJson(it) }))
            }
            // OpenRouter-specific body fields (provider routing, plugins, etc.)
            injectOpenRouterBody(profile)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .apply {
                // OpenRouter-specific HTTP headers (site URL, app title)
                if (profile.isOpenRouter) {
                    profile.openRouterSiteUrl?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { header("HTTP-Referer", it) }
                    profile.openRouterAppTitle?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { header("X-Title", it) }
                }
            }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        currentJob = scope.launch {
            val sb = StringBuilder()
            val toolAccum = mutableMapOf<Int, ToolCall>()
            /**
             * Finalizes accumulated tool calls: providers such as GLM (deepinfra)
             * may stream tool-call deltas without an `id`. OpenAI-compatible
             * servers still require a `tool_call_id` on the matching role "tool"
             * message, so synthesize a stable one — the same id must appear on
             * the assistant's tool_calls[] entry and the tool result message.
             */
            fun finalizeToolCalls() = toolAccum.toSortedMap().values.toList().also { calls ->
                calls.forEachIndexed { i, c -> if (c.id.isBlank()) c.id = "call_$i" }
            }
            var finishReason: String? = null
            var usage: LlmClient.Usage? = null
            try {
                var call = HTTP.newCall(request)
                currentCall = call
                var response = call.await()
                var errBody: String? = null
                // Some OpenAI-compatible providers reject `stream_options`; retry once without it.
                if (response.code == 400) {
                    errBody = response.body?.string()
                    if (errBody?.contains("stream_options") == true) {
                        response.close()
                        val plainBody = buildJsonObject {
                            put("model", model); put("stream", true)
                            put("messages", JsonArray(history.map { it.toJson() }))
                            profile.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let { put("reasoning_effort", it) }
                            if (tools.isNotEmpty()) put("tools", JsonArray(tools.map { toolSpecJson(it) }))
                            injectOpenRouterBody(profile)
                        }.toString()
                        call = HTTP.newCall(
                            request.newBuilder()
                                .post(plainBody.toRequestBody("application/json".toMediaType()))
                                .build()
                        )
                        currentCall = call
                        response = call.await()
                        errBody = null
                    }
                }
                if (!response.isSuccessful) {
                    val err = errBody ?: response.body?.string().orEmpty()
                    val sentAuthorization = !response.request.header("Authorization").isNullOrBlank()
                    val finalUrl = response.request.url
                    LOG.warn(
                        "TellForgeAI provider response HTTP ${response.code}; " +
                            "host=${finalUrl.host}, path=${finalUrl.encodedPath}, " +
                            "authorizationPresent=$sentAuthorization",
                    )
                    onEvent(
                        LlmClient.Event.Error(
                            "HTTP ${response.code} from ${finalUrl.host}${finalUrl.encodedPath} " +
                                "(Authorization header ${if (sentAuthorization) "was sent" else "was missing"}): " +
                                err.take(500).ifBlank { response.message },
                        ),
                    )
                    return@launch
                }
                val source = response.body?.source() ?: run {
                    onEvent(LlmClient.Event.Error("Empty response body"))
                    return@launch
                }
                source.use { src ->
                    while (true) {
                        // Throws IOException when the call is cancelled via cancel().
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        if (data.contains("\"tool_calls\"")) LOG.info("TellForgeAI raw tool_call SSE: $data")
                        val chunk = parseChunk(data, toolAccum)
                        if (chunk.delta.isNotEmpty()) {
                            sb.append(chunk.delta)
                            onEvent(LlmClient.Event.Delta(chunk.delta))
                        }
                        parseReasoning(data)?.takeIf { it.isNotEmpty() }?.let { onEvent(LlmClient.Event.Reasoning(it)) }
                        chunk.usage?.let { usage = it }
                        finishReason = chunk.finish ?: finishReason
                    }
                }
                onEvent(LlmClient.Event.Done(sb.toString(), finalizeToolCalls(), finishReason, usage))
            } catch (e: CancellationException) {
                onEvent(LlmClient.Event.Done(sb.toString(), finalizeToolCalls()))
                throw e
            } catch (e: IOException) {
                // A cancelled call surfaces as an IOException mid-read; treat as user stop.
                if (currentJob?.isCancelled == true) {
                    onEvent(LlmClient.Event.Done(sb.toString()))
                } else {
                    onEvent(LlmClient.Event.Error(e.message ?: e.javaClass.simpleName))
                }
            } catch (e: Exception) {
                onEvent(LlmClient.Event.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                currentCall = null
            }
        }
    }

    private data class Chunk(val delta: String, val finish: String?, val usage: LlmClient.Usage?)

    /**
     * Parses one SSE chunk: returns the streamed content delta, the finish
     * reason (if present) and the token usage (typically on the final chunk).
     * Tool-call fragments are accumulated into [toolAccum] by index, since they
     * arrive piecemeal across chunks.
     */
    private fun parseChunk(
        data: String,
        toolAccum: MutableMap<Int, ToolCall>,
    ): Chunk {
        val delta = parseDelta(data)
        var finish: String? = null
        var usage: LlmClient.Usage? = null
        try {
            val obj = JSON.parseToJsonElement(data).jsonObject
            // Use `as?` casts, not `?.jsonObject` / `?.jsonArray`: those extensions
            // THROW on JsonNull, and providers like deepinfra send explicit
            // `"usage":null` / `"tool_calls":null` fields — the throw aborts the
            // whole chunk parse and silently loses the tool-call name.
            (obj["usage"] as? JsonObject)?.let { u ->
                val promptDetails = u["prompt_tokens_details"] as? JsonObject
                    ?: u["input_tokens_details"] as? JsonObject
                usage = LlmClient.Usage(
                    u["prompt_tokens"]?.jsonPrimitive?.intOrNull
                        ?: u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    u["completion_tokens"]?.jsonPrimitive?.intOrNull
                        ?: u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                        ?: promptDetails?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull
                        ?: promptDetails?.get("cached_input_tokens")?.jsonPrimitive?.intOrNull,
                )
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return Chunk(delta, null, usage)
            finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            ((choice["delta"] as? JsonObject)?.get("tool_calls") as? JsonArray)?.forEach { tc ->
                // Each tool_call delta is parsed independently: some providers
                // (e.g. GLM on deepinfra) send odd shapes, and one bad field must
                // not silently discard the function name of the whole chunk.
                try {
                    val o = tc as? JsonObject ?: return@forEach
                    val idx = o["index"]?.jsonPrimitive?.intOrNull ?: 0
                    // Name/arguments are normally nested under "function", but some
                    // providers emit them inline at the top level of the delta.
                    val fn = o["function"] as? JsonObject
                    val name = fn?.get("name") ?: o["name"]
                    val args = fn?.get("arguments") ?: o["arguments"]
                    val existing = toolAccum.getOrPut(idx) { ToolCall() }
                    o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { existing.id = it }
                    name?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { existing.name = it }
                    args?.jsonPrimitive?.contentOrNull?.let { existing.arguments += it }
                } catch (e: Exception) {
                    LOG.warn("TellForgeAI: failed to parse tool_call delta: $tc", e)
                }
            }
        } catch (e: Exception) {
            // Surface the raw chunk and the failure instead of silently dropping
            // it — a discarded name chunk previously made the model appear to
            // call a tool with an empty name.
            LOG.warn("TellForgeAI: failed to parse SSE chunk: $data", e)
        }
        return Chunk(delta, finish, usage)
    }

    /**
     * Extracts the reasoning/thinking delta from an SSE chunk, if the model
     * streams one. Providers use `reasoning_content` (DeepSeek, OpenRouter)
     * or `reasoning` (OpenAI o-series style); both are checked.
     */
    private fun parseReasoning(data: String): String? = try {
        val delta = JSON.parseToJsonElement(data).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
        (delta["reasoning_content"] ?: delta["reasoning"])?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun parseDelta(data: String): String = try {
        val obj = JSON.parseToJsonElement(data).jsonObject
        obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    } catch (_: Exception) {
        ""
    }
}
