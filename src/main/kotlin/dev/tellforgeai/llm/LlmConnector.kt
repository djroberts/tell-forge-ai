package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile

/**
 * A wire-protocol connector. Implemented once per [ApiProtocol]
 * family; [LlmClient] dispatches to the right one based on the
 * profile's configured protocol.
 */
interface LlmConnector {
    /** True while a request of this connector is in flight. */
    fun isRunning(): Boolean

    /**
     * Runs [action] once no stream is active on this connector. If a stream is
     * running (its job still finishing after [LlmClient.Event.Done] was
     * delivered) [action] is deferred, preventing the follow-up turn after
     * tool results from being silently dropped by the `isRunning()` guard.
     */
    fun whenIdle(action: () -> Unit)

    fun stream(
        profile: ProviderProfile,
        planMode: Boolean,
        history: List<ChatMessage>,
        tools: List<Map<String, Any>>,
        protocol: ApiProtocol,
        onEvent: (LlmClient.Event) -> Unit,
    )
}
