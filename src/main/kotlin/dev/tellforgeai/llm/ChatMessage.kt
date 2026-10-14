package dev.tellforgeai.llm

import kotlinx.serialization.Serializable

/** One function/tool call requested by the model (OpenAI function-calling format). */
@Serializable
data class ToolCall(
    var id: String = "",
    var name: String = "",
    var arguments: String = "", // raw JSON string of arguments
)

/**
 * One chat message.
 *
 * NOTE: properties must be `var` (not `val`) — IntelliJ's settings XML
 * serializer cannot populate immutable properties, which silently drops
 * them from tellforgeai.chats.xml (see ProviderProfile).
 */
@Serializable
data class ChatMessage(
    var role: String = "user",
    var content: String = "",
    /** For assistant messages that requested tool calls. */
    var toolCalls: List<ToolCall> = emptyList(),
    /** For role == "tool" messages: the id of the call this is a result for. */
    var toolCallId: String = "",
    /** Base64 PNG data URLs of images pasted with this message (user messages only). */
    var images: List<String> = emptyList(),
    /** Project-relative (or absolute) paths of images the user referenced via
     *  @path tokens. The full-resolution payloads live in [images]; this list
     *  only records *which files* were referenced, for display and history. */
    var referencedPaths: List<String> = emptyList(),
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)

        /** Assistant message that requested one or more tool calls. */
        fun assistantWithToolCalls(content: String, calls: List<ToolCall>) =
            ChatMessage("assistant", content, toolCalls = calls)

        /** Tool result message fed back to the model. */
        fun toolResult(callId: String, content: String) =
            ChatMessage("tool", content, toolCallId = callId)
    }
}
