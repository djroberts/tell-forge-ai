package dev.tellforgeai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadBinaryToolTest {

    @Test
    fun `accepts arbitrary HTTPS hosts`() {
        assertEquals("files.example.org", DownloadBinaryTool.validateUrl("https://files.example.org/assets/archive.bin").host)
    }

    @Test
    fun `rejects non HTTPS schemes`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadBinaryTool.validateUrl("http://files.example.org/archive.bin")
        }
    }

    @Test
    fun `rejects URLs containing credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadBinaryTool.validateUrl("https://user:password@files.example.org/archive.bin")
        }
    }

    @Test
    fun `rejects malformed URLs`() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadBinaryTool.validateUrl("not a url")
        }
    }
}
