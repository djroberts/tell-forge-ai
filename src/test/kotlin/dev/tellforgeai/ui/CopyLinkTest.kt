package dev.tellforgeai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyLinkTest {

    @Test
    fun `href round-trips arbitrary text including html-unsafe characters`() {
        for (text in listOf(
            "lt & gt \" quotes' <tags>\nlines",
            "gradlew test",
            "unicode ⧉ é 中文",
        )) {
            val html = CopyLink.html(text)
            val href = Regex("href='([^']+)'").find(html)!!.groupValues[1]
            assertEquals(text, CopyLink.textFromHref(href))
        }
    }

    @Test
    fun `html contains no quotes needing escaping and no newlines in href`() {
        val html = CopyLink.html("line\n\nline & <b>bold</b>")
        val href = Regex("href='([^']+)'").find(html)!!.groupValues[1]
        assertFalse(href.contains('\n'))
        assertFalse(href.contains('\''))
        assertFalse(href.contains('<'))
        assertFalse(href.contains('>'))
        assertFalse(html.contains('"'))
    }

    @Test
    fun `link is right-aligned inside the block`() {
        val html = CopyLink.html("x")
        assertTrue(html.contains("align='right'"))
    }

    @Test
    fun `foreign or malformed hrefs yield null`() {
        assertNull(CopyLink.textFromHref("https://example.com"))
        assertNull(CopyLink.textFromHref("tellforgeai:copy:!!!not-base64url!!!"))
        assertNull(CopyLink.textFromHref("tellforgeai:copy:"))
    }

    @Test
    fun `href works for a 2000-char truncated tool output`() {
        val text = "x".repeat(2000)
        val href = Regex("href='([^']+)'").find(CopyLink.html(text))!!.groupValues[1]
        assertEquals(text, CopyLink.textFromHref(href))
    }
}
