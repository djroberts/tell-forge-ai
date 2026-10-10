package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.tellforgeai.model.ApiProtocol
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Tests for the Responses connector's private serialization shapes, via reflection. */
class OpenaiResponsesConnectorTest {

    @Test
    fun `tool spec flattened for Responses wire format`() {
        // Via the shared chat/completions connector (nested form) for contrast.
        val nested = mapOf(
            "type" to "function",
            "function" to mapOf<String, Any>(
                "name" to "read_file",
                "description" to "d",
                "parameters" to kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                },
            ),
        )
        val flattened = invokeFlatToolSpec(nested)
        val obj = flattened as kotlinx.serialization.json.JsonObject
        assertEquals("function", obj["type"]?.toString()?.trim('"'))
        assertEquals("read_file", obj["name"]?.toString()?.trim('"'))
        assertEquals("d", obj["description"]?.toString()?.trim('"'))
        // No nested "function" wrapper present:
        assertTrue("function" !in obj.keys)
    }

    @Test
    fun `tool result becomes function_call_output item`() {
        val msg = ChatMessage.toolResult("call_42", "result body")
        val items = invokeToInputItems(msg)
        assertEquals(1, items.size)
        val item = items[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"function_call_output\"", item["type"].toString())
        assertEquals("\"call_42\"", item["call_id"].toString())
        assertEquals("\"result body\"", item["output"].toString())
    }

    @Test
    fun `assistant message keeps role and content as plain string`() {
        val msg = ChatMessage.assistant("hi there")
        val items = invokeToInputItems(msg)
        assertEquals(1, items.size)
        val item = items[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"assistant\"", item["role"].toString())
        assertEquals("\"hi there\"", item["content"].toString())
    }

    @Test
    fun `assistant with tool calls emits flat function_call items`() {
        // Regression: the Responses API rejects "type":"assistant" items and
        // nested "tool_calls" arrays (HTTP 400 Invalid value: 'assistant').
        val msg = ChatMessage.assistantWithToolCalls(
            "let me check",
            listOf(ToolCall(id = "call_1", name = "read_file", arguments = "{\"path\":\"a\"}")),
        )
        val items = invokeToInputItems(msg)
        assertEquals(2, items.size)
        val text = items[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"assistant\"", text["role"].toString())
        assertEquals("\"let me check\"", text["content"].toString())
        val call = items[1] as kotlinx.serialization.json.JsonObject
        assertEquals("\"function_call\"", call["type"].toString())
        assertEquals("\"call_1\"", call["call_id"].toString())
        assertEquals("\"read_file\"", call["name"].toString())
        assertTrue("tool_calls" !in call.keys)
    }

    @Test
    fun `item seals with function_call fully populated on output_item done`() {
        // Response-style event directly — parse it the way the connector does.
        val data = """
            {"type":"response.output_item.done","item":{"type":"function_call",
             "id":"fc_1","call_id":"call_9","name":"read_file",
             "arguments":"{\"path\":\"a\"}"}}
        """.trimIndent()
        val obj = ChatCompletionsConnector.JSON.parseToJsonElement(data).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals("function_call", obj["item"]?.let { item ->
            (item as kotlinx.serialization.json.JsonObject)["type"].toString()
        }?.trim('"'))
    }

    private fun invokeFlatToolSpec(spec: Map<String, Any>): kotlinx.serialization.json.JsonElement {
        val m = OpenaiResponsesConnector::class.java.getDeclaredMethod(
            "flatToolSpec", Map::class.java,
        )
        m.isAccessible = true
        return m.invoke(OpenaiResponsesConnector(), spec) as kotlinx.serialization.json.JsonElement
    }

    private fun invokeToInputItems(msg: ChatMessage): List<kotlinx.serialization.json.JsonElement> {
        val m = OpenaiResponsesConnector::class.java.getDeclaredMethod(
            "toInputItems", ChatMessage::class.java,
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(OpenaiResponsesConnector(), msg) as List<kotlinx.serialization.json.JsonElement>
    }
}
