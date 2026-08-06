package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader

/**
 * SKY-1 (`internalDebug`-only): turns a stream of analyzed frames into a session log on disk.
 *
 * Owns exactly two pieces of state a caller should not have to thread through itself — the frame
 * sequence number and the started/stopped flag — and delegates everything else: the pure record
 * assembly to [buildSkyFrameRecord], the bytes to [SkySessionLogWriter].
 *
 * Free of Android types on purpose, so the whole record→disk path is unit-testable on the JVM with a
 * temporary directory and no device.
 *
 * ## Threading
 * One recorder belongs to one capture session and is driven from that session's single analysis
 * executor thread ([SkySessionCameraPreview] uses `Executors.newSingleThreadExecutor`), which is also
 * the thread [SkySessionLogWriter] documents as its own. [stop] may be called from elsewhere once the
 * analyzer has been torn down.
 */
internal class SkySessionRecorder(
    private val writer: SkySessionLogWriter,
) {
    private var nextSequence = 0L

    /**
     * Whether [start] succeeded and [stop] has not been called. `@Volatile` because the capture screen
     * reads it during composition on the main thread while [record] runs on the analysis thread.
     */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** Frames written to the log. */
    val recordedFrameCount: Long get() = writer.writtenFrameCount

    /** Frames the camera delivered but which never reached the log; see [lastFailureReason]. */
    var droppedFrameCount: Long = 0L
        private set

    /** Total luma bytes on disk for this session. */
    val writtenLumaBytes: Long get() = writer.writtenLumaBytes

    val lastFailureReason: String? get() = writer.lastFailureReason

    /** The directory the session was written to, for sharing or `adb pull`. */
    val sessionDirectoryPath: String get() = writer.sessionDirectory.absolutePath

    /** Writes the header and opens the session. Returns `false` when the log could not be created. */
    fun start(header: SkySessionLogHeader): Boolean {
        if (isRecording) return true
        isRecording = writer.start(header)
        return isRecording
    }

    /**
     * Records one frame: pixels first, then the log line that references them.
     *
     * The order matters and is not incidental. A log line pointing at a luma file that does not exist
     * is a broken record an offline reader has to special-case; a luma file with no log line is merely
     * an orphan byte blob that costs disk and nothing else. So the pixels are written first, and the
     * line only if they landed.
     *
     * [stars] and [prediction] must be the exact input and output of the same `projectStars` batch for
     * [geometry] — see [buildSkyFrameRecord].
     */
    fun record(
        frame: SkyAnalyzedFrame,
        geometry: CameraSessionGeometry,
        capturedAtEpochMillis: Long,
        observer: SkyObserverContext?,
        stars: List<EquatorialStarDirection>,
        prediction: StarPredictionBatchResult,
    ): SkyRecordOutcome {
        if (!isRecording) return SkyRecordOutcome.NOT_RECORDING
        if (geometry.frame.timestampNanos != frame.metadata.timestampNanos) {
            // The geometry belongs to a different frame than the pixels do. Recording it would pair
            // one frame's pose with another's image - the single worst corruption this log can carry,
            // because nothing downstream could ever detect it.
            droppedFrameCount += 1
            return SkyRecordOutcome.GEOMETRY_FRAME_MISMATCH
        }

        val sequence = nextSequence
        val luma =
            writer.writeLumaFrame(
                sequence = sequence,
                data = frame.lumaData,
                widthPx = frame.lumaWidthPx,
                heightPx = frame.lumaHeightPx,
                rowStridePx = frame.lumaRowStridePx,
            )
        if (luma == null) {
            droppedFrameCount += 1
            return SkyRecordOutcome.LUMA_WRITE_FAILED
        }

        val record =
            buildSkyFrameRecord(
                sequence = sequence,
                capturedAtEpochMillis = capturedAtEpochMillis,
                geometry = geometry,
                luma = luma,
                observer = observer,
                exposure = frame.exposure,
                stars = stars,
                prediction = prediction,
            )
        if (!writer.appendFrame(record)) {
            droppedFrameCount += 1
            return SkyRecordOutcome.LOG_WRITE_FAILED
        }
        nextSequence += 1
        return SkyRecordOutcome.RECORDED
    }

    /** Flushes and closes the log. Idempotent. */
    fun stop() {
        if (!isRecording) return
        isRecording = false
        writer.close()
    }
}

/** What happened to one frame handed to [SkySessionRecorder.record]. */
internal enum class SkyRecordOutcome {
    RECORDED,

    /** [SkySessionRecorder.start] was never called, or [SkySessionRecorder.stop] already ran. */
    NOT_RECORDING,

    /** The supplied geometry describes a different frame than the supplied pixels. */
    GEOMETRY_FRAME_MISMATCH,

    /** The luma file could not be written; no log line was appended for this frame. */
    LUMA_WRITE_FAILED,

    /** The pixels landed but the log line did not, leaving an orphan luma file. */
    LOG_WRITE_FAILED,
}
