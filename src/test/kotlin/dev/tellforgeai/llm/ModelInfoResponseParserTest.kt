package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelInfoResponseParserTest {
    @Test
    fun `parses complete JSON object`() {
        val parsed = ModelInfoResponseParser.parse("""{"context_window":1050000,"confidence":"high"}""")
        assertEquals(1_050_000, parsed?.get("context_window")?.toString()?.toInt())
    }

    @Test
    fun `accepts a single fenced JSON object`() {
        val parsed = ModelInfoResponseParser.parse("""```json
{"context_window":1050000}
```""")
        assertEquals(1_050_000, parsed?.get("context_window")?.toString()?.toInt())
    }

    @Test
    fun `rejects prose surrounding JSON and unrelated objects`() {
        assertNull(ModelInfoResponseParser.parse("""Here is the result: {"context_window":1050000}"""))
        assertNull(ModelInfoResponseParser.parse("""{"unrelated":"value"}"""))
    }

    @Test
    fun `parses decimal shorthand and comma-grouped counts`() {
        assertEquals(1_050_000, ContextWindowNumberParser.parse("1.05m"))
        assertEquals(1_050_000, ContextWindowNumberParser.parse("1,050,000"))
        assertEquals(128_000, ContextWindowNumberParser.parse("128k"))
    }

    @Test
    fun `rejects malformed or out of-range context values`() {
        assertNull(ContextWindowNumberParser.parse("1.05m tokens"))
        assertNull(ContextWindowNumberParser.parse("105m"))
        assertNull(ContextWindowNumberParser.parse("800"))
    }
}
