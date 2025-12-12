package dev.pointtosky.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Persists crash events into a JSONL file for offline diagnostics.
 */
class CrashLogStore(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val redactor: Redactor = Redactor.Privacy,
) {
    private val file: File = File(directory, FILE_NAME)
    private val lock = Any()
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.of("UTC"))
    private val hasLoadedState = AtomicBoolean(false)
    private val lastCrashState = MutableStateFlow<CrashLogEntry?>(null)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    fun record(thread: Thread, throwable: Throwable) {
        val entry = CrashLogEntry.from(thread, throwable, clock)
        synchronized(lock) {
            try {
                ensureLoadedLocked()
                val line = entry.toJsonLine()
                FileOutputStream(file, true).use { stream ->
                    stream.write(line.toByteArray(StandardCharsets.UTF_8))
                    stream.write(NEWLINE_BYTE)
                }
                trimLocked()
                lastCrashState.value = entry
            } catch (_: Exception) {
                // Ignored – we never want crash logging to throw.
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } finally {
                lastCrashState.value = null
                hasLoadedState.set(false)
            }
        }
    }

    fun lastCrash(): StateFlow<CrashLogEntry?> {
        ensureLoaded()
        return lastCrashState.asStateFlow()
    }

    fun currentLastCrash(): CrashLogEntry? {
        ensureLoaded()
        return lastCrashState.value
    }

    fun hasLogs(): Boolean {
        synchronized(lock) {
            return file.exists() && file.length() > 0L
        }
    }

    fun createZip(targetDirectory: File): File? {
        synchronized(lock) {
            ensureLoadedLocked()
            if (!file.exists() || file.length() == 0L) {
                return null
            }
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            val timestamp = formatter.format(Instant.now(clock))
            val zipName = "pointtosky-crash-$timestamp.zip"
            val zipFile = File(targetDirectory, zipName)
            return try {
                // Read, redact, and write crash log entries
                FileOutputStream(zipFile).use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry(file.name))
                        file.inputStream().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            lines.forEach { line ->
                                if (line.isNotBlank()) {
                                    // Parse entry, redact it, and write to ZIP
                                    val entry = CrashLogEntry.fromJson(line)
                                    if (entry != null) {
                                        val redacted = if (redactor is Redactor.Privacy) {
                                            Redactor.Privacy.redact(entry)
                                        } else {
                                            entry // Passthrough if not using Privacy redactor
                                        }
                                        val redactedLine = redacted.toJsonLine()
                                        zip.write(redactedLine.toByteArray(StandardCharsets.UTF_8))
                                        zip.write(NEWLINE_BYTE)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                zipFile
            } catch (_: Exception) {
                zipFile.delete()
                null
            }
        }
    }

    private fun ensureLoaded() {
        if (hasLoadedState.get()) return
        synchronized(lock) {
            ensureLoadedLocked()
        }
    }

    private fun ensureLoadedLocked() {
        if (hasLoadedState.get()) return
        if (!file.exists()) {
            hasLoadedState.set(true)
            lastCrashState.value = null
            return
        }
        var last: CrashLogEntry? = null
        try {
            file.inputStream().bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        CrashLogEntry.fromJson(line)?.let { parsed ->
                            last = parsed
                        }
                    }
                }
            }
        } catch (_: Exception) {
            last = null
        }
        lastCrashState.value = last
        hasLoadedState.set(true)
    }

    private fun trimLocked() {
        if (!file.exists()) return
        if (maxEntries <= 0) return
        try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            if (lines.size <= maxEntries) return
            val tail = lines.takeLast(maxEntries)
            file.writeText(tail.joinToString(separator = "\n", postfix = "\n"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Ignore trimming failures.
        }
    }

    companion object {
        private const val FILE_NAME = "crash-log.jsonl"
        private const val DEFAULT_MAX_ENTRIES = 64
        private const val NEWLINE_BYTE = '\n'.code
    }
}
