package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `splitPipeline respects quotes and escapes`() {
        assertEquals(
            listOf("grep -n \"a|b\" file", "head -5"),
            CommandTool.splitPipeline("grep -n \"a|b\" file | head -5"),
        )
        assertEquals(
            listOf("grep 'x|y' f"),
            CommandTool.splitPipeline("grep 'x|y' f"),
        )
        assertEquals(
            listOf("echo | abc"), // escaped pipe is a literal, not an operator: no split
            CommandTool.splitPipeline("echo \\| abc").map { it.trim().replace("\\|", "|") },
        )
    }

    private fun String.removeEscapesForTest() = replace("\\|", "|")

    @Test
    fun `safe pipeline into head is allowed in read-only mode`() {
        assertNull(CommandTool.readOnlyRejection("grep -n \"knobCell\" src/Editor.cpp | head -20"))
    }

    @Test
    fun `pipeline into wc and sort is allowed in read-only mode`() {
        assertNull(CommandTool.readOnlyRejection("git log --oneline | wc -l"))
        assertNull(CommandTool.readOnlyRejection("cat build.gradle | sort"))
    }

    @Test
    fun `pipe inside quotes is literal and does not block`() {
        assertNull(CommandTool.readOnlyRejection("grep \"a|b\" README.md"))
        assertNull(CommandTool.readOnlyRejection("grep -n 'x|y' file.md | tail -3"))
    }

    @Test
    fun `pipeline into non-allowlisted tail is rejected`() {
        assertNotNull(CommandTool.readOnlyRejection("grep -n x f | xargs cat"))
        assertNotNull(CommandTool.readOnlyRejection("grep -n x f | grep y | tee out"))
    }

    @Test
    fun `pipeline into forbidden command is rejected`() {
        assertNotNull(CommandTool.readOnlyRejection("grep -n x f | ripple rm -rf /"))
    }

    @Test
    fun `redirect is still rejected in read-only mode`() {
        assertNotNull(CommandTool.readOnlyRejection("grep -n x f > out.txt"))
        assertNotNull(CommandTool.readOnlyRejection("grep -n x f | head > out.txt"))
    }

    @Test
    fun `chaining and substitution are still rejected`() {
        assertNotNull(CommandTool.readOnlyRejection("ls; cat f"))
        assertNotNull(CommandTool.readOnlyRejection("grep x f && echo done"))
        assertNotNull(CommandTool.readOnlyRejection("echo `cat f`"))
        assertNotNull(CommandTool.readOnlyRejection("echo \$(cat f)"))
    }

    @Test
    fun `first stage must still be read-only`() {
        assertNotNull(CommandTool.readOnlyRejection("git add f | head -3"))
    }

    @Test
    fun `quoted pipe with escaped backslash pair`() {
        nullableAndAssert(CommandTool.readOnlyRejection("grep -n \"a\\\\|b\" f"))
    }

    private fun nullableAndAssert(rejection: String?) {
        assertNull(rejection)
    }
}
