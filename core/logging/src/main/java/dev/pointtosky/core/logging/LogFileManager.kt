package dev.pointtosky.core.logging

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.max

class LogFileManager(
    private val config: LoggerConfig,
    private val clock: Clock = Clock.systemUTC()
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HH-mm-ss", Locale.US)
        .withZone(ZoneId.of("UTC"))

    @Volatile
    private var activeFile: ActiveFile? = null

    @Synchronized
    fun currentFile(now: Instant = Instant.now(clock)): File {
        val existing = activeFile
        return if (existing == null) {
            createNewFile(now, sequence = 0)
        } else {
            existing.file
        }
    }

    @Synchronized
    fun shouldRotate(additionalBytes: Long, now: Instant = Instant.now(clock)): Boolean {
        val current = activeFile ?: return false
        val exceedsSize = current.size + additionalBytes > config.maxFileSizeBytes
        val differentDay = !sameDay(current.createdAt, now)
        return exceedsSize || differentDay
    }

    @Synchronized
    fun markWrite(bytes: Long) {
        val current = activeFile ?: return
        current.size += bytes
    }

    @Synchronized
    fun rotate(now: Instant = Instant.now(clock)): File {
        val previous = activeFile
        val nextSequence = if (previous == null || !sameDay(previous.createdAt, now)) 0 else previous.sequence + 1
        previous?.let { archive(it) }
        return createNewFile(now, nextSequence)
    }

    private fun archive(file: ActiveFile) {
        if (file.file.exists() && file.file.length() > 0L) {
            gzipFile(file.file)
        }
        cleanup()
    }

    private fun createNewFile(now: Instant, sequence: Int): File {
        if (!config.logsDirectory.exists()) {
            config.logsDirectory.mkdirs()
        }
        val fileName = buildString {
            append(FILE_PREFIX)
            append('-')
            append(formatter.format(now))
            append('-')
            append(sequence)
            append(FILE_SUFFIX)
        }
        val file = File(config.logsDirectory, fileName)
        if (!file.exists()) {
            Files.createFile(file.toPath())
        }
        archiveStaleFiles(exclude = file)
        activeFile = ActiveFile(file = file, createdAt = now, sequence = sequence, size = max(0L, file.length()))
        cleanup()
        return file
    }

    private fun archiveStaleFiles(exclude: File?) {
        val staleFiles = config.logsDirectory.listFiles { candidate ->
            candidate.isFile && candidate.name.endsWith(FILE_SUFFIX) && candidate != exclude
        } ?: return
        staleFiles.forEach { candidate ->
            if (candidate.length() == 0L) {
                candidate.delete()
            } else {
                gzipFile(candidate)
            }
        }
    }

    private fun gzipFile(source: File) {
        val gzFile = File(source.parentFile, source.name + ".gz")
        FileInputStream(source).use { input ->
            FileOutputStream(gzFile).use { output ->
                GZIPOutputStream(output).use { gzip ->
                    input.copyTo(gzip)
                }
            }
        }
        source.delete()
    }

    private fun cleanup() {
        val files = config.logsDirectory.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith(FILE_PREFIX)
        }?.sortedBy { it.lastModified() } ?: return
        val overflow = files.size - config.maxFiles
        if (overflow <= 0) return
        files.take(overflow).forEach { it.delete() }
    }

    private fun sameDay(left: Instant, right: Instant): Boolean {
        val zone = formatter.zone
        val leftDate = left.atZone(zone).toLocalDate()
        val rightDate = right.atZone(zone).toLocalDate()
        return leftDate == rightDate
    }

    private data class ActiveFile(
        val file: File,
        val createdAt: Instant,
        val sequence: Int,
        var size: Long
    )

    companion object {
        private const val FILE_PREFIX = "pointtosky"
        private const val FILE_SUFFIX = ".jsonl"
    }
}
