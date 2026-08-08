package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader

/**
 * SKY-1 (`internalDebug`-only): turns a stream of analyzed frames into a session log on disk.
 *
 * ## One lock, one terminal state machine
 * `record` runs on the camera analysis executor; `stop` runs on the main thread when the operator
 * taps Stop; the analyzer stays bound and keeps delivering frames afterwards. Without serialization
 * that means `SkySessionLogSink.close()` can run *while* a frame is halfway between its luma file and
 * its log line, leaving a session whose last luma file has no line and whose stream was closed
 * mid-write.
 *
 * So [start], [record] and [stop] each hold [lock] for their **entire** body, sink calls included.
 * That gives exactly the boundary this dataset needs:
 *  - a frame already committing finishes atomically — [stop] waits for it;
 *  - a frame that starts after [stop] observes [SkyRecorderState.STOPPED] and is rejected before any
 *    byte is written;
 *  - [SkySessionLogSink.close] can never interleave with `writeLumaFrame`/`appendFrame`.
 *
 * Holding a lock across disk I/O means Stop can block the main thread for as long as one frame write
 * takes. That is a deliberate trade: one flushed line is a bounded, sub-millisecond write, and the
 * alternative — a second executor to hand ownership to — buys nothing here except another lifecycle
 * to get wrong.
 *
 * ## Terminal, never reusable
 * [SkyRecorderState.STOPPED] is a one-way door. Recording again means a new recorder, a new sink, and
 * a new session directory — see [SkySessionCaptureSession.startRecording]. A recorder that could
 * restart would have to decide whether to reuse its sequence numbering and its directory, and both
 * answers corrupt something.
 *
 * Free of Android types on purpose, so the whole record→disk path is unit-testable on the JVM.
 */
internal class SkySessionRecorder(
    private val sink: SkySessionLogSink,
) {
    private val lock = Any()
    private var state = SkyRecorderState.IDLE
    private var nextSequence = 0L
    private var droppedFrames = 0L
    private var lastDropReason: SkyRecordOutcome? = null

    val currentState: SkyRecorderState get() = synchronized(lock) { state }

    val isRecording: Boolean get() = synchronized(lock) { state == SkyRecorderState.RECORDING }

    /** Frames written to the log. */
    val recordedFrameCount: Long get() = synchronized(lock) { sink.writtenFrameCount }

    /** Frames the camera delivered but which never reached the log; see [lastFailureReason]. */
    val droppedFrameCount: Long get() = synchronized(lock) { droppedFrames }

    /** Total luma bytes on disk for this session. */
    val writtenLumaBytes: Long get() = synchronized(lock) { sink.writtenLumaBytes }

    /** The most recent typed failure, sink-level or recorder-level, as a short stable name. */
    val lastFailureReason: String?
        get() =
            synchronized(lock) {
                sink.lastFailure?.name ?: lastDropReason?.takeIf { it != SkyRecordOutcome.RECORDED }?.name
            }

    /** The directory the session was written to, for sharing or `adb pull`. */
    val sessionDirectoryPath: String get() = sink.sessionPath

    /**
     * Writes the header and opens the session. Returns `false` when the log could not be created, or
     * when this recorder has already been stopped — a stopped recorder is terminal and must never
     * reopen its own session.
     */
    fun start(header: SkySessionLogHeader): Boolean =
        synchronized(lock) {
            when (state) {
                SkyRecorderState.RECORDING -> true
                SkyRecorderState.STOPPED -> {
                    lastDropReason = SkyRecordOutcome.RECORDER_TERMINAL
                    false
                }

                SkyRecorderState.IDLE -> {
                    val started = sink.start(header)
                    if (started) state = SkyRecorderState.RECORDING
                    started
                }
            }
        }

    /**
     * Records one frame: pixels first, then the log line that references them.
     *
     * The order matters and is not incidental. A log line pointing at a luma file that does not exist
     * is a broken record an offline reader has to special-case; a luma file with no log line is merely
     * an orphan byte blob that costs disk and nothing else. So the pixels are written first, and the
     * line only if they landed.
     *
     * [exposure] is the `CaptureResult` sample the join proved belongs to these exact pixels (see
     * [SkyExposureJoin]) — never the most recent one seen. It is validated once more here, because
     * this is the last point before the bytes reach disk.
     *
     * [observer] must carry a location *and* its magnetic declination. It is typed nullable because the
     * capture path genuinely may not have one, not because a frame without one is acceptable: such a
     * frame is dropped here rather than written with `observer: null`.
     *
     * [stars] and [prediction] must be the exact input and output of the same `projectStars` batch for
     * [geometry] — see [buildSkyFrameRecord].
     */
    fun record(
        frame: SkyAnalyzedFrame,
        exposure: SkyExposureSample,
        geometry: CameraSessionGeometry,
        capturedAtEpochMillis: Long,
        observer: SkyObserverContext?,
        stars: List<EquatorialStarDirection>,
        prediction: StarPredictionBatchResult,
    ): SkyRecordOutcome =
        synchronized(lock) {
            if (state !=
                SkyRecorderState.RECORDING
            ) {
                return@synchronized note(SkyRecordOutcome.NOT_RECORDING, counted = false)
            }

            if (geometry.frame.timestampNanos != frame.metadata.timestampNanos) {
                // The geometry belongs to a different frame than the pixels do. Recording it would pair
                // one frame's pose with another's image - the single worst corruption this log can
                // carry, because nothing downstream could ever detect it.
                return@synchronized note(SkyRecordOutcome.GEOMETRY_FRAME_MISMATCH)
            }

            if (!isUsableSkyObserverContext(observer)) {
                // Defence in depth. evaluateSkyRecordingGate refuses to start a session without an
                // observing context, so reaching here means the fix (or its declination) vanished
                // mid-session - a permission revoked from Settings, a provider that stopped. Such a
                // frame is not a SKY-1 frame: it would replay as OBSERVER_CONTEXT_UNAVAILABLE and no
                // detector could use it. It is dropped with a typed reason rather than written with a
                // null observer, so the HUD's counts stay honest about what the session actually holds.
                return@synchronized note(SkyRecordOutcome.OBSERVER_CONTEXT_UNAVAILABLE)
            }

            val validation = validateSkyManualExposure(exposure, frame.metadata.timestampNanos)
            if (validation is SkyExposureValidation.Rejected) {
                return@synchronized note(
                    when (validation.reason) {
                        SkyExposureRejectReason.EXPOSURE_TIME_MISSING -> SkyRecordOutcome.EXPOSURE_TIME_MISSING
                        SkyExposureRejectReason.SENSITIVITY_MISSING -> SkyRecordOutcome.EXPOSURE_SENSITIVITY_MISSING
                        SkyExposureRejectReason.SENSOR_TIMESTAMP_MISMATCH ->
                            SkyRecordOutcome.EXPOSURE_TIMESTAMP_MISMATCH
                        SkyExposureRejectReason.AE_MODE_NOT_OFF -> SkyRecordOutcome.EXPOSURE_AE_NOT_OFF
                    },
                )
            }

            val sequence = nextSequence
            val luma =
                sink.writeLumaFrame(
                    sequence = sequence,
                    data = frame.lumaData,
                    widthPx = frame.lumaWidthPx,
                    heightPx = frame.lumaHeightPx,
                    rowStridePx = frame.lumaRowStridePx,
                ) ?: return@synchronized note(SkyRecordOutcome.LUMA_WRITE_FAILED)

            val record =
                buildSkyFrameRecord(
                    sequence = sequence,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    geometry = geometry,
                    luma = luma,
                    observer = observer,
                    exposure = exposure,
                    stars = stars,
                    prediction = prediction,
                )
            if (!sink.appendFrame(record)) return@synchronized note(SkyRecordOutcome.LOG_WRITE_FAILED)

            nextSequence += 1
            note(SkyRecordOutcome.RECORDED, counted = false)
        }

    /**
     * Flushes and closes the log, permanently. Idempotent, and safe to call while a [record] is in
     * flight — it waits for that frame to finish committing before closing.
     */
    fun stop() =
        synchronized(lock) {
            if (state == SkyRecorderState.STOPPED) return@synchronized
            val wasRecording = state == SkyRecorderState.RECORDING
            state = SkyRecorderState.STOPPED
            if (wasRecording) sink.close()
        }

    /** Records [outcome] as the latest, counting it against [droppedFrameCount] unless told otherwise. */
    private fun note(
        outcome: SkyRecordOutcome,
        counted: Boolean = true,
    ): SkyRecordOutcome {
        lastDropReason = outcome
        if (counted) droppedFrames += 1
        return outcome
    }
}

/** A recorder's lifecycle. [STOPPED] is terminal. */
internal enum class SkyRecorderState {
    IDLE,
    RECORDING,
    STOPPED,
}

/** What happened to one frame handed to [SkySessionRecorder.record]. */
internal enum class SkyRecordOutcome {
    RECORDED,

    /** [SkySessionRecorder.start] was never called, or [SkySessionRecorder.stop] already ran. */
    NOT_RECORDING,

    /** [SkySessionRecorder.start] was called on a recorder that had already been stopped. */
    RECORDER_TERMINAL,

    /** The supplied geometry describes a different frame than the supplied pixels. */
    GEOMETRY_FRAME_MISMATCH,

    /**
     * No usable observing context for this frame — no location fix, or no magnetic declination at it.
     * The start gate normally prevents this entirely; see the check in [SkySessionRecorder.record].
     */
    OBSERVER_CONTEXT_UNAVAILABLE,

    /** The matched `CaptureResult` reported no exposure time. */
    EXPOSURE_TIME_MISSING,

    /** The matched `CaptureResult` reported no sensitivity. */
    EXPOSURE_SENSITIVITY_MISSING,

    /** The matched `CaptureResult`'s `SENSOR_TIMESTAMP` is not this frame's. */
    EXPOSURE_TIMESTAMP_MISMATCH,

    /** Auto-exposure was still in control when these pixels were produced. */
    EXPOSURE_AE_NOT_OFF,

    /** The luma file could not be written; no log line was appended for this frame. */
    LUMA_WRITE_FAILED,

    /** The pixels landed but the log line did not, leaving an orphan luma file. */
    LOG_WRITE_FAILED,
}
