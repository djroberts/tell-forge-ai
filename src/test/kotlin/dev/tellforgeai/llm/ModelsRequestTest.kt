package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsRequestTest {

    @Test
    fun `models request uses unsaved key when supplied`() {
        val request = ChatCompletionsConnector.modelsRequest(
            profile = ProviderProfile(baseUrl = "https://example.com/v1"),
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            apiKey = "unsaved-secret",
        )

        assertEquals("Bearer unsaved-secret", request.header("Authorization"))
    }

    @Test
    fun `models request omits authorization when explicitly blank key supplied`() {
        val request = ChatCompletionsConnector.modelsRequest(
            profile = ProviderProfile(baseUrl = "https://example.com/v1"),
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            apiKey = "",
        )

        assertNull(request.header("Authorization"))
    }
}
