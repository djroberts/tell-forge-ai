package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for [SentryTool.translateQuery]: messy free-text agent
 * queries must be translated into Sentry search syntax (bare words quoted,
 * known `field:value` filters and quoted phrases preserved) rather than
 * rejected.
 */
class SentryToolTest {

    @Test
    fun emptyQueryStaysEmpty() {
        assertEquals("", SentryTool.translateQuery(""))
        assertEquals("", SentryTool.translateQuery("   "))
    }

    @Test
    fun bareWordsAreQuotedAsMessageSearch() {
        assertEquals("\"null\" \"pointer\"", SentryTool.translateQuery("null pointer"))
    }

    @Test
    fun singleWordIsQuoted() {
        assertEquals("\"timeout\"", SentryTool.translateQuery("timeout"))
    }

    @Test
    fun knownFiltersPassThrough() {
        assertEquals(
            "is:unresolved level:error",
            SentryTool.translateQuery("is:unresolved level:error"),
        )
    }

    @Test
    fun mixedFiltersAndFreeText() {
        assertEquals(
            "level:error \"payment\" \"failed\"",
            SentryTool.translateQuery("level:error payment failed"),
        )
    }

    @Test
    fun quotedPhrasesArePreserved() {
        assertEquals(
            "\"connection refused\" environment:production",
            SentryTool.translateQuery("\"connection refused\" environment:production"),
        )
    }

    @Test
    fun dottedFilterNamesPassThrough() {
        assertEquals(
            "browser.name:Chrome",
            SentryTool.translateQuery("browser.name:Chrome"),
        )
    }

    @Test
    fun negatedFilterPassesThrough() {
        assertEquals(
            "!has:user \"crash\"",
            SentryTool.translateQuery("!has:user crash"),
        )
    }

    @Test
    fun whitespaceIsNormalised() {
        assertEquals("\"a\" \"b\"", SentryTool.translateQuery("  a    b  "))
    }
}
