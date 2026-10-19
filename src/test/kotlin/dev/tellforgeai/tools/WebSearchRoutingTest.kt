package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure routing decisions of [WebSearchTool] — no network, no IntelliJ services.
 *
 * Availability is injected as a `(String) -> Boolean` keyed by provider id;
 * ids follow [SearchProviders.all]: duckduckgo, brave, searxng.
 */
class WebSearchRoutingTest {

    /** Availability: Brave per [brave], SearXNG per [searx], DDG always true. */
    private fun avail(brave: Boolean, searx: Boolean = false) =
        { id: String ->
            when (id) {
                "brave" -> brave
                "searxng" -> searx
                else -> true
            }
        }

    // ------------------------------------------------------------ unknown ids

    @Test
    fun `unknown requested provider yields denial notice with available list`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "bing", available = avail(brave = true),
        )
        assertEquals("duckduckgo", start)
        assertTrue(notice!!.contains("unknown provider"))
        assertTrue(notice.contains("duckduckgo, brave, searxng"))
    }

    // ------------------------------------------------------------ resolveRequest

    @Test
    fun `no provider arg uses the primary with no notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = null, available = avail(brave = true),
        )
        assertEquals("duckduckgo", start)
        assertNull(notice)
    }

    @Test
    fun `provider arg equal to primary keeps primary with no notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "brave", requested = "brave", available = avail(brave = true),
        )
        assertEquals("brave", start)
        assertNull(notice)
    }

    @Test
    fun `provider arg for another available provider is honored as LLM fallback`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", available = avail(brave = true),
        )
        assertEquals("brave", start)
        assertTrue(notice!!.contains("explicitly requested"))
    }

    @Test
    fun `provider arg for searxng is honored when an instance url is configured`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "searxng", available = avail(brave = false, searx = true),
        )
        assertEquals("searxng", start)
        assertTrue(notice!!.contains("explicitly requested"))
    }

    @Test
    fun `provider arg for unavailable searxng falls back to primary with denial notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "searxng", available = avail(brave = true, searx = false),
        )
        assertEquals("duckduckgo", start)
        assertTrue(notice!!.contains("unavailable"))
    }

    @Test
    fun `provider arg for unavailable brave falls back to primary with denial notice`() {
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", available = avail(brave = false),
        )
        assertEquals("duckduckgo", start)
        assertTrue(notice!!.contains("unavailable"))
    }

    @Test
    fun `provider arg is honored even without any configured code fallback`() {
        // LLM-initiated fallback is an explicit request; the fallback-provider
        // setting governs the automatic code layer, not explicit requests.
        val (start, notice) = WebSearchTool.resolveRequest(
            "duckduckgo", requested = "brave", available = avail(brave = true),
        )
        assertEquals("brave", start)
        assertTrue(notice!!.contains("explicitly requested"))
    }
}
