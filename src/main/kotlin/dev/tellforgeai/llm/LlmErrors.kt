package dev.tellforgeai.llm

/**
 * Pure, testable classification of provider failure messages as delivered
 * through [LlmClient.Event.Error].
 *
 * Both wire connectors prefix HTTP failures with "HTTP <code> ..." and pass
 * exception messages through raw (timeouts, resets, EOF), so status-driven
 * decisions parse that prefix; body-text signals are checked first because a
 * 400 can still be a context overflow on some providers.
 *
 * Classification drives the retry policy in ChatEngine: transient classes may
 * be retried with bounded backoff; CONTEXT_TOO_LARGE must never be retried
 * blindly (the fix is re-trimming, once); INVALID/OTHER must surface to the
 * user unchanged — retrying a deterministic failure just burns credits.
 */
object LlmErrors {

    enum class Kind {
        /** 429 / rate-limited — safe to retry after backoff. */
        TRANSIENT_RATE_LIMIT,

        /** 529 / "overloaded" — provider capacity, safe to retry after backoff. */
        TRANSIENT_OVERLOADED,

        /** 502/503/504 and other 5xx — gateway/capacity, safe to retry. */
        GATEWAY,

        /** Timeout / connection reset / EOF before any delta — safe to retry. */
        NETWORK,

        /** Request exceeded the model's context window — fix by re-trimming, once. */
        CONTEXT_TOO_LARGE,

        /** Deterministic request failure (bad model name, wrong key, bad tool spec). */
        INVALID,

        /** Recognized but unclassifiable failure — surface, don't retry. */
        OTHER,
    }

    private val CONTEXT_SIGNALS = listOf(
        "context_length_exceeded",
        "maximum context length",
        "context length",
        "context window",
        "too many tokens",
        "reduce the length",
        "prompt is too long",
    )

    private val OVERLOADED_SIGNALS = listOf(
        "overloaded_error",
        "overloaded",
        "try again later",
    )

    private val RATE_SIGNALS = listOf(
        "rate limit",
        "rate_limit",
        "too many requests",
        "quota",
    )

    private val NETWORK_SIGNALS = listOf(
        "timeout",
        "timed out",
        "connection",
        "econn",
        "unexpected eof",
        "unexpected end of stream",
        "broken pipe",
        "reset by peer",
    )

    private val HTTP_STATUS = Regex("http (\\d{3})")

    /** Classifies an [LlmClient.Event.Error] message. */
    fun classify(message: String): Kind {
        val m = message.lowercase()
        val status = HTTP_STATUS.find(m)?.groupValues?.get(1)?.toIntOrNull()

        // Body-text signals first: providers vary in which status they pick.
        if (CONTEXT_SIGNALS.any { it in m }) return Kind.CONTEXT_TOO_LARGE
        if (status == 529 || OVERLOADED_SIGNALS.any { it in m }) return Kind.TRANSIENT_OVERLOADED
        if (status == 429 || RATE_SIGNALS.any { it in m }) return Kind.TRANSIENT_RATE_LIMIT
        if (status in 500..599) return Kind.GATEWAY
        if (status == 413) return Kind.CONTEXT_TOO_LARGE
        if (status == 400 || status == 404 || status == 422) return Kind.INVALID
        if (NETWORK_SIGNALS.any { it in m }) return Kind.NETWORK
        return if (status != null) Kind.OTHER else Kind.NETWORK
    }

    /** Whether [kind] is worth an automatic retry at all. */
    fun retryable(kind: Kind): Boolean = when (kind) {
        Kind.TRANSIENT_RATE_LIMIT, Kind.TRANSIENT_OVERLOADED, Kind.GATEWAY, Kind.NETWORK -> true
        Kind.CONTEXT_TOO_LARGE, Kind.INVALID, Kind.OTHER -> false
    }

    /**
     * Backoff for the [attempt]-th retry (1-based), exponential with jitter:
     * 1s, 2s, 4s … capped at 8s, plus 0–250 ms jitter to avoid synchronized
     * client storms against a recovering provider.
     */
    fun backoffMs(attempt: Int, jitter: () -> Int = { (0..250).random() }): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8_000L) + jitter().toLong()
}
