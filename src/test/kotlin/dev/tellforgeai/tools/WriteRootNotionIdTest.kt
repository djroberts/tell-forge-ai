package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the write-root id normalization bug: Notion returns
 * ids dashed (search results, parent objects) while persisted settings store
 * them dashless. Comparing them string-wise made the ancestry walk report
 * the root itself (and every child of it) as OUTSIDE the configured root,
 * refusing all writes under a root (tellforgeai issue: "parent 'TellForgeAI'
 * is outside it" while the root WAS TellForgeAI).
 */
class WriteRootNotionIdTest {

    private val dashed = "3f9424f0-1ab4-812c-8f56-ed38d1469328"
    private val bare = "3f9424f01ab4812c8f56ed38d1469328"

    // ------------------------------------------------- normalization contract

    @Test
    fun `dashed and bare ids normalize to the same canonical form`() {
        assertEquals(WriteRoot.normalizeNotionId(bare), WriteRoot.normalizeNotionId(dashed))
        assertEquals(bare, WriteRoot.normalizeNotionId(dashed))
        assertEquals(bare, WriteRoot.normalizeNotionId(bare))
    }

    @Test
    fun `normalization is case-insensitive`() {
        assertEquals(bare, WriteRoot.normalizeNotionId(bare.uppercase()))
        assertEquals(
            WriteRoot.normalizeNotionId(dashed.uppercase()),
            WriteRoot.normalizeNotionId(dashed),
        )
    }

    @Test
    fun `normalization idempotent`() {
        val once = WriteRoot.normalizeNotionId(dashed)
        assertEquals(once, WriteRoot.normalizeNotionId(once))
    }

    @Test
    fun `normalization leaves non-id strings untouched apart from dashes`() {
        assertEquals("tellforgeai", WriteRoot.normalizeNotionId("TellForgeAI"))
    }

    // ----------------------------------------------------- parser round-trip

    @Test
    fun `notion root parser accepts dashed id url and picker forms`() {
        assertEquals(bare, WriteRoot.parseNotionTarget(bare))
        assertEquals(bare, WriteRoot.parseNotionTarget(dashed))
        assertEquals(bare, WriteRoot.parseNotionTarget("https://www.notion.so/TellForgeAI-$dashed"))
        assertEquals(bare, WriteRoot.parseNotionTarget("TellForgeAI — [page] $dashed"))
        assertEquals(bare, WriteRoot.parseNotionTarget("TellForgeAI (id $dashed)"))
    }

    @Test
    fun `notion root parser rejects garbage`() {
        assertNull(WriteRoot.parseNotionTarget(""))
        assertNull(WriteRoot.parseNotionTarget("TellForgeAI"))
        assertNull(WriteRoot.parseNotionTarget("not-a-page"))
    }

    // ------------------------------------------------- containment semantics
    //
    // isUnderNotionRoot performs the network walk itself, so full walk tests
    // live with the integration tests; here we pin the invariant the walk
    // must preserve: the root matches ITSELF regardless of dash format.
    // (The pre-fix code compared `pageId == rootId` with mixed formats, so a
    // dashed parent of a dashless root failed this.)

    @Test
    fun `root self-reference is a containment match after normalization`() {
        // Make the equality the walk relies on explicit and format-proof.
        val root = WriteRoot.normalizeNotionId(renderRootId())
        val requested = WriteRoot.normalizeNotionId(renderRequestedId())
        assertTrue(root == requested)
    }

    @Test
    fun `unrelated ids never compare equal even in same format`() {
        assertFalse(
            WriteRoot.normalizeNotionId(renderRootId()) ==
                WriteRoot.normalizeNotionId(renderRequestedId().map { if (it == 'a') 'b' else it }.joinToString("")),
        )
    }

    /** Stand-in for a dashless persisted root id. */
    private fun renderRootId(): String = bare

    /** Stand-in for a dashed id Notion search returns. */
    private fun renderRequestedId(): String = dashed
}
