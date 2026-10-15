package dev.tellforgeai.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoSpecTest {

    @Test
    fun fullHttpsUrl() {
        val s = RepoSpec.parse("https://github.com/djroberts/my-configs.git")!!
        assertEquals("https://github.com/djroberts/my-configs.git", s.cloneUrl)
        assertEquals("", s.subpath)
    }

    @Test
    fun fullHttpsUrlWithoutGitSuffix() {
        val s = RepoSpec.parse("https://github.com/djroberts/my-configs")!!
        assertEquals("https://github.com/djroberts/my-configs.git", s.cloneUrl)
        assertEquals("", s.subpath)
    }

    @Test
    fun shorthandWithSubdirectory() {
        val s = RepoSpec.parse("djroberts/my-configs/my-project")!!
        assertEquals("https://github.com/djroberts/my-configs.git", s.cloneUrl)
        assertEquals("my-project", s.subpath)
    }

    @Test
    fun fragmentSubdirectory() {
        val s = RepoSpec.parse("https://github.com/djroberts/my-configs.git#my-project")!!
        assertEquals("https://github.com/djroberts/my-configs.git", s.cloneUrl)
        assertEquals("my-project", s.subpath)
    }

    @Test
    fun sshUrlPassthrough() {
        val s = RepoSpec.parse("git@github.com:djroberts/my-configs.git")!!
        assertEquals("git@github.com:djroberts/my-configs.git", s.cloneUrl)
        assertEquals("", s.subpath)
    }

    @Test
    fun trailingSlashesAndSpacesTrimmed() {
        val s = RepoSpec.parse("  djroberts/my-configs/  ")!!
        assertEquals("https://github.com/djroberts/my-configs.git", s.cloneUrl)
        assertEquals("", s.subpath)
    }

    @Test
    fun hostPrefixedInputIsRejected() {
        assertNull(RepoSpec.parse("example.com/djroberts/repo"))
    }

    @Test
    fun emptyInputIsRejected() {
        assertNull(RepoSpec.parse("  "))
    }

    @Test
    fun singleSegmentIsRejected() {
        assertNull(RepoSpec.parse("just-a-name"))
    }
}
