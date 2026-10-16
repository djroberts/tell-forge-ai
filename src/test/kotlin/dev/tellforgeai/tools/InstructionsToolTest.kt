package dev.tellforgeai.tools

import dev.tellforgeai.llm.InstructionsRepoService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The instructions tool surface must be a single document: the model never
 * sees a path or repo layout, and any legacy/rogue attempt to write a file
 * other than the canonical one is rejected before the service is touched.
 * Expectations reference [InstructionsRepoService.CANONICAL_FILE] so renames
 * of the canonical file don't break these tests.
 */
class InstructionsToolTest {

    private fun pathArg(value: String) = buildJsonObject { put("path", value) }

    @Test
    fun `absent path defaults to canonical`() {
        assertEquals(InstructionsRepoService.CANONICAL_FILE, InstructionsTool.canonicalOrReject(null))
    }

    @Test
    fun `canonical path in any case or with whitespace is accepted`() {
        assertEquals(InstructionsRepoService.CANONICAL_FILE, InstructionsTool.canonicalOrReject("INSTRUCTIONS.md"))
        assertEquals(
            InstructionsRepoService.CANONICAL_FILE,
            InstructionsTool.canonicalOrReject(" instructions.md "),
        )
    }

    @Test
    fun `any other path is rejected`() {
        assertNull(InstructionsTool.canonicalOrReject("AGENTS.md"))
        assertNull(InstructionsTool.canonicalOrReject("../INSTRUCTIONS.md"))
        assertNull(InstructionsTool.canonicalOrReject("sub/INSTRUCTIONS.md"))
        assertNull(InstructionsTool.canonicalOrReject(""))
    }

    @Test
    fun `path extracted from tool args follows the same rule`() {
        // exactly what InstructionsTool.write does with the raw args
        assertNull(InstructionsTool.canonicalOrReject(pathArg("AGENTS.md")["path"]?.jsonPrimitive?.content))
        assertEquals(
            InstructionsRepoService.CANONICAL_FILE,
            InstructionsTool.canonicalOrReject(pathArg("INSTRUCTIONS.md")["path"]?.jsonPrimitive?.content),
        )
    }

    @Test
    fun `args without a path field pass the guard`() {
        val args = buildJsonObject { put("content", "new instructions") }
        assertEquals(
            InstructionsRepoService.CANONICAL_FILE,
            InstructionsTool.canonicalOrReject(args["path"]?.jsonPrimitive?.content),
        )
    }
}
