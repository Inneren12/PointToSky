package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkyFrameLine
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkySessionHeaderLine
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** The log file inside a session directory. One JSON object per line; the first is the header. */
internal const val SKY_SESSION_LOG_FILE_NAME = "session.jsonl"

/** The subdirectory holding one raw luma plane per analyzed frame. */
internal const val SKY_SESSION_FRAMES_DIRECTORY_NAME = "frames"

/**
 * Why a session write failed. A short, stable category — never an `IOException` message, which can
 * carry the app's full private storage path into a HUD or a pasted bug report.
 */
internal enum class SkySessionWriteFailure {
    /** The session directory already existed. A session never adopts a directory it did not create. */
    SESSION_DIRECTORY_EXISTS,
    SESSION_DIRECTORY_CREATE_FAILED,
    FRAMES_DIRECTORY_EXISTS,
    FRAMES_DIRECTORY_CREATE_FAILED,

    /** `session.jsonl` already existed. Appending to it would splice two sessions into one file. */
    LOG_FILE_EXISTS,
    LOG_OPEN_FAILED,
    HEADER_WRITE_FAILED,

    /** A luma file for this sequence number already existed. Never overwritten. */
    LUMA_FILE_EXISTS,
    LUMA_WRITE_FAILED,

    /** The supplied buffer is smaller than the geometry it claims. */
    LUMA_BUFFER_SHORT,
    FRAME_APPEND_FAILED,
    CLOSE_FAILED,

    /** A write was attempted before [SkySessionLogSink.start] succeeded. */
    NOT_STARTED,
}

/**
 * The disk seam a [SkySessionRecorder] writes through.
 *
 * Extracted as an interface for one reason: the recorder's whole job is ordering — pixels before the
 * line that references them, no write after close, one in-flight commit finishing atomically against
 * a concurrent stop. Proving that against a real filesystem means racing real I/O; proving it against
 * a sink that can be paused mid-`appendFrame` is deterministic. See `SkySessionRecorderConcurrencyTest`.
 */
internal interface SkySessionLogSink {
    val sessionPath: String
    val writtenFrameCount: Long
    val writtenLumaBytes: Long
    val lastFailure: SkySessionWriteFailure?

    /** Creates the session and writes the header. `false` means the session must not start. */
    fun start(header: SkySessionLogHeader): Boolean

    /** Writes this frame's pixels, returning the reference to record, or `null` on failure. */
    fun writeLumaFrame(
        sequence: Long,
        data: ByteArray,
        widthPx: Int,
        heightPx: Int,
        rowStridePx: Int,
    ): SkyLumaReference?

    /** Appends and flushes one frame line. */
    fun appendFrame(record: SkyFrameRecord): Boolean

    fun close()
}

/**
 * SKY-1 (`internalDebug`-only): writes one sky session to disk — a `session.jsonl` line stream plus
 * one raw luma file per frame under `frames/`.
 *
 * ## Layout
 * ```text
 * <parent>/sky_<sessionId>/
 *   session.jsonl        header line, then one line per frame
 *   frames/frame_000000.y  packed 8-bit luma
 *   frames/frame_000001.y
 * ```
 * A frame line references its pixels by a path **relative to the session directory**
 * ([SkyLumaReference.path]), so the whole directory can be pulled off the device and read anywhere.
 *
 * ## Exclusive creation
 * A session **creates** its directory or refuses to run. It never adopts an existing one, never
 * appends to an existing `session.jsonl`, and never overwrites an existing `frame_NNNNNN.y`. Every
 * one of those is done through `Files.createDirectory` / `StandardOpenOption.CREATE_NEW`, which fail
 * atomically at the filesystem level rather than through a check-then-open window another writer
 * could slip through.
 *
 * This is not defensiveness for its own sake. `session.jsonl` opened with `append = true` on a
 * directory that already had one produces a file with two header lines and two interleaved frame
 * sequences, whose `frame_000000.y` belongs to whichever session wrote it last — a dataset that
 * parses cleanly and is entirely wrong.
 *
 * ## Failure handling
 * Every write returns a result rather than throwing: a capture session that fills the disk halfway
 * through should stop cleanly and keep the frames it already wrote, not crash the experiment
 * activity. [lastFailure] is a typed category; nothing here ever surfaces an exception message.
 *
 * ## Threading
 * Not internally synchronized. Ordering and exclusion are [SkySessionRecorder]'s job — it holds a
 * lock across every call into this sink, including [close]. Nothing else may write through an
 * instance the recorder owns.
 */
internal class SkySessionLogWriter(
    val sessionDirectory: File,
) : SkySessionLogSink {
    private val framesDirectory = File(sessionDirectory, SKY_SESSION_FRAMES_DIRECTORY_NAME)
    private val logFile = File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME)

    private var writer: Writer? = null
    private var closed = false

    override val sessionPath: String get() = sessionDirectory.absolutePath

    override var writtenFrameCount: Long = 0L
        private set

    override var writtenLumaBytes: Long = 0L
        private set

    override var lastFailure: SkySessionWriteFailure? = null
        private set

    /** How many writes failed. A non-zero value means the log is short of what the camera delivered. */
    var failureCount: Long = 0L
        private set

    override fun start(header: SkySessionLogHeader): Boolean {
        check(!closed) { "SkySessionLogWriter is closed" }
        check(writer == null) { "start() must be called exactly once" }

        if (sessionDirectory.exists()) return fail(SkySessionWriteFailure.SESSION_DIRECTORY_EXISTS)
        try {
            sessionDirectory.parentFile?.let { Files.createDirectories(it.toPath()) }
            Files.createDirectory(sessionDirectory.toPath())
        } catch (_: FileAlreadyExistsException) {
            return fail(SkySessionWriteFailure.SESSION_DIRECTORY_EXISTS)
        } catch (_: IOException) {
            return fail(SkySessionWriteFailure.SESSION_DIRECTORY_CREATE_FAILED)
        } catch (_: SecurityException) {
            return fail(SkySessionWriteFailure.SESSION_DIRECTORY_CREATE_FAILED)
        }

        try {
            Files.createDirectory(framesDirectory.toPath())
        } catch (_: FileAlreadyExistsException) {
            return fail(SkySessionWriteFailure.FRAMES_DIRECTORY_EXISTS)
        } catch (_: IOException) {
            return fail(SkySessionWriteFailure.FRAMES_DIRECTORY_CREATE_FAILED)
        } catch (_: SecurityException) {
            return fail(SkySessionWriteFailure.FRAMES_DIRECTORY_CREATE_FAILED)
        }

        val stream =
            try {
                // CREATE_NEW, never APPEND: two sessions must never share one log file.
                Files.newOutputStream(logFile.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (_: FileAlreadyExistsException) {
                return fail(SkySessionWriteFailure.LOG_FILE_EXISTS)
            } catch (_: IOException) {
                return fail(SkySessionWriteFailure.LOG_OPEN_FAILED)
            } catch (_: SecurityException) {
                return fail(SkySessionWriteFailure.LOG_OPEN_FAILED)
            }

        val textWriter = OutputStreamWriter(BufferedOutputStream(stream), StandardCharsets.UTF_8)
        return try {
            textWriter.appendLine(encodeSkySessionHeaderLine(header))
            textWriter.flush()
            writer = textWriter
            true
        } catch (_: IOException) {
            runCatching { textWriter.close() }
            fail(SkySessionWriteFailure.HEADER_WRITE_FAILED)
        }
    }

    override fun writeLumaFrame(
        sequence: Long,
        data: ByteArray,
        widthPx: Int,
        heightPx: Int,
        rowStridePx: Int,
    ): SkyLumaReference? {
        check(!closed) { "SkySessionLogWriter is closed" }
        if (writer == null) {
            fail(SkySessionWriteFailure.NOT_STARTED)
            return null
        }
        if (widthPx <= 0 || heightPx <= 0 || rowStridePx < widthPx) {
            fail(SkySessionWriteFailure.LUMA_BUFFER_SHORT)
            return null
        }
        val byteLength = rowStridePx.toLong() * heightPx.toLong()
        if (byteLength > data.size.toLong()) {
            fail(SkySessionWriteFailure.LUMA_BUFFER_SHORT)
            return null
        }

        val fileName = lumaFileName(sequence)
        val target = File(framesDirectory, fileName)
        try {
            Files
                .newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { stream -> stream.write(data, 0, byteLength.toInt()) }
        } catch (_: FileAlreadyExistsException) {
            fail(SkySessionWriteFailure.LUMA_FILE_EXISTS)
            return null
        } catch (_: IOException) {
            fail(SkySessionWriteFailure.LUMA_WRITE_FAILED)
            return null
        } catch (_: SecurityException) {
            fail(SkySessionWriteFailure.LUMA_WRITE_FAILED)
            return null
        }

        writtenLumaBytes += byteLength
        return SkyLumaReference(
            path = "$SKY_SESSION_FRAMES_DIRECTORY_NAME/$fileName",
            format = SkyLumaFormat.RAW_Y8,
            widthPx = widthPx,
            heightPx = heightPx,
            rowStridePx = rowStridePx,
            byteLength = byteLength,
        )
    }

    /**
     * Appends one frame line. Flushed immediately: a capture that ends by the device being switched
     * off must leave the frames it already recorded readable, and a buffered tail would lose the last
     * few seconds — which, at long sky exposures, can be most of the session.
     */
    override fun appendFrame(record: SkyFrameRecord): Boolean {
        check(!closed) { "SkySessionLogWriter is closed" }
        val stream = writer ?: return fail(SkySessionWriteFailure.NOT_STARTED)
        return try {
            stream.appendLine(encodeSkyFrameLine(record))
            stream.flush()
            writtenFrameCount += 1
            true
        } catch (_: IOException) {
            fail(SkySessionWriteFailure.FRAME_APPEND_FAILED)
        }
    }

    /** Flushes and closes the log stream. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        val stream = writer
        writer = null
        if (stream == null) return
        try {
            stream.flush()
            stream.close()
        } catch (_: IOException) {
            fail(SkySessionWriteFailure.CLOSE_FAILED)
        }
    }

    private fun fail(failure: SkySessionWriteFailure): Boolean {
        failureCount += 1
        lastFailure = failure
        return false
    }

    internal companion object {
        /** Zero-padded so a directory listing sorts in capture order. */
        fun lumaFileName(sequence: Long): String = "frame_" + sequence.toString().padStart(6, '0') + ".y"
    }
}
