package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [ExternalToolProvider] extension-point contract. The
 * ExtensionPointName wiring itself requires the platform (verified via
 * runIde/buildPlugin); here we test the pure-logic parts: the default
 * [ExternalToolProvider.handles] derivation and the spec shape providers
 * must emit.
 */
class ExternalToolProviderTest {

    /** Minimal spec in the same shape ToolRegistry.spec emits. */
    private fun spec(name: String): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to "test",
            "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
    )

    private class FakeProvider(private val names: List<String>) : ExternalToolProvider {
        override fun specs(
            project: com.intellij.openapi.project.Project?,
            planMode: Boolean,
        ): List<Map<String, Any>> = names.map { specFor(it) }

        override fun execute(
            project: com.intellij.openapi.project.Project,
            name: String,
            args: kotlinx.serialization.json.JsonObject,
            planMode: Boolean,
            chatId: String?,
        ): String = "executed:$name"

        private fun specFor(name: String): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to "test",
                "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
        )
    }

    @Test
    fun handlesDerivesNamesFromSpecs() {
        val provider = FakeProvider(listOf("cms_search", "cms_push"))
        assertTrue(provider.handles("cms_search", project = null))
        assertTrue(provider.handles("cms_push", project = null))
        assertFalse(provider.handles("read_file", project = null))
        assertFalse(provider.handles("unknown", project = null))
    }

    @Test
    fun providerWithNoSpecsHandlesNothing() {
        val provider = FakeProvider(emptyList())
        assertFalse(provider.handles("anything", project = null))
    }

    @Test
    fun specShapeIsOpenAiFunctionCalling() {
        val s = spec("cms_search")["function"] as Map<*, *>
        assertEquals("cms_search", s["name"])
        assertEquals("test", s["description"])
        assertTrue(s.containsKey("parameters"))
    }
}
