package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IgnoreRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // Source discovery / fallback
    // ------------------------------------------------------------------

    @Test
    fun `no ignore files falls back to built-in defaults`() {
        val r = rules()
        assertTrue(r.sources.single().contains("built-in"))
        assertTrue(r.isPathIgnored("node_modules/foo/index.js", false))
        assertTrue(r.isPathIgnored("config/.env", false))
        assertTrue(r.isPathIgnored("deploy.pem", false))
        assertTrue(r.isPathIgnored("app/secret.pem", false))
        assertTrue(r.isPathIgnored("app/secrets/key.txt", false))
        assertFalse(r.isPathIgnored("src/Secrets.kt", false))
    }

    @Test
    fun `recognized ai ignore files are discovered alongside gitignore`() {
        write(".cursorignore", "emails/\n")
        write(".aiderignore", "api-keys.xml\n")
        write(".gitignore", "build/\n")
        val r = rules()
        // order is: .gitignore first, remaining AI ignore files in canonical order
        assertEquals(
            listOf(".gitignore", ".aiderignore", ".cursorignore"),
            r.sources
        )
        assertTrue(r.isPathIgnored("emails/x.txt", false))
        assertTrue(r.isPathIgnored("api-keys.xml", false))
        assertTrue(r.isPathIgnored("build/foo.txt", false))
    }

    // ---------------------------------------------------------------
    // Basic gitignore semantics
    // ---------------------------------------------------------------

    @Test
    fun `comments and blank lines are skipped`() {
        write(".gitignore", "# a comment\n\n*.log\n")
        val r = rules()
        assertTrue(r.isPathIgnored("a.log", false))
        assertFalse(r.isIgnored("# a comment", false))
    }

    @Test
    fun `basic glob patterns match at any depth by default`() {
        write(".gitignore", "*.log\ntmp/\n")
        val r = rules()
        assertTrue(r.isPathIgnored("a.log", false))
        assertTrue(r.isPathIgnored("deep/nested/a.log", false))
        assertTrue(r.isPathIgnored("some/tmp/file.txt", false))
        assertFalse(r.isIgnored("some/tmp", false)) // file, not dir → dir-only rule doesn't match
    }

    @Test
    fun `trailing slash means directory only`() {
        write(".gitignore", "logs/\n")
        val r = rules()
        assertTrue(r.isPathIgnored("logs", true))
        assertTrue(r.isPathIgnored("a/logs", true))
        assertFalse(r.isPathIgnored("logs", false))
        assertFalse(r.isPathIgnored("a/logs", false))
        assertFalse(r.isPathIgnored("logs.txt", false))
    }

    @Test
    fun `leading slash anchors pattern at root`() {
        write(".gitignore", "/readme.md\n/secret/\n")
        val r = rules()
        assertTrue(r.isPathIgnored("readme.md", false))
        assertFalse(r.isPathIgnored("sub/readme.md", false))
        assertTrue(r.isPathIgnored("secret/x.txt", false))
        assertFalse(r.isPathIgnored("sub/secret/x.txt", false))
    }

    @Test
    fun `embedded slash anchors pattern at root`() {
        write(".gitignore", "src/generated/**\n")
        val r = rules()
        assertTrue(r.isPathIgnored("src/generated/a.kt", false))
        assertTrue(r.isPathIgnored("src/generated/sub/b.kt", false))
        assertFalse(r.isPathIgnored("lib/generated/b.kt", false))
    }

    @Test
    fun `double-star matches across directories`() {
        write(".gitignore", "a/**/b\n")
        val r = rules()
        assertTrue(r.isPathIgnored("a/b", false))
        assertTrue(r.isPathIgnored("a/x/b", false))
        assertTrue(r.isPathIgnored("a/x/y/b", false))
        assertFalse(r.isPathIgnored("a/x/y/z", false))

        write(".gitignore", "**/middle\n")
        val r2 = rules()
        assertTrue(r2.isPathIgnored("x/middle", false))
        assertTrue(r2.isPathIgnored("middle", false))

        write(".gitignore", "top/**\n")
        val r3 = rules()
        assertTrue(r3.isPathIgnored("top/anything.txt", false))
        assertTrue(r3.isPathIgnored("top/deep/file.txt", false))
        assertFalse(r3.isPathIgnored("topother/file.txt", false))
    }

    @Test
    fun `question mark matches single non-separator character`() {
        write(".gitignore", "file?.txt\n")
        val r = rules()
        assertTrue(r.isPathIgnored("file1.txt", false))
        assertFalse(r.isPathIgnored("file10.txt", false))
        assertFalse(r.isPathIgnored("file/.txt", false))
    }

    @Test
    fun `character class matches ranges`() {
        write(".gitignore", "[abc]x.txt\n[0-9]y.txt\n")
        val r = rules()
        assertTrue(r.isPathIgnored("ax.txt", false))
        assertTrue(r.isPathIgnored("9y.txt", false))
        assertFalse(r.isPathIgnored("dx.txt", false))
    }

    // ---------------------------------------------------------------
    // Negation and precedence
    // ---------------------------------------------------------------

    @Test
    fun `negation re-includes a broader rule`() {
        write(".gitignore", "*.env\n!keep.env\n")
        val r = rules()
        assertTrue(r.isPathIgnored("prod.env", false))
        assertFalse(r.isPathIgnored("keep.env", false))
    }

    @Test
    fun `later AI ignore files override earlier ones and gitignore`() {
        write(".gitignore", "*.log\n")
        write(".cursorignore", "!important.log\n")
        val r = rules()
        assertFalse(r.isPathIgnored("important.log", false))
        assertTrue(r.isPathIgnored("debug.log", false))
    }

    @Test
    fun `negated dir-only rule does not silently leak non-dir matches`() {
        write(".gitignore", "*.log\n")
        write(".cursorignore", "!keep.log/\n")
        val r = rules()
        // !keep.log/ is directory-only; it must not un-ignore a FILE named
        // keep.log (or any other *.log file)
        assertTrue(r.isPathIgnored("keep.log", false))
        assertTrue(r.isPathIgnored("other.log", false))
    }

    // ---------------------------------------------------------------
    // Ancestor (path) behaviour
    // ---------------------------------------------------------------

    @Test
    fun `ignored ancestor directory hides descendants`() {
        write(".gitignore", "build/\n")
        val r = rules()
        assertTrue(r.isPathIgnored("build/outputs/inner.log", false))
        assertTrue(r.isPathIgnored("build", true))
    }

    @Test
    fun `project root itself is never ignored`() {
        write(".gitignore", "/**\n")
        val r = rules()
        assertTrue(r.isPathIgnored("anything", false))
        // the "." / "" case is also guarded
        assertFalse(r.isPathIgnored("", false))
    }

    @Test
    fun `malformed character class does not crash loading`() {
        write(".gitignore", "[unclosed\n*.log\n")
        val r = rules()
        assertTrue(r.isPathIgnored("a.log", false))
        // "[unclosed" should not quietly swallow other filenames either way;
        // we only require it loads and other rules still work.
    }

    @Test
    fun `cache invalidation reloads changed ignore files`() {
        val r1 = rules()
        assertFalse(r1.isPathIgnored("foo.txt", false))
        write(".gitignore", "foo.txt\n")
        IgnoreRules.invalidate(tmp.root)
        val r2 = rules()
        assertTrue(r2.isPathIgnored("foo.txt", false))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun rules(): IgnoreRules = IgnoreRules.load(tmp.root)

    private fun write(name: String, content: String) {
        File(tmp.root, name).writeText(content.trimIndent() + "\n")
        IgnoreRules.invalidate(tmp.root)
    }
}
