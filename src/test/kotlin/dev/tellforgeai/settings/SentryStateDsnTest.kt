package dev.tellforgeai.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentryStateDsnTest {

    @Test
    fun hostedDsnWithOrgAndProject() {
        val dsn = "https://abc123@o456789.ingest.sentry.io/4505234567890123"
        val (org, project) = SentryState.parseDsn(dsn)!!
        assertEquals("o456789", org)
        assertEquals("4505234567890123", project)
    }

    @Test
    fun whitespaceIsTrimmed() {
        val (org, project) = SentryState.parseDsn("  https://k@o1.ingest.sentry.io/42  ")!!
        assertEquals("o1", org)
        assertEquals("42", project)
    }

    @Test
    fun nonUrlIsRejected() {
        assertNull(SentryState.parseDsn("just-a-slug"))
        assertNull(SentryState.parseDsn(""))
    }

    @Test
    fun urlWithoutProjectPathIsRejected() {
        assertNull(SentryState.parseDsn("https://abc123@o456789.ingest.sentry.io"))
    }

    @Test
    fun hostWithoutOrgHintIsRejected() {
        // Self-hosted DSNs have no "o<id>" host prefix; without it we cannot
        // infer the org, so the hint is refused rather than guessed.
        assertNull(SentryState.parseDsn("https://abc123@sentry.example.com/4505"))
    }
}
