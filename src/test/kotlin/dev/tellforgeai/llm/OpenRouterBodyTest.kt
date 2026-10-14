package dev.tellforgeai.llm

import dev.tellforgeai.model.ApiProtocol
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.model.injectOpenRouterBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenRouterBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `empty OpenRouter profile injects nothing`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        assertNull("provider should be absent", body["provider"])
        assertNull("plugins should be absent", body["plugins"])
    }

    @Test
    fun `non-OpenRouter profile ignores OpenRouter fields`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            openRouterProviderOrder = "anthropic,openai",
            openRouterAppTitle = "MyApp",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        assertNull("provider should be absent for non-OpenRouter", body["provider"])
        assertEquals("{}", body.toString())
    }

    @Test
    fun `injects provider order and allow_fallbacks`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterProviderOrder = "anthropic, openai, google",
            openRouterAllowFallbacks = false,
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val provider = body["provider"]?.jsonObject
        assertNotNull("provider object missing", provider)
        val order = provider!!["order"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
        assertEquals(listOf("anthropic", "openai", "google"), order)
        assertEquals(false, provider["allow_fallbacks"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `injects sort, quantizations, data_collection`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterProviderSort = "throughput",
            openRouterQuantizations = "fp8, bf16",
            openRouterDataCollection = "deny",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val provider = body["provider"]?.jsonObject!!
        assertEquals("throughput", provider["sort"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            listOf("fp8", "bf16"),
            provider["quantizations"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals("deny", provider["data_collection"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `injects zdr and require_parameters`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterZdr = true,
            openRouterRequireParameters = true,
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val provider = body["provider"]?.jsonObject!!
        assertEquals(true, provider["zdr"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, provider["require_parameters"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `injects max_price`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterMaxPricePrompt = 0.5,
            openRouterMaxPriceCompletion = 1.5,
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val price = body["provider"]?.jsonObject?.get("max_price")?.jsonObject!!
        assertEquals(0.5, price["prompt"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.001)
        assertEquals(1.5, price["completion"]?.jsonPrimitive?.doubleOrNull ?: 0.0, 0.001)
    }

    @Test
    fun `injects plugins JSON array`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterPluginsJson = """[{"id":"web"},{"id":"response_healing"}]""",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val plugins = body["plugins"]?.jsonArray!!
        assertEquals(2, plugins.size)
        assertEquals("web", plugins[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `injects extra body JSON`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterExtraBodyJson = """{"transforms":["middle-out"],"route":"fallback"}""",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        assertEquals(
            "middle-out",
            body["transforms"]?.jsonArray?.first()?.jsonPrimitive?.contentOrNull
        )
        assertEquals("fallback", body["route"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `injects preferred_min_throughput and preferred_max_latency`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterPreferredMinThroughput = """{"p50":100,"p90":50}""",
            openRouterPreferredMaxLatency = """{"p50":1,"p90":3,"p99":5}""",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val provider = body["provider"]?.jsonObject!!
        val thr = provider["preferred_min_throughput"]?.jsonObject!!
        assertEquals(100, thr["p50"]?.jsonPrimitive?.intOrNull)
        assertEquals(50, thr["p90"]?.jsonPrimitive?.intOrNull)
        val lat = provider["preferred_max_latency"]?.jsonObject!!
        assertEquals(1, lat["p50"]?.jsonPrimitive?.intOrNull)
        assertEquals(3, lat["p90"]?.jsonPrimitive?.intOrNull)
        assertEquals(5, lat["p99"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `injects ignore and only provider lists`() {
        val profile = ProviderProfile(
            name = "test",
            apiProtocol = ApiProtocol.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            openRouterProviderIgnore = "deepinfra, novita",
            openRouterProviderOnly = "anthropic",
        )
        val body = buildJsonObject { injectOpenRouterBody(profile) }
        val provider = body["provider"]?.jsonObject!!
        assertEquals(
            listOf("deepinfra", "novita"),
            provider["ignore"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("anthropic"),
            provider["only"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
    }
}
