package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure routing decisions of [WebSearchTool] — no network, no IntelliJ services.
 */
class WebSearchRoutingTest {

    // ------------------------------------------------------------ normalize

    @Test
    fun `normalize accepts known providers`() {
        assertEquals("brave", WebSearchTool.normalizeProvider("Brave"))
        assertEquals("duckduckgo", WebSearchTool.normalizeProvider("duckduckgo"))
        assertEquals("duckduckgo", WebSearchTool.normalizeProvider("DuckDuckGo"))
    }

    @Test
    fun `normalize maps unknown or blank values to null`() {
        assertNull(WebSearchTool.normalizeProvider(null))
        assertNull(WebSearchTool.normalizeProvider(""))
        assertNull(WebSearchTool.normalizeProvider("auto"))
        assertNull(WebSearchTool.normalizeProvider("bing"))
    }

    // ------------------------------------------------------------ otherOf

    @Test
    fun `otherOf returns the opposite provider`() {
        assertEquals("duckduckgo", WebSearchTool.otherOf("brave"))
        assertEquals("brave", WebSearchTool.otherOf("duckduckgo"))
    }

    // ------------------------------------------------------------ resolveRequest

    @Test
    fun `no provider arg uses the primary with no notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = null, fallbackEnabled = true, braveAvailable = true,
        )
        assertEquals("duckduckgo", start)
        assertNull(notice)
    }

    @Test
    fun `provider arg equal to primary keeps primary with no notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "brave", requested = "brave", fallbackEnabled = true, braveAvailable = true,
        )
        assertEquals("brave", start)
        assertNull(notice)
    }

    @Test
    fun `provider arg for the other available provider is honored as LLM fallback`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", fallbackEnabled = true, braveAvailable = true,
        )
        assertEquals("brave", start)
        assertTrue(notice!!.contains("explicitly requested"))
    }

    @Test
    fun `provider arg for unavailable brave falls back to primary with denial notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", fallbackEnabled = true, braveAvailable = false,
        )
        assertEquals("duckduckgo", start)
        assertTrue(notice!!.contains("unavailable"))
    }

    @Test
    fun `provider arg is honored even when fallback is disabled`() {
        // LLM-initiated fallback is an explicit request; the flag governs the
        // automatic code layer, not explicit requests.
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", fallbackEnabled = false, braveAvailable = true,
        )
        assertEquals("brave", start)
        assertTrue(notice!!.contains("explicitly requested"))
    }
}
