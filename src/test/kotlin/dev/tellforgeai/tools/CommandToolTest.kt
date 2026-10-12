package dev.tellforgeai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandToolTest {

    @Test
    fun `git ls-files is allowed in Foretell mode`() {
        assertTrue(CommandTool.isReadOnlyCommand("git ls-files docs"))
    }

    @Test
    fun `Foretell rejection lists all allowed prefixes`() {
        val message = CommandTool.planModeRejection("git add docs")

        assertTrue(message.contains("git ls-files"))
        assertTrue(message.contains("'git add docs' was NOT executed"))
    }

    @Test
    fun `unlisted commands are not read-only`() {
        assertFalse(CommandTool.isReadOnlyCommand("git add docs"))
    }
}
