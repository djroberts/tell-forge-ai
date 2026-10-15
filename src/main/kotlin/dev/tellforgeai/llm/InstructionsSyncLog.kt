package dev.tellforgeai.llm

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Dedicated best-effort file log for the instructions repo sync, independent
 * of idea.log filtering/rate limits. Written to <log dir>/tellforgeai-sync.log.
 * All methods swallow their own failures so logging can never break the sync.
 */
object InstructionsSyncLog {

    private val lock = Any()

    fun path(): File = File(PathManager.getLogPath(), "tellforgeai-sync.log")

    fun info(msg: String) = write("INFO ", msg)

    fun warn(msg: String) = write("WARN ", msg)

    fun error(msg: String, e: Throwable? = null) =
        write("ERROR", msg + (e?.let { "\n  " + it.stackTraceToString().replace("\n", "\n  ") } ?: ""))

    fun mark(phase: String) = info("--- $phase ---")

    private fun write(level: String, msg: String) {
        try {
            synchronized(lock) {
                val f = path()
                f.parentFile?.mkdirs()
                f.appendText("${Instant.now()} $level sync: $msg\n", StandardCharsets.UTF_8)
                // Bound growth: rotate at ~1 MB.
                if (f.length() > 1_000_000) {
                    val backup = File(f.parentFile, f.name + ".1")
                    FileUtil.delete(backup)
                    f.renameTo(backup)
                }
            }
        } catch (_: Throwable) {
            // never let logging break the sync
        }
    }
}
