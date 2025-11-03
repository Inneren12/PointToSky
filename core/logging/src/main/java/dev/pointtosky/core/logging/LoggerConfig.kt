package dev.pointtosky.core.logging

import java.io.File

data class LoggerConfig(
    val logsDirectory: File,
    val diagnosticsEnabled: Boolean,
    val ringBufferCapacity: Int = DEFAULT_RING_BUFFER_CAPACITY,
    val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    val maxFiles: Int = DEFAULT_MAX_FILES,
    val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    val forceFsyncOnRotate: Boolean = true,
    val redactor: Redactor = Redactor.Passthrough,
    val frameTraceMode: FrameTraceMode = FrameTraceMode.OFF,
) {
    init {
        require(maxFileSizeBytes > 0) { "maxFileSizeBytes must be > 0" }
        require(maxFiles > 0) { "maxFiles must be > 0" }
        require(flushIntervalMs >= 0) { "flushIntervalMs must be >= 0" }
        require(ringBufferCapacity > 0) { "ringBufferCapacity must be > 0" }
    }

    companion object {
        private const val DEFAULT_RING_BUFFER_CAPACITY = 10_000
        private const val DEFAULT_MAX_FILES = 7
        private const val DEFAULT_FLUSH_INTERVAL_MS = 500L
        private const val DEFAULT_MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L
    }
}

enum class FrameTraceMode {
    OFF,
    SUMMARY_1HZ,
    FULL_15HZ,
}
