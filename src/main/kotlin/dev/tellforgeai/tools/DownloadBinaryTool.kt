package dev.tellforgeai.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Downloads an HTTPS response as raw bytes into a project file. Forge mode only. */
object DownloadBinaryTool {

    const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        // Never follow a redirect from HTTPS to plaintext HTTP.
        .followSslRedirects(false)
        .build()

    fun download(
        project: Project,
        args: JsonObject,
        onDownloadRequested: (String) -> Boolean,
    ): String {
        val urlText = VfsTools.str(args, "url")?.trim()
            ?: throw IllegalArgumentException("url required")
        val relPath = VfsTools.str(args, "path")?.trim()
            ?: throw IllegalArgumentException("path required")
        val uri = validateUrl(urlText)
        if (relPath.isBlank() || relPath == "." || relPath.endsWith('/') || relPath.endsWith('\\')) {
            return "Error: path must name a file"
        }
        val target = VfsTools.resolve(project, relPath)
        if (!isInsideProject(project, target)) return "Error: destination must stay inside the project root"
        if (target.isDirectory) return "Error: '$relPath' is a directory"
        val overwrite = if (target.exists()) " This will overwrite the existing file." else ""
        val approvalText = "Download $uri to '$relPath'.$overwrite"
        if (!onDownloadRequested(approvalText)) return "Download declined by user."

        val bytes = fetch(uri.toASCIIString())
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val refreshedTarget = VfsTools.resolve(project, relPath)
                    if (!isInsideProject(project, refreshedTarget)) {
                        result = "Error: destination must stay inside the project root"
                        return@runWriteCommandAction
                    }
                    if (refreshedTarget.isDirectory) {
                        result = "Error: '$relPath' became a directory"
                        return@runWriteCommandAction
                    }
                    val parent = VfsUtil.createDirectoryIfMissing(refreshedTarget.canonicalFile.parent)
                    if (parent == null) {
                        result = "Error: could not create parent directory for '$relPath'"
                        return@runWriteCommandAction
                    }
                    val existing = parent.findChild(refreshedTarget.name)
                    val file = existing ?: parent.createChildData(this, refreshedTarget.name)
                    file.setBinaryContent(bytes)
                    result = "Downloaded ${bytes.size} bytes to $relPath${if (existing == null) "" else " (overwritten)"}"
                } catch (e: Exception) {
                    result = "Error saving '$relPath': ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        return result
    }

    internal fun validateUrl(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid URL: ${e.message ?: "malformed URL"}")
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port > 65535) {
            throw IllegalArgumentException("URL must be an HTTPS URL with a hostname and no embedded credentials")
        }
        return uri
    }

    private fun isInsideProject(project: Project, target: File): Boolean {
        val root = VfsTools.root(project).canonicalFile.toPath()
        return target.canonicalFile.toPath().startsWith(root)
    }

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val redirectNote = if (response.isRedirect) " Redirects are not followed; provide the final HTTPS URL." else ""
                throw IllegalStateException("download returned HTTP ${response.code}.$redirectNote")
            }
            val body = response.body ?: throw IllegalStateException("download response had no body")
            if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                throw IllegalStateException("download exceeds the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MiB size limit")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_DOWNLOAD_BYTES) {
                        throw IllegalStateException("download exceeds the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MiB size limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }
}
