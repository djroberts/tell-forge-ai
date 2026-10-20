package dev.tellforgeai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmErrorsTest {

    // --- classification ---

    @Test
    fun `rate limit errors classify as transient`() {
        assertEquals(LlmErrors.Kind.TRANSIENT_RATE_LIMIT, LlmErrors.classify("HTTP 429 from api.example.com/v1/chat/completions: rate limit exceeded"))
        assertEquals(LlmErrors.Kind.TRANSIENT_RATE_LIMIT, LlmErrors.classify("HTTP 400: Too Many Requests"))
        assertEquals(LlmErrors.Kind.TRANSIENT_RATE_LIMIT, LlmErrors.classify("HTTP 500: you hit your quota"))
    }

    @Test
    fun `overloaded errors classify as transient`() {
        assertEquals(LlmErrors.Kind.TRANSIENT_OVERLOADED, LlmErrors.classify("HTTP 529: overloaded_error"))
        assertEquals(LlmErrors.Kind.TRANSIENT_OVERLOADED, LlmErrors.classify("HTTP 503: provider is overloaded, try again later"))
    }

    @Test
    fun `gateway errors classify from 5xx status`() {
        assertEquals(LlmErrors.Kind.GATEWAY, LlmErrors.classify("HTTP 502 from api.example.com: bad gateway"))
        assertEquals(LlmErrors.Kind.GATEWAY, LlmErrors.classify("HTTP 503 from api.internal: service unavailable"))
        assertEquals(LlmErrors.Kind.GATEWAY, LlmErrors.classify("HTTP 504: gateway timeout"))
    }

    @Test
    fun `network failures classify as retryable network errors`() {
        assertEquals(LlmErrors.Kind.NETWORK, LlmErrors.classify("timeout"))
        assertEquals(LlmErrors.Kind.NETWORK, LlmErrors.classify("Connection reset by peer"))
        assertEquals(LlmErrors.Kind.NETWORK, LlmErrors.classify("unexpected EOF while reading"))
        assertEquals(LlmErrors.Kind.NETWORK, LlmErrors.classify("broken pipe"))
        assertEquals(LlmErrors.Kind.NETWORK, LlmErrors.classify("Some totally unclassifiable failure with no status"))
    }

    @Test
    fun `context overflow is never blindly retryable`() {
        assertEquals(LlmErrors.Kind.CONTEXT_TOO_LARGE, LlmErrors.classify("HTTP 400: This model's maximum context length is 8192 tokens"))
        assertEquals(LlmErrors.Kind.CONTEXT_TOO_LARGE, LlmErrors.classify("HTTP 400: context_length_exceeded"))
        assertEquals(LlmErrors.Kind.CONTEXT_TOO_LARGE, LlmErrors.classify("HTTP 413: payload too large"))
        assertEquals(LlmErrors.Kind.CONTEXT_TOO_LARGE, LlmErrors.classify("HTTP 200: prompt is too long"))
        assertFalse(LlmErrors.retryable(LlmErrors.Kind.CONTEXT_TOO_LARGE))
    }

    @Test
    fun `deterministic request errors are never retried`() {
        assertEquals(LlmErrors.Kind.INVALID, LlmErrors.classify("HTTP 400: model gpt-nonexistent does not exist"))
        // 401 has no dedicated kind — it is recognized-but-unclassifiable, and never retried.
        assertEquals(LlmErrors.Kind.OTHER, LlmErrors.classify("HTTP 401: invalid api key"))
        assertEquals(LlmErrors.Kind.INVALID, LlmErrors.classify("HTTP 404: no such model"))
        assertFalse(LlmErrors.retryable(LlmErrors.Kind.INVALID))
        assertFalse(LlmErrors.retryable(LlmErrors.Kind.OTHER))
    }

    // --- gating ---

    @Test
    fun `retryable kinds are exactly the transient ones`() {
        assertTrue(LlmErrors.retryable(LlmErrors.Kind.TRANSIENT_RATE_LIMIT))
        assertTrue(LlmErrors.retryable(LlmErrors.Kind.TRANSIENT_OVERLOADED))
        assertTrue(LlmErrors.retryable(LlmErrors.Kind.GATEWAY))
        assertTrue(LlmErrors.retryable(LlmErrors.Kind.NETWORK))
    }

    // --- backoff ---

    @Test
    fun `backoff grows exponentially and stays bounded`() {
        assertEquals(1_000L, LlmErrors.backoffMs(1) { 0 })
        assertEquals(2_000L, LlmErrors.backoffMs(2) { 0 })
        assertEquals(4_000L, LlmErrors.backoffMs(3) { 0 })
        assertEquals(8_000L, LlmErrors.backoffMs(4) { 0 })
        assertEquals(8_000L, LlmErrors.backoffMs(50) { 0 }) // capped
        assertTrue(LlmErrors.backoffMs(1) in 1_000L..1_250L) // jitter range
    }
}
