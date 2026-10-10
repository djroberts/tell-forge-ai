package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextLimitParserTest {

    @Test
    fun `exact id match with standard field`() {
        val json = """{"data":[{"id":"gpt-6-luna","context_length":131072}]}"""
        assertEquals(131_072, ContextLimitParser.fromModelsJson(json, "gpt-6-luna"))
    }

    @Test
    fun `namespaced catalog id matches configured model`() {
        val json = """{"data":[{"id":"openai/gpt-6-luna","max_model_len":200000}]}"""
        assertEquals(200_000, ContextLimitParser.fromModelsJson(json, "gpt-6-luna"))
        assertEquals(200_000, ContextLimitParser.fromModelsJson(json, "openai/gpt-6-luna"))
    }

    @Test
    fun `substring fallback for suffixed deployment names`() {
        val json = """{"data":[{"id":"gpt-6-luna-2025-01-01","context_window":128000}]}"""
        assertEquals(128_000, ContextLimitParser.fromModelsJson(json, "gpt-6-luna"))
    }

    @Test
    fun `must not match a different revision when exact or same-id match is available`() {
        val json = """{"data":[{"id":"gpt-5.6-luna","context_length":999999},{"id":"gpt-6-luna","context_length":128000}]}"""
        assertEquals(128_000, ContextLimitParser.fromModelsJson(json, "gpt-6-luna"))
    }

    @Test
    fun `rejects unmatched model and unset fields`() {
        val json = """{"data":[{"id":"gpt-6-luna","context_length":1024}]}"""
        assertNull(ContextLimitParser.fromModelsJson(json, "gpt-6-luna")) // 1024 = unset marker
        assertNull(ContextLimitParser.fromModelsJson(json, "gpt-6-sol"))
        assertNull(ContextLimitParser.fromModelsJson("not json", "gpt-6-luna"))
        assertNull(ContextLimitParser.fromModelsJson(json, ""))
    }

    @Test
    fun `valid bounds`() {
        assertNull(ContextLimitParser.valid(null))
        assertNull(ContextLimitParser.valid(1_000))   // below MIN
        assertNull(ContextLimitParser.valid(20_000_000)) // above MAX
        assertEquals(128_000, ContextLimitParser.valid(128_000))
    }

    @Test
    fun `single entry text parses the same keys`() {
        assertEquals(128_000, ContextLimitParser.fromModelEntryText("""{"id":"gpt-6-luna","context_length":128000}"""))
        assertNull(ContextLimitParser.fromModelEntryText("""{"id":"gpt-6-luna","input_cost":1.5}"""))
        assertNull(ContextLimitParser.fromModelEntryText(null))
        assertNull(ContextLimitParser.fromModelEntryText("prose"))
    }
}
