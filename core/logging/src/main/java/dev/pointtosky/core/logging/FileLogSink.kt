package dev.pointtosky.core.logging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant

class FileLogSink(
    private val config: LoggerConfig,
    private val fileManager: LogFileManager,
) : LogSink, FlushableLogSink, SyncableLogSink {

    private val mutex = Mutex()
    private var holder: WriterHolder? = null

    override suspend fun write(event: LogEvent) {
        val line = event.toJsonLine()
        val bytes = line.toByteArray(StandardCharsets.UTF_8).size + NEWLINE_BYTES
        mutex.withLock {
            val now = Instant.now()
            ensureWriter(now)
            if (holder == null) return
            if (fileManager.shouldRotate(bytes.toLong(), now)) {
                rotateLocked(now)
                ensureWriter(now)
            }
            holder?.writer?.apply {
                append(line)
                append('\n')
            }
            fileManager.markWrite(bytes.toLong())
        }
    }

    override suspend fun flush() {
        mutex.withLock {
            holder?.writer?.flush()
        }
    }

    override suspend fun sync() {
        mutex.withLock {
            holder?.let {
                it.writer.flush()
                it.stream.fd.sync()
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            holder?.close()
            holder = null
        }
    }

    private suspend fun rotateLocked(now: Instant) {
        val current = holder ?: return
        current.writer.flush()
        if (config.forceFsyncOnRotate) {
            current.stream.fd.sync()
        }
        current.close()
        holder = null
        fileManager.rotate(now)
    }

    private suspend fun ensureWriter(now: Instant) {
        if (holder != null) return
        val file = fileManager.currentFile(now)
        holder = WriterHolder(file)
    }

    private inner class WriterHolder(file: File) {
        val stream = FileOutputStream(file, true)
        val writer: BufferedWriter = BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8))

        fun close() {
            writer.close()
            stream.close()
        }
    }

    private companion object {
        private const val NEWLINE_BYTES = 1
    }
}
