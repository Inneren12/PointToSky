package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.LumaFrame
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogDocument
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.parseSkySessionLog
import java.io.File
import java.io.IOException

/**
 * SKY-3: the file seam a SKY-1 session directory is read through — and deliberately the *only* one.
 *
 * `:core:astro-core` cannot open a file by design: every consumer in it takes an in-memory input
 * ([parseSkySessionLog] takes a `String`, [LumaFrame.forReference] takes a `ByteArray`), which is what
 * keeps that module pure, deterministic and Android-free. This module supplies the missing
 * file->bytes/string glue and nothing else: it reads what
 * `dev.pointtosky.mobile.ar.camera.SkySessionLogWriter` writes, hands it to the existing entry points,
 * and reimplements none of the parsing, projection or detection.
 *
 * ## The layout this reads
 * Mirrors the writer exactly (`SkySessionLogWriter`'s KDoc and its `SKY_SESSION_LOG_FILE_NAME` /
 * `SKY_SESSION_FRAMES_DIRECTORY_NAME` constants):
 * ```text
 * <session-dir>/
 *   session.jsonl          header line, then one line per frame
 *   frames/frame_000000.y  packed 8-bit luma, rowStridePx * heightPx bytes, no header
 *   frames/frame_000001.y
 * ```
 * A frame line addresses its pixels by [SkyLumaReference.path], which the writer emits **relative to
 * the session directory** (`"frames/frame_NNNNNN.y"`), so a directory pulled off a device reads the
 * same anywhere. This resolves that path against the session directory and refuses anything that
 * escapes it — a log is data from a device, and data must not be able to name `../../etc/passwd`.
 *
 * ## Every failure is a value
 * Reading a capture is an expected runtime activity with expected failures: a session that filled the
 * disk stops mid-flush, leaving a truncated final line or a short frame file. Nothing here throws for
 * those; each one is a categorized reason a caller can count and print, which is what keeps a partial
 * session usable instead of fatal.
 *
 * [SKY_SESSION_LOG_FILE_NAME] is the log file inside a session directory: the same name
 * `SkySessionLogWriter` creates.
 */
const val SKY_SESSION_LOG_FILE_NAME: String = "session.jsonl"

/** The subdirectory the writer puts one raw luma plane per analyzed frame into. */
const val SKY_SESSION_FRAMES_DIRECTORY_NAME: String = "frames"

/** Why a session directory could not be turned into a replayable log. */
enum class SkySessionLoadFailure {
    /** The path does not exist. */
    DIRECTORY_MISSING,

    /** The path exists but is a file, not a session directory. */
    NOT_A_DIRECTORY,

    /** No `session.jsonl` inside it. Whatever this directory is, it is not a sky session. */
    LOG_MISSING,

    /** `session.jsonl` exists but could not be read (permissions, I/O error, not valid UTF-8). */
    LOG_UNREADABLE,

    /**
     * The log parsed but carries no supported header line, so there are no tolerances, no intrinsics
     * and no clock alignment to replay any frame against. Frames read before a header are
     * [SkySessionLogDocument.orphanFrames] and are never adopted by one.
     */
    HEADER_MISSING,
}

/** A session directory that parsed, or the categorized reason it did not. */
sealed interface SkySessionLoadResult {
    data class Loaded(
        val sessionDirectory: File,
        val header: SkySessionLogHeader,
        val document: SkySessionLogDocument,
    ) : SkySessionLoadResult

    data class Failed(
        val reason: SkySessionLoadFailure,
        val detail: String? = null,
    ) : SkySessionLoadResult
}

/** Why one frame's pixels could not be read. */
enum class SkyLumaReadFailure {
    /** [SkyLumaReference.path] resolved outside the session directory. Never followed. */
    PATH_ESCAPES_SESSION,

    /** The referenced frame file is not there — the line was flushed but the pixels were not. */
    FILE_MISSING,

    /** The path exists but is a directory or a special file. */
    NOT_A_FILE,

    /** The file is there but could not be read (permissions, I/O error). */
    READ_FAILED,

    /**
     * The file is a different size than the line says. A truncated or replaced frame is refused rather
     * than detected in: whatever the tail of a short buffer holds is not this frame's sky.
     */
    LENGTH_MISMATCH,

    /** The reference names a luma format this build cannot decode. Only `RAW_Y8` exists today. */
    UNSUPPORTED_FORMAT,
}

/** One frame's pixels, or the categorized reason they are unavailable. */
sealed interface SkyLumaReadResult {
    data class Loaded(
        val frame: LumaFrame,
    ) : SkyLumaReadResult

    data class Failed(
        val reason: SkyLumaReadFailure,
        val detail: String? = null,
    ) : SkyLumaReadResult
}

/**
 * Reads `<sessionDirectory>/session.jsonl` and parses it with the existing [parseSkySessionLog].
 *
 * The whole file is read into memory rather than streamed. A session log is one JSON line per analyzed
 * frame — kilobytes per frame against the megabytes of pixels each line points at — so the log itself
 * is never the large part of a session, and the in-memory form is what the pure parser accepts.
 */
fun loadSkySessionLog(sessionDirectory: File): SkySessionLoadResult {
    if (!sessionDirectory.exists()) {
        return SkySessionLoadResult.Failed(SkySessionLoadFailure.DIRECTORY_MISSING, sessionDirectory.path)
    }
    if (!sessionDirectory.isDirectory) {
        return SkySessionLoadResult.Failed(SkySessionLoadFailure.NOT_A_DIRECTORY, sessionDirectory.path)
    }
    val logFile = File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME)
    if (!logFile.isFile) {
        return SkySessionLoadResult.Failed(SkySessionLoadFailure.LOG_MISSING, logFile.path)
    }
    val text =
        try {
            logFile.readText()
        } catch (e: IOException) {
            return SkySessionLoadResult.Failed(SkySessionLoadFailure.LOG_UNREADABLE, e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return SkySessionLoadResult.Failed(SkySessionLoadFailure.LOG_UNREADABLE, e.javaClass.simpleName)
        }

    val document = parseSkySessionLog(text)
    val header =
        document.header
            ?: return SkySessionLoadResult.Failed(SkySessionLoadFailure.HEADER_MISSING, logFile.path)
    return SkySessionLoadResult.Loaded(sessionDirectory = sessionDirectory, header = header, document = document)
}

/**
 * Reads the frame file [reference] points at and wraps it with the existing [LumaFrame.forReference].
 *
 * The bytes are handed over verbatim: a `.y` file is exactly `rowStridePx * heightPx` bytes of packed
 * 8-bit luma with no header, no padding of its own, and no framing — `SkySessionLogWriter` writes the
 * camera's `planes[0]` buffer straight out and records that length as
 * [SkyLumaReference.byteLength]. So the file's length *is* the reference's length, and the row padding
 * between `widthPx` and `rowStridePx` is inside the data where `LumaFrame` expects it. Nothing here
 * repacks, re-strides or trims; doing so would be the one transformation capable of silently shifting
 * every centroid this module then reports.
 */
fun readSkyLumaFrame(
    sessionDirectory: File,
    reference: SkyLumaReference,
): SkyLumaReadResult {
    if (reference.format != SkyLumaFormat.RAW_Y8) {
        return SkyLumaReadResult.Failed(SkyLumaReadFailure.UNSUPPORTED_FORMAT, reference.format.name)
    }
    return when (val located = locateLumaFile(sessionDirectory, reference)) {
        is LocatedLumaFile.Failed -> SkyLumaReadResult.Failed(located.reason, located.detail)
        is LocatedLumaFile.Found -> readLumaFrame(located.file, reference)
    }
}

/** The frame file, or the reason it is not one. */
private sealed interface LocatedLumaFile {
    data class Found(
        val file: File,
    ) : LocatedLumaFile

    data class Failed(
        val reason: SkyLumaReadFailure,
        val detail: String?,
    ) : LocatedLumaFile
}

/**
 * [reference]'s path resolved under [sessionDirectory], refusing anything outside it.
 *
 * `SkyLumaReference` already rejects an absolute path, but a relative one can still climb with `..`,
 * and a symlink inside the session directory can point anywhere at all. Both are ruled out by
 * comparing canonical paths, so the worst a malformed or hostile log can do is fail to load.
 */
private fun locateLumaFile(
    sessionDirectory: File,
    reference: SkyLumaReference,
): LocatedLumaFile {
    val file =
        try {
            val root = sessionDirectory.canonicalFile
            val target = File(root, reference.path).canonicalFile
            val inside = target.path == root.path || target.path.startsWith(root.path + File.separator)
            target.takeIf { inside }
        } catch (e: IOException) {
            return LocatedLumaFile.Failed(SkyLumaReadFailure.READ_FAILED, e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return LocatedLumaFile.Failed(SkyLumaReadFailure.READ_FAILED, e.javaClass.simpleName)
        } ?: return LocatedLumaFile.Failed(SkyLumaReadFailure.PATH_ESCAPES_SESSION, reference.path)

    if (!file.exists()) return LocatedLumaFile.Failed(SkyLumaReadFailure.FILE_MISSING, reference.path)
    if (!file.isFile) return LocatedLumaFile.Failed(SkyLumaReadFailure.NOT_A_FILE, reference.path)
    return LocatedLumaFile.Found(file)
}

private fun readLumaFrame(
    file: File,
    reference: SkyLumaReference,
): SkyLumaReadResult {
    // Checked before the read, not after: a reference that disagrees with the file on disk is a
    // truncated capture, and reading a whole plane to then reject it helps nobody.
    if (file.length() != reference.byteLength) {
        return SkyLumaReadResult.Failed(
            SkyLumaReadFailure.LENGTH_MISMATCH,
            "${reference.path}: file is ${file.length()} bytes, line says ${reference.byteLength}",
        )
    }
    val data =
        try {
            file.readBytes()
        } catch (e: IOException) {
            return SkyLumaReadResult.Failed(SkyLumaReadFailure.READ_FAILED, e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return SkyLumaReadResult.Failed(SkyLumaReadFailure.READ_FAILED, e.javaClass.simpleName)
        }
    // The file can still change size between the stat above and the read, so the length is checked
    // again against what was actually handed over — the one check that cannot be raced.
    if (data.size.toLong() != reference.byteLength) {
        return SkyLumaReadResult.Failed(
            SkyLumaReadFailure.LENGTH_MISMATCH,
            "${reference.path}: read ${data.size} bytes, line says ${reference.byteLength}",
        )
    }
    return SkyLumaReadResult.Loaded(LumaFrame.forReference(reference, data))
}
