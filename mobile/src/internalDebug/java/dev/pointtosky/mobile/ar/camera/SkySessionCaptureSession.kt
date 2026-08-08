package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.toStarProjectionContext
import java.io.File

/**
 * SKY-1 (`internalDebug`-only): the per-bind runtime wiring — pairing, geometry, intrinsics, and the
 * recorder — held outside the composable so a recomposition can never rebuild it mid-capture.
 *
 * ## Generation isolation is the point of this class
 * Everything a bind resolves is bind-scoped and none of it survives a rebind:
 *  - `SessionScopedCameraIntrinsicsResolver.resolveOnce` caches its first answer *forever* per
 *    instance, which is correct for one camera session and catastrophic across two;
 *  - `CameraSessionGeometryProvider.onIntrinsicsResolved` likewise accepts only its first call;
 *  - `CameraTimestampSynchronizer` holds a rotation history paired against one buffer's frames;
 *  - the camera's reported `SENSOR_INFO_TIMESTAMP_SOURCE` ([SkyCaptureScope.timestampSource]) is a
 *    per-camera fact, and a logical camera need not agree with its own physical sub-cameras.
 *
 * So a [SkyCaptureScope] holds all of it plus the bound camera's identity, and a new bind epoch
 * replaces the whole scope at once. There is no path by which a header can pair one generation's
 * camera id with another generation's intrinsics or clock provenance: [startRecording] reads them all
 * out of a single scope while holding [lock], and a scope's camera id, resolver and timestamp source
 * are only ever written together in [rotateTo]/[adoptCameraInfo].
 *
 * Frames and camera-info callbacks carrying an older epoch are dropped ([SkyGenerationTransition.STALE]).
 * They are not merely late — they were produced by a different sensor, buffer size, or exposure.
 *
 * ## Threading
 * One [lock] serializes everything. Callbacks arrive on the camera analysis executor (frames), the
 * main thread (bind, start/stop, dispose) and the sensor thread (rotation samples); the state they
 * touch is shared, so it is guarded rather than volatile-annotated field by field.
 */
internal class SkySessionCaptureSession(
    private val sessionsRoot: File,
    private val deviceModel: String?,
    private val scopeFactory: () -> SkyCaptureScope = { SkyCaptureScope.create() },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    /**
     * Reads the bound camera's `SENSOR_INFO_TIMESTAMP_SOURCE`. Injected so the header's clock claim is
     * checkable on the JVM: the production probe needs `Camera2Interop` and a live `CameraInfo`, and
     * the invariant worth testing — that an unprovable timestamp source produces an unalignable
     * session rather than an assumed-zero one — has nothing to do with either.
     */
    private val timestampSourceProbe: (CameraInfo) -> SkyCameraTimestampSource = ::probeSkyCameraTimestampSource,
) {
    private val lock = Any()
    private val generations = SkyCaptureGenerationTracker()

    private var scope: SkyCaptureScope? = null
    private var recorder: SkySessionRecorder? = null
    private var analyzedFrameCount = 0L
    private var staleFrameCount = 0L
    private var joinDropCount = 0L
    private var lastJoinDropReason: SkyJoinDropReason? = null

    val isRecording: Boolean get() = synchronized(lock) { recorder?.isRecording == true }

    /** The live bind's epoch, or `0` before the first bind. */
    val currentEpoch: Long get() = synchronized(lock) { generations.currentEpoch }

    /**
     * Announces a successful bind.
     *
     * A newer epoch rotates the whole scope: the previous one is disposed, and any recording still
     * running under it is stopped, because its remaining frames would be shot through different
     * intrinsics than its header describes.
     */
    fun onBind(
        epoch: Long,
        configuration: SkyCaptureConfiguration,
        cameraInfo: CameraInfo?,
    ): Boolean =
        synchronized(lock) {
            when (generations.observe(epoch, configuration)) {
                SkyGenerationTransition.STALE -> false
                SkyGenerationTransition.STARTED -> {
                    rotateTo(configuration, cameraInfo)
                    true
                }

                SkyGenerationTransition.CURRENT -> {
                    scope?.adoptCameraInfo(cameraInfo)
                    true
                }
            }
        }

    /** Feeds the rotation history the CAM-1d pairing reads. Ignored before the first bind. */
    fun onRotationSample(sample: TimedRotationSample) {
        synchronized(lock) { scope?.synchronizer?.onRotationSample(sample) }
    }

    fun onViewportChanged(
        widthPx: Int,
        heightPx: Int,
    ) {
        synchronized(lock) { scope?.geometryProvider?.onViewportChanged(widthPx, heightPx) }
    }

    /** Records everything the join released without a pair, for the HUD's dropped count. */
    fun onJoinDrops(
        epoch: Long,
        drops: List<SkyJoinDrop>,
        previous: SkyCaptureUiState,
    ): SkyCaptureUiState =
        synchronized(lock) {
            if (!generations.isCurrent(epoch)) return@synchronized previous
            joinDropCount += drops.size
            drops.lastOrNull()?.let { lastJoinDropReason = it.reason }
            previous.copy(joinDropCount = joinDropCount, lastJoinDropReason = lastJoinDropReason)
        }

    /**
     * Called once per analyzed frame whose exposure has already been matched by exact
     * `SENSOR_TIMESTAMP`, on the analysis thread.
     *
     * A frame from a stale generation is counted and discarded — never fed to the intrinsics resolver,
     * whose first answer would then be cached for the whole *new* session.
     */
    fun onFrame(
        epoch: Long,
        configuration: SkyCaptureConfiguration,
        joined: SkyJoinedFrame,
        observer: SkyObserverContext?,
        stars: List<EquatorialStarDirection>,
        previous: SkyCaptureUiState,
    ): SkyCaptureUiState =
        synchronized(lock) {
            when (generations.observe(epoch, configuration)) {
                SkyGenerationTransition.STALE -> {
                    staleFrameCount += 1
                    return@synchronized previous.copy(staleFrameCount = staleFrameCount)
                }

                // A frame can legitimately be the first contact from a new bind: onBind is delivered on
                // the main thread and the analyzer is already running. Rotate here too rather than
                // letting the frame resolve intrinsics into the previous generation's resolver.
                SkyGenerationTransition.STARTED -> rotateTo(configuration, cameraInfo = null)
                SkyGenerationTransition.CURRENT -> Unit
            }

            val active = scope ?: return@synchronized previous
            analyzedFrameCount += 1
            val frame = joined.frame

            active.cameraInfo?.let { info ->
                val resolution =
                    active.intrinsics.resolve(
                        cameraInfo = info,
                        imageWidthPx = frame.metadata.bufferWidthPx,
                        imageHeightPx = frame.metadata.bufferHeightPx,
                        sensorToBufferTransform = frame.metadata.sensorToBufferTransform,
                    )
                active.geometryProvider.onIntrinsicsResolved(resolution)
            }

            active.synchronizer.onCameraFrame(frame.metadata)?.let { pairing ->
                active.geometryProvider.onPairedFrame(frame.metadata, pairing)
            }

            val geometryResult = active.geometryProvider.state.value
            val geometry = (geometryResult as? CameraSessionGeometryResult.Ready)?.geometry
            val prediction = predictionFor(geometry, observer, stars)

            val recording = recorder
            val outcome =
                if (recording != null && geometry != null) {
                    recording.record(
                        frame = frame,
                        exposure = joined.exposure,
                        geometry = geometry,
                        capturedAtEpochMillis = nowEpochMillis(),
                        observer = observer,
                        stars = if (prediction is StarPredictionBatchResult.Ready) stars else emptyList(),
                        prediction = prediction ?: StarPredictionBatchResult.Ready.of(emptyList()),
                    )
                } else {
                    previous.lastOutcome
                }

            previous.copy(
                analyzedFrameCount = analyzedFrameCount,
                staleFrameCount = staleFrameCount,
                recordedFrameCount = recording?.recordedFrameCount ?: 0L,
                droppedFrameCount = recording?.droppedFrameCount ?: 0L,
                writtenLumaBytes = recording?.writtenLumaBytes ?: 0L,
                lastOutcome = outcome,
                // A start failure ("intrinsics not resolved yet") must survive the next frame's status
                // update - it is the one message the operator needs to act on, and wiping it a frame
                // later would make Record look like it silently did nothing.
                lastFailureReason = recording?.lastFailureReason ?: previous.lastFailureReason,
                geometryStatus = geometryResult::class.simpleName ?: "UNKNOWN",
                predictedStarCount = (prediction as? StarPredictionBatchResult.Ready)?.projections?.size ?: 0,
                exposureAvailable = joined.exposure.exposureTimeNanos != null,
                sessionDirectoryPath = recording?.sessionDirectoryPath ?: previous.sessionDirectoryPath,
            )
        }

    /**
     * Whether the live generation may start recording, and with which confirmed exposure.
     *
     * [observer] is supplied by the caller rather than held here: the observing context is a property
     * of *now*, assembled per frame from the live location and the current instant, and caching it in
     * the session would let Record be enabled against a fix that has since gone away.
     */
    fun recordingGate(
        requested: SkyManualExposureRequest?,
        capability: SkyManualExposureCapability?,
        observer: SkyObserverContext?,
    ): SkyRecordingGate =
        synchronized(lock) {
            val active = scope
            evaluateSkyRecordingGate(
                requested = requested,
                capability = capability,
                appliedExposure = generations.currentConfiguration?.exposure,
                intrinsicsResolved = active?.intrinsics?.publishedResolution != null,
                observer = observer,
            )
        }

    /**
     * Opens a **new** session directory, writes the header, and starts recording.
     *
     * Always a new recorder, sink and directory — a stopped recorder is terminal (see
     * [SkySessionRecorder]) and reusing a directory would splice two sessions into one file.
     *
     * Every value in the header comes from the same [SkyCaptureScope] under the same lock acquisition,
     * so the camera id and the intrinsics can never come from different generations.
     */
    fun startRecording(
        requested: SkyManualExposureRequest?,
        capability: SkyManualExposureCapability?,
        observer: SkyObserverContext?,
        previous: SkyCaptureUiState,
    ): SkyCaptureUiState =
        synchronized(lock) {
            if (recorder?.isRecording == true) return@synchronized previous

            val gate =
                evaluateSkyRecordingGate(
                    requested = requested,
                    capability = capability,
                    appliedExposure = generations.currentConfiguration?.exposure,
                    intrinsicsResolved = scope?.intrinsics?.publishedResolution != null,
                    observer = observer,
                )
            if (gate is SkyRecordingGate.Blocked) {
                return@synchronized previous.copy(lastFailureReason = gate.reason.name, sessionDirectoryPath = null)
            }

            val active = scope ?: return@synchronized previous.copy(lastFailureReason = "no_active_bind")
            val intrinsics =
                active.intrinsics.publishedResolution
                    ?: return@synchronized previous.copy(
                        lastFailureReason = SkyRecordingBlockedReason.INTRINSICS_NOT_RESOLVED.name,
                    )
            val geometry = (active.geometryProvider.state.value as? CameraSessionGeometryResult.Ready)?.geometry
            val configuration =
                generations.currentConfiguration
                    ?: return@synchronized previous.copy(lastFailureReason = "no_active_bind")

            val startedAt = nowEpochMillis()
            val sessionId = "sky_${startedAt}_g${generations.currentEpoch}"
            val writer = SkySessionLogWriter(File(sessionsRoot, sessionId))
            val header =
                buildSkySessionHeader(
                    sessionId = sessionId,
                    startedAtEpochMillis = startedAt,
                    bufferWidthPx = geometry?.frame?.bufferWidthPx ?: configuration.resolution.widthPx,
                    bufferHeightPx = geometry?.frame?.bufferHeightPx ?: configuration.resolution.heightPx,
                    intrinsics = intrinsics,
                    // Read out of the same scope, under the same lock acquisition, as the camera id and
                    // the intrinsics - so the header's clock claim always describes the camera the
                    // header names.
                    clockAlignment = skyClockAlignmentFor(active.timestampSource),
                    maxPairDeltaNanos = active.synchronizer.maxAllowedDeltaNanos,
                    clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
                    deviceModel = deviceModel,
                    cameraId = configuration.physicalCameraId,
                    physicalCameraIds =
                        active.intrinsics.calibrationDiagnostics
                            ?.physicalCameraIds
                            ?.toList()
                            ?.sorted()
                            .orEmpty(),
                    calibration = active.intrinsics.calibrationDiagnostics?.toSkyCalibrationRecord(),
                    pinhole = geometry?.let { skyPinholeRecordOrNull(it) },
                    notes = null,
                )

            val started = SkySessionRecorder(writer)
            if (!started.start(header)) {
                recorder = null
                return@synchronized previous.copy(
                    sessionDirectoryPath = null,
                    lastFailureReason = writer.lastFailure?.name ?: "session_start_failed",
                )
            }
            recorder = started
            previous.copy(sessionDirectoryPath = started.sessionDirectoryPath, lastFailureReason = null)
        }

    fun stopRecording() {
        synchronized(lock) { recorder?.stop() }
    }

    fun dispose() {
        synchronized(lock) {
            recorder?.stop()
            recorder = null
            scope?.dispose()
            scope = null
            generations.clear()
        }
    }

    /**
     * Replaces the whole per-bind scope. Any recording still running belongs to the outgoing
     * generation and is stopped first — its header describes intrinsics the incoming bind no longer
     * has.
     */
    private fun rotateTo(
        @Suppress("UNUSED_PARAMETER") configuration: SkyCaptureConfiguration,
        cameraInfo: CameraInfo?,
    ) {
        recorder?.stop()
        recorder = null
        scope?.dispose()
        scope = scopeFactory().also { it.adoptCameraInfo(cameraInfo) }
        analyzedFrameCount = 0L
        joinDropCount = 0L
        lastJoinDropReason = null
        // staleFrameCount is deliberately cumulative across generations: it is a diagnostic about how
        // much the rebinds cost, and resetting it would hide exactly that.
    }

    /**
     * Attaches [cameraInfo] to this scope and resolves the clock provenance that goes with it.
     *
     * Both are written together, under [lock], for the same reason the camera id and the intrinsics
     * are: a header must never pair one camera's identity with another camera's timestamp source. The
     * probe runs only when the `CameraInfo` instance actually changes — a repeated `onBind` for the
     * live epoch is not a new camera and must not re-read characteristics on the main thread.
     */
    private fun SkyCaptureScope.adoptCameraInfo(cameraInfo: CameraInfo?) {
        if (cameraInfo === this.cameraInfo) return
        this.cameraInfo = cameraInfo
        timestampSource =
            cameraInfo?.let(timestampSourceProbe) ?: SkyCameraTimestampSource.UNAVAILABLE
    }

    private fun predictionFor(
        geometry: CameraSessionGeometry?,
        observer: SkyObserverContext?,
        stars: List<EquatorialStarDirection>,
    ): StarPredictionBatchResult? {
        if (geometry == null || observer == null || stars.isEmpty()) return null
        val context = observer.toStarProjectionContext() ?: return null
        return projectStars(stars, context, geometry)
    }
}

/**
 * Everything one camera bind owns. Created fresh per generation and disposed as a unit, because every
 * component inside resolves exactly once per instance and none of those answers survives a rebind.
 */
internal class SkyCaptureScope(
    val synchronizer: CameraTimestampSynchronizer,
    val geometryProvider: CameraSessionGeometryProvider,
    val intrinsics: SkyIntrinsicsSource,
) {
    /** Set when the bind reports its `CameraInfo`; `null` until then. */
    var cameraInfo: CameraInfo? = null

    /**
     * What *this bind's* camera says about its `SENSOR_TIMESTAMP` time base, and therefore whether this
     * generation's poses can be placed on its frame clock at all.
     *
     * Bind-scoped like everything else here, and for the same kind of reason: switching physical camera
     * can switch timestamp source (a logical camera and one of its physical sub-cameras need not agree),
     * so a value carried across a rebind could put a proven-comparable claim in the header of a session
     * whose camera never made it. `UNAVAILABLE` until a `CameraInfo` arrives — never optimistically
     * `REALTIME`.
     */
    var timestampSource: SkyCameraTimestampSource = SkyCameraTimestampSource.UNAVAILABLE

    fun dispose() {
        synchronizer.dispose()
        geometryProvider.dispose()
        cameraInfo = null
        timestampSource = SkyCameraTimestampSource.UNAVAILABLE
    }

    internal companion object {
        fun create(): SkyCaptureScope {
            val synchronizer = CameraTimestampSynchronizer()
            return SkyCaptureScope(
                synchronizer = synchronizer,
                geometryProvider =
                    CameraSessionGeometryProvider(
                        maxAllowedPairDeltaNanos = synchronizer.maxAllowedDeltaNanos,
                    ),
                intrinsics = SessionScopedSkyIntrinsicsSource(),
            )
        }
    }
}

/**
 * The one-per-bind intrinsics resolution a [SkyCaptureScope] owns.
 *
 * A narrow interface over `SessionScopedCameraIntrinsicsResolver` rather than the class itself, for a
 * reason that is the whole point of this review item: the invariant worth testing is that a session
 * header's camera id and its intrinsics come from the *same* generation, and that cannot be exercised
 * against a resolver whose only entry point needs a live `CameraInfo`. With this seam the rule is
 * checkable on the JVM; without it, it is only inspectable by reading the code.
 */
internal interface SkyIntrinsicsSource {
    /** What this bind resolved, or `null` before the first analyzed frame. */
    val publishedResolution: CameraIntrinsicsResolution?

    val calibrationDiagnostics: CameraCalibrationDiagnostics?

    /** Resolves once per instance; later calls return the cached answer. */
    fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int,
        imageHeightPx: Int,
        sensorToBufferTransform: SensorToBufferMatrix3?,
    ): CameraIntrinsicsResolution
}

/** The production [SkyIntrinsicsSource]: one `SessionScopedCameraIntrinsicsResolver` per bind. */
internal class SessionScopedSkyIntrinsicsSource(
    private val resolver: SessionScopedCameraIntrinsicsResolver = SessionScopedCameraIntrinsicsResolver(),
) : SkyIntrinsicsSource {
    override val publishedResolution: CameraIntrinsicsResolution? get() = resolver.lastPublishedResolution

    override val calibrationDiagnostics: CameraCalibrationDiagnostics? get() = resolver.lastCalibrationDiagnostics

    override fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int,
        imageHeightPx: Int,
        sensorToBufferTransform: SensorToBufferMatrix3?,
    ): CameraIntrinsicsResolution =
        resolver.resolveOnce(
            cameraInfo = cameraInfo,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            sensorToBufferTransform = sensorToBufferTransform,
        )
}
