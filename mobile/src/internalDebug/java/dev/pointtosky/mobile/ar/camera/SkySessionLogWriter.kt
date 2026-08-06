package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkyFrameLine
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkySessionHeaderLine
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer

/** The log file inside a session directory. One JSON object per line; the first is the header. */
internal const val SKY_SESSION_LOG_FILE_NAME = "session.jsonl"

/** The subdirectory holding one raw luma plane per analyzed frame. */
internal const val SKY_SESSION_FRAMES_DIRECTORY_NAME = "frames"

/**
 * SKY-1 (`internalDebug`-only): writes one sky session to disk — a `session.jsonl` line stream plus
 * one raw luma file per frame under `frames/`.
 *
 * ## Layout
 * ```text
 * <parent>/sky_<sessionId>/
 *   session.jsonl        header line, then one line per frame
 *   frames/frame_000000.y  packed 8-bit luma, rowStridePx == widthPx
 *   frames/frame_000001.y
 * ```
 * A frame line references its pixels by a path **relative to the session directory**
 * ([SkyLumaReference.path]), so the whole directory can be pulled off the device and read anywhere.
 *
 * ## Why raw `.y` and not PNG
 * See [SkyLumaFormat]. Short version: `ImageProxy.planes[0]` already is the 8-bit intensity plane a
 * star detector wants, so writing it verbatim is lossless, encoder-free, and one `numpy.fromfile`
 * away offline. Chroma is not read at all — no star detector uses it, and it would cost roughly 3x
 * the bytes per frame in a mode (long-exposure night capture) where storage is the practical limit
 * on session length.
 *
 * ## Failure handling
 * Every write returns a result rather than throwing: a capture session that fills the disk halfway
 * through should stop cleanly and keep the frames it already wrote, not crash the experiment
 * activity. [failureCount] and [lastFailureReason] surface that to the UI. The reason is a category
 * plus the exception's class name, never its message — an `IOException` message can carry a full
 * filesystem path.
 *
 * ## Threading
 * Not internally synchronized: one instance is owned by one capture session and written only from
 * that session's single analysis executor thread, matching [SkySessionCameraPreview]'s own
 * single-thread analyzer. [close] may be called from another thread once the analyzer is known to
 * have stopped.
 */
internal class SkySessionLogWriter(
    val sessionDirectory: File,
) {
    private val framesDirectory = File(sessionDirectory, SKY_SESSION_FRAMES_DIRECTORY_NAME)
    private val logFile = File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME)

    private var writer: Writer? = null
    private var closed = false

    /** How many frames have been appended successfully. */
    var writtenFrameCount: Long = 0L
        private set

    /** How many writes failed. A non-zero value means the log is short of what the camera delivered. */
    var failureCount: Long = 0L
        private set

    /** The most recent failure's short category, or `null` when nothing has failed. */
    var lastFailureReason: String? = null
        private set

    /** How many bytes of luma have been written, so a UI can show a session's storage cost. */
    var writtenLumaBytes: Long = 0L
        private set

    /**
     * Creates the session directory and writes the header line. Must be called exactly once, before
     * any [appendFrame]. Returns `false` when the directory or header could not be written, in which
     * case the session should not start.
     */
    fun start(header: SkySessionLogHeader): Boolean {
        check(!closed) { "SkySessionLogWriter is closed" }
        check(writer == null) { "start() must be called exactly once" }
        return runWrite("session_start") {
            if (!sessionDirectory.isDirectory && !sessionDirectory.mkdirs()) {
                throw IOException("could not create session directory")
            }
            if (!framesDirectory.isDirectory && !framesDirectory.mkdirs()) {
                throw IOException("could not create frames directory")
            }
            val stream =
                OutputStreamWriter(BufferedOutputStream(FileOutputStream(logFile, /* append = */ true)), Charsets.UTF_8)
            stream.appendLine(encodeSkySessionHeaderLine(header))
            stream.flush()
            writer = stream
        }
    }

    /**
     * Writes [data]'s first `rowStridePx * heightPx` bytes as this frame's luma file and returns the
     * reference to record in the frame's log line, or `null` when the write failed.
     *
     * The returned [SkyLumaReference.byteLength] is what was actually written, not what was intended:
     * an offline reader must be able to trust that the file on disk is that long.
     */
    fun writeLumaFrame(
        sequence: Long,
        data: ByteArray,
        widthPx: Int,
        heightPx: Int,
        rowStridePx: Int,
    ): SkyLumaReference? {
        check(!closed) { "SkySessionLogWriter is closed" }
        val fileName = lumaFileName(sequence)
        val relativePath = "$SKY_SESSION_FRAMES_DIRECTORY_NAME/$fileName"
        val byteLength = rowStridePx.toLong() * heightPx.toLong()
        if (byteLength > data.size.toLong()) {
            recordFailure("luma_buffer_short")
            return null
        }
        var reference: SkyLumaReference? = null
        runWrite("luma_write") {
            FileOutputStream(File(framesDirectory, fileName)).use { stream ->
                stream.write(data, 0, byteLength.toInt())
            }
            writtenLumaBytes += byteLength
            reference =
                SkyLumaReference(
                    path = relativePath,
                    format = SkyLumaFormat.RAW_Y8,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    rowStridePx = rowStridePx,
                    byteLength = byteLength,
                )
        }
        return reference
    }

    /**
     * Appends one frame line. Flushed immediately: a capture that ends by the device being switched
     * off must leave the frames it already recorded readable, and a buffered tail would lose the last
     * few seconds — which, at long sky exposures, can be most of the session.
     */
    fun appendFrame(record: SkyFrameRecord): Boolean {
        check(!closed) { "SkySessionLogWriter is closed" }
        val stream =
            writer ?: run {
                recordFailure("not_started")
                return false
            }
        return runWrite("frame_append") {
            stream.appendLine(encodeSkyFrameLine(record))
            stream.flush()
            writtenFrameCount += 1
        }
    }

    /** Flushes and closes the log stream. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        runWrite("close") {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    private inline fun runWrite(
        category: String,
        block: () -> Unit,
    ): Boolean =
        try {
            block()
            true
        } catch (e: IOException) {
            recordFailure("$category:${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            recordFailure("$category:${e.javaClass.simpleName}")
            false
        } catch (e: IllegalArgumentException) {
            // A model type's own init rejecting the values this frame produced. Expected to be
            // unreachable (every caller passes already-validated geometry), but a capture session
            // must degrade to "one frame lost, counted" rather than taking the activity down.
            recordFailure("$category:${e.javaClass.simpleName}")
            false
        }

    private fun recordFailure(reason: String) {
        failureCount += 1
        lastFailureReason = reason
    }

    internal companion object {
        /** Zero-padded so a directory listing sorts in capture order. */
        fun lumaFileName(sequence: Long): String = "frame_" + sequence.toString().padStart(6, '0') + ".y"
    }
}
