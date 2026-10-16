package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import com.intellij.openapi.diagnostic.Logger
import dev.tellforgeai.settings.ApiKeyStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI `/v1/responses` connector ([ApiProtocol.OPENAI_RESPONSES]).
 *
 * Needed because OpenAI's reasoning models (gpt-5.x/6.x, o-series) reject the
 * classic function-tools-with-reasoning_effort combination on
 * /chat/completions — the Responses wire format is the supported path for
 * reasoning + tool use on those models.
 *
 * Differences from chat/completions that this connector handles:
 *  - request body key is `input` (a list of role/content items), not `messages`
 *  - `reasoning: {effort: "..."}` instead of top-level `reasoning_effort`
 *  - tool specs are flat: {type:"function", name, description, parameters}
 *    (no nested "function" wrapper)
 *  - SSE events are typed (`response.output_text.delta`,
 *    `response.function_call_arguments.delta`, `response.completed`, ...)
 *  - tool output is fed back as `{"type":"function_call_output", call_id, output}`
 *    items instead of role:"tool" messages
 */
class OpenaiResponsesConnector : LlmConnector {

    companion object {
        private val LOG = Logger.getInstance(OpenaiResponsesConnector::class.java)
        private val HTTP = ChatCompletionsConnector.HTTP
        private val JSON = ChatCompletionsConnector.JSON
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentCall: okhttp3.Call? = null
    private var currentJob: Job? = null

    override fun isRunning(): Boolean = currentJob?.isActive == true

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
        if (isRunning()) return
        val model = profile.modelFor(planMode)
        val apiKey = ApiKeyStore.resolve(profile)
        val url = profile.trimmedBaseUrl() + "/responses"

        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("input", JsonArray(sanitize(history.flatMap { it.toInputItems() })))
            profile.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("reasoning", buildJsonObject { put("effort", it) })
            }
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { flatToolSpec(it) }))
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        currentJob = scope.launch {
            if (apiKey.isBlank()) {
                onEvent(LlmClient.Event.Error("No API key configured. Open the Settings dialog (gear icon), paste your API key, and click Apply."))
                return@launch
            }
            val sb = StringBuilder()
            // Accumulates function-call arguments keyed by item_id — they arrive
            // piecemeal via response.function_call_arguments.delta events.
            val toolAccum = mutableMapOf<String, ToolCall>()
            fun finalizeToolCalls(): List<ToolCall> {
                val calls = toolAccum.values.toList()
                calls.forEachIndexed { i, c -> if (c.id.isBlank()) c.id = "call_$i" }
                return calls
            }
            var finishReason: String? = null
            var usage: LlmClient.Usage? = null
            try {
                val call = HTTP.newCall(request)
                currentCall = call
                val response = call.await()
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    onEvent(LlmClient.Event.Error("HTTP ${response.code}: ${err.take(500).ifBlank { response.message }}"))
                    return@launch
                }
                val source = response.body?.source() ?: run {
                    onEvent(LlmClient.Event.Error("Empty response body"))
                    return@launch
                }
                source.use { src ->
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val obj = JSON.parseToJsonElement(data).jsonObject
                            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            when (type) {
                                "response.output_text.delta" -> {
                                    val t = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (t.isNotEmpty()) {
                                        sb.append(t)
                                        onEvent(LlmClient.Event.Delta(t))
                                    }
                                }
                                "response.reasoning_summary_text.delta",
                                "response.reasoning_text.delta" -> {
                                    val t = obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (t.isNotEmpty()) onEvent(LlmClient.Event.Reasoning(t))
                                }
                                "response.output_item.added" -> {
                                    val item = obj["item"] as? JsonObject ?: return@use
                                    if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                        val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: return@use
                                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull ?: itemId
                                        toolAccum.getOrPut(callId) {
                                            ToolCall(
                                                id = callId,
                                                name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                            )
                                        }
                                    }
                                }
                                "response.function_call_arguments.delta" -> {
                                    val itemId = obj["item_id"]?.jsonPrimitive?.contentOrNull ?: return@use
                                    val existing = toolAccum[itemId]
                                    if (existing != null) {
                                        existing.arguments += obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    }
                                }
                                "response.output_item.done" -> {
                                    // Some models complete the (empty-name or
                                    // arguments-only) tool call here instead; parse defensively.
                                    val item = obj["item"] as? JsonObject ?: return@use
                                    if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                                        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                            ?: item["id"]?.jsonPrimitive?.contentOrNull
                                            ?: return@use
                                        val existing = toolAccum.getOrPut(callId) { ToolCall(id = callId) }
                                        item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                            ?.let { if (existing.name.isBlank()) existing.name = it }
                                        item["arguments"]?.jsonPrimitive?.contentOrNull?.let {
                                            if (existing.arguments.isBlank()) existing.arguments = it
                                        }
                                    }
                                }
                                "response.completed", "response.incomplete", "response.failed" -> {
                                    val resp = obj["response"] as? JsonObject
                                    finishReason = resp?.get("status")?.jsonPrimitive?.contentOrNull ?: finishReason
                                    (resp?.get("usage") as? JsonObject)?.let { u ->
                                        val inputDetails = u["input_tokens_details"] as? JsonObject
                                            ?: u["prompt_tokens_details"] as? JsonObject
                                        usage = LlmClient.Usage(
                                            u["input_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                            u["output_tokens"]?.jsonPrimitive?.intOrNull
                                                ?: u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                            inputDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                                                ?: inputDetails?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull
                                                ?: inputDetails?.get("cached_input_tokens")?.jsonPrimitive?.intOrNull,
                                        )
                                    }
                                    (resp?.get("error") as? JsonObject)?.let { err ->
                                        err["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                            ?.let { onEvent(LlmClient.Event.Error("HTTP 200 error: $it")) }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            LOG.warn("TellForgeAI: failed to parse responses SSE chunk: $data", e)
                        }
                    }
                }
                onEvent(LlmClient.Event.Done(sb.toString(), finalizeToolCalls(), finishReason, usage))
            } catch (e: CancellationException) {
                onEvent(LlmClient.Event.Done(sb.toString(), finalizeToolCalls()))
                throw e
            } catch (e: IOException) {
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

    /**
     * Drops `function_call_output` items whose `call_id` has no matching
     * preceding `function_call` item. Such orphans make /v1/responses reject
     * the whole request with HTTP 400 ("No tool call found for function call
     * output with call_id …") and can still exist in chats persisted by older
     * plugin versions — healing the input instead of failing the turn.
     */
    internal fun sanitize(items: List<JsonElement>): List<JsonElement> {
        val knownCallIds = mutableSetOf<String>()
        val out = ArrayList<JsonElement>(items.size)
        var dropped = 0
        for (item in items) {
            val obj = item as? JsonObject
            when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
                "function_call" -> {
                    obj?.get("call_id")?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let { knownCallIds.add(it) }
                    out.add(item)
                }
                "function_call_output" -> {
                    val callId = obj?.get("call_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (callId !in knownCallIds) {
                        dropped++
                        LOG.warn("TellForgeAI: dropping orphan function_call_output (call_id=$callId) from the request input")
                    } else out.add(item)
                }
                else -> out.add(item)
            }
        }
        if (dropped > 0) LOG.warn("TellForgeAI: dropped $dropped orphan function_call_output item(s) from history")
        return out
    }

    /**
     * Responses-tool format is FLAT: {type:"function", name, description,
     * parameters} — no "function" wrapper like chat/completions.
     */
    private fun flatToolSpec(t: Map<String, Any>): JsonElement {
        val fn = t["function"] as? Map<*, *> ?: t
        return buildJsonObject {
            put("type", "function")
            put("name", fn["name"]?.toString().orEmpty())
            put("description", fn["description"]?.toString().orEmpty())
            (fn["parameters"] as? kotlinx.serialization.json.JsonObject)?.let { put("parameters", it) }
        }
    }

    /**
     * Serializes a [ChatMessage] into one or more Responses-API `input` items.
     * Tool calls become flat `function_call` items (one per call — the API
     * rejects "type":"assistant" items and nested "tool_calls" arrays) and
     * tool results become `function_call_output` items, matching OpenAI's
     * Responses input shape.
     */
    private fun ChatMessage.toInputItems(): List<JsonElement> = when {
        role == "tool" -> listOf(buildJsonObject {
            put("type", "function_call_output")
            put("call_id", toolCallId.ifBlank { "call_unknown" })
            put("output", content)
        })
        toolCalls.isNotEmpty() -> buildList {
            if (content.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                })
            }
            addAll(toolCalls.map { c ->
                buildJsonObject {
                    put("type", "function_call")
                    put("call_id", c.id)
                    put("name", c.name)
                    put("arguments", c.arguments)
                }
            })
        }
        else -> listOf(buildJsonObject {
            put("role", role)
            if (images.isEmpty()) {
                put("content", content)
            } else {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", content)
                    })
                    for (img in images) {
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", img)
                        })
                    }
                })
            }
        })
    }
}
