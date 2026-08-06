package dev.pointtosky.mobile.ar.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pointtosky.core.astro.catalog.PtskCatalogLoader
import dev.pointtosky.core.astro.catalog.isRenderablePoint
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.skylog.SkyCalibrationRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.toStarProjectionContext
import dev.pointtosky.mobile.ar.camera.prediction.selectPredictedStarDirections
import dev.pointtosky.mobile.ar.rememberRotationFrame
import dev.pointtosky.mobile.location.DeviceLocationRepository
import java.io.File

/**
 * SKY-1 sky session-log capture experiment (`internalDebug`-only).
 *
 * A standalone screen for the field workflow this data collection actually is: point the phone at the
 * night sky, hold still, record a few hundred frames, pull the directory off the device. It owns its
 * whole camera session for the same reason [PhysicalCameraBindingExperimentScreen] and
 * [FrameContentCorrespondenceScreen] do — an experiment that shares the AR screen's session cannot
 * control exposure, resolution or physical camera without changing what production does.
 *
 * ## What it records
 * See `SkySessionLog` in `:core:astro-core`. Per frame: the raw luma plane, the CAM-1c frame
 * metadata, the CAM-1d-paired device pose, the observing context (GPS + UTC + magnetic declination),
 * the `CaptureResult` exposure for that exact frame, and the CAM-2a predicted stars for that pose.
 * Once per session: camera ids, buffer size, intrinsics and calibration.
 *
 * ## Manual exposure
 * The capture defaults to a manual long exposure ([DEFAULT_SKY_EXPOSURE]), because an auto-exposed
 * frame of the night sky is not usable data — see [SkyCaptureExposure]'s file KDoc. When the selected
 * physical camera does not support `MANUAL_SENSOR`, the screen says so plainly rather than recording
 * an auto-exposed session that looks the same in the log.
 */
class SkySessionCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SkySessionCaptureScreen() }
    }
}

internal val SKY_SESSION_CAPTURE_ACTIVITY_CLASS_NAME: String = SkySessionCaptureActivity::class.java.name

/** The one function that ever constructs the launch [Intent] — mirrors the other experiments' testability rationale. */
internal fun buildSkySessionCaptureIntent(context: Context): Intent =
    Intent(context, SkySessionCaptureActivity::class.java)

/**
 * A 0.5 s exposure at ISO 1600: long enough to record stars well below naked-eye magnitude, short
 * enough that Earth's rotation trails a star by well under a pixel at these focal lengths, and short
 * enough that a hand-held or lightly-braced phone still yields usable frames. A starting point for
 * the field, not a tuned constant — the operator adjusts it against the actual sky.
 */
internal val DEFAULT_SKY_EXPOSURE = SkyManualExposureRequest(exposureTimeNanos = 500_000_000L, sensitivityIso = 1600)

private val SKY_RESOLUTION_CANDIDATES =
    listOf(
        AnalysisResolutionRequest(1280, 720, AnalysisResolutionFamily.NEAR_16_9),
        AnalysisResolutionRequest(1920, 1080, AnalysisResolutionFamily.NEAR_16_9),
        AnalysisResolutionRequest(640, 480, AnalysisResolutionFamily.NEAR_4_3),
    )

internal const val TAG_SKY_REQUEST_PERMISSION = "sky_request_permission"
internal const val TAG_SKY_START_RECORDING = "sky_start_recording"
internal const val TAG_SKY_STOP_RECORDING = "sky_stop_recording"
internal const val TAG_SKY_STATUS = "sky_status"

/** Live, bounded status for the capture HUD. Never a growing list — counters and latest values only. */
internal data class SkyCaptureUiState(
    val analyzedFrameCount: Long = 0L,
    val recordedFrameCount: Long = 0L,
    val droppedFrameCount: Long = 0L,
    val writtenLumaBytes: Long = 0L,
    val lastOutcome: SkyRecordOutcome? = null,
    val lastFailureReason: String? = null,
    val geometryStatus: String = "MISSING_FRAME",
    val predictedStarCount: Int = 0,
    val exposureAvailable: Boolean = false,
    val sessionDirectoryPath: String? = null,
)

@Composable
internal fun SkySessionCaptureScreen() {
    val context = LocalContext.current
    var hasCameraPermission by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission =
                granted
        }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF05050A)) {
        if (!hasCameraPermission) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Camera permission required", color = Color.White)
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.testTag(TAG_SKY_REQUEST_PERMISSION),
                ) {
                    Text("Grant camera permission")
                }
            }
            return@Surface
        }
        SkySessionCaptureContent()
    }
}

@Composable
private fun SkySessionCaptureContent() {
    val context = LocalContext.current

    val topology = remember { buildCameraTopologyReport(context, boundCameraInfo = null) }
    val physicalCameraIds =
        remember(topology) {
            topology.entries
                .flatMap { it.declaredPhysicalCameraIds }
                .distinct()
                .sorted()
        }

    var selectedPhysicalCameraId by remember { mutableStateOf(physicalCameraIds.firstOrNull()) }
    var resolution by remember { mutableStateOf(SKY_RESOLUTION_CANDIDATES.first()) }
    var manualExposure by remember { mutableStateOf<SkyManualExposureRequest?>(DEFAULT_SKY_EXPOSURE) }
    var exposureCapability by remember { mutableStateOf<SkyManualExposureCapability?>(null) }
    var bindFailure by remember { mutableStateOf<String?>(null) }
    var uiState by remember { mutableStateOf(SkyCaptureUiState()) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }

    // The catalog subset to predict, loaded once. Reuses the same bounded selection the CAM-2b overlay
    // uses rather than inventing a second star-selection policy for the log.
    var starDirections by remember { mutableStateOf<List<EquatorialStarDirection>>(emptyList()) }
    LaunchedEffect(Unit) {
        val catalog = runCatching { PtskCatalogLoader(context.assets).load() }.getOrNull()
        starDirections = selectPredictedStarDirections(catalog?.allStars().orEmpty().filter { it.isRenderablePoint() })
    }

    // The latest device fix. Null until location resolves; a frame captured before then records no
    // observer context rather than a guessed one.
    var latitudeDeg by remember { mutableStateOf<Double?>(null) }
    var longitudeDeg by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val repository = DeviceLocationRepository(context.applicationContext)
        repository.deviceLocationFlow.collect { point ->
            latitudeDeg = point?.latDeg
            longitudeDeg = point?.lonDeg
        }
    }

    val synchronizer = remember { CameraTimestampSynchronizer() }
    val geometryProvider =
        remember { CameraSessionGeometryProvider(maxAllowedPairDeltaNanos = synchronizer.maxAllowedDeltaNanos) }
    val intrinsicsResolver = remember { SessionScopedCameraIntrinsicsResolver() }
    val session = remember { SkySessionCaptureSession(synchronizer, geometryProvider, intrinsicsResolver) }

    // Feeds the rotation history the CAM-1d pairing reads. The matrix reaching the log is exactly the
    // display-remapped, magnetic-north-referenced one the math consumes - never pre-corrected for true
    // north, which projectStars applies itself from the recorded declination.
    rememberRotationFrame(onRotationSample = { sample -> synchronizer.onRotationSample(sample) })

    LaunchedEffect(viewportWidthPx, viewportHeightPx) {
        if (viewportWidthPx > 0 && viewportHeightPx > 0) {
            geometryProvider.onViewportChanged(viewportWidthPx, viewportHeightPx)
        }
    }

    DisposableEffect(session) {
        onDispose { session.dispose() }
    }

    Box(
        modifier =
            Modifier.fillMaxSize().onSizeChanged { size ->
                viewportWidthPx = size.width
                viewportHeightPx = size.height
            },
    ) {
        val cameraId = selectedPhysicalCameraId
        if (cameraId != null) {
            SkySessionCameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraSelector = explicitPhysicalCameraSelector(cameraId),
                analysisResolutionOverride = resolution,
                manualExposure = manualExposure,
                onCameraInfo = { info: CameraInfo ->
                    exposureCapability = probeSkyManualExposureCapability(info)
                    session.onCameraInfo(info, cameraId)
                },
                onExplicitBindFailure = { reason -> bindFailure = reason },
                onFrame = { frame ->
                    val observer = skyObserverContext(latitudeDeg, longitudeDeg)
                    uiState = session.onFrame(frame, observer, starDirections, uiState)
                },
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectionContainer {
                Text(
                    text =
                        skyCaptureStatusText(
                            uiState,
                            exposureCapability,
                            manualExposure,
                            bindFailure,
                            resolution,
                            selectedPhysicalCameraId,
                        ),
                    color = Color(0xFFB8E0FF),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TAG_SKY_STATUS),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.sessionDirectoryPath == null || !session.isRecording) {
                    Button(
                        onClick = { uiState = session.startRecording(context, resolution, uiState) },
                        modifier = Modifier.testTag(TAG_SKY_START_RECORDING),
                    ) {
                        Text("Record")
                    }
                }
                if (session.isRecording) {
                    Button(onClick = { session.stopRecording() }, modifier = Modifier.testTag(TAG_SKY_STOP_RECORDING)) {
                        Text("Stop")
                    }
                }
            }

            if (!session.isRecording) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SKY_RESOLUTION_CANDIDATES.forEach { candidate ->
                        Button(
                            onClick = { resolution = candidate },
                        ) { Text("${candidate.widthPx}x${candidate.heightPx}") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SKY_EXPOSURE_PRESETS.forEach { preset ->
                        Button(onClick = { manualExposure = exposureCapability?.clamp(preset) ?: preset }) {
                            Text(formatSkyExposure(preset))
                        }
                    }
                    Button(onClick = { manualExposure = null }) { Text("Auto") }
                }
                if (physicalCameraIds.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        physicalCameraIds.forEach { id ->
                            Button(onClick = { selectedPhysicalCameraId = id }) { Text("cam $id") }
                        }
                    }
                }
            }
        }
    }
}

/** Exposure presets spanning what a hand-held to lightly-braced night shot can realistically use. */
private val SKY_EXPOSURE_PRESETS =
    listOf(
        SkyManualExposureRequest(exposureTimeNanos = 125_000_000L, sensitivityIso = 3200),
        DEFAULT_SKY_EXPOSURE,
        SkyManualExposureRequest(exposureTimeNanos = 1_000_000_000L, sensitivityIso = 800),
        SkyManualExposureRequest(exposureTimeNanos = 2_000_000_000L, sensitivityIso = 400),
    )

private fun formatSkyExposure(request: SkyManualExposureRequest): String {
    val millis = request.exposureTimeNanos / 1_000_000L
    return "${millis}ms/${request.sensitivityIso}"
}

/**
 * The observing context for a frame captured now, or `null` when the device fix has not resolved.
 *
 * The magnetic declination is computed from the same [GeomagneticField] model the AR renderer uses,
 * at the frame's own instant and location — never carried over from another position, and never
 * defaulted to zero (a `null` declination makes the replay skip the frame explicitly instead of
 * projecting an uncorrected result that looks corrected).
 */
internal fun skyObserverContext(
    latitudeDeg: Double?,
    longitudeDeg: Double?,
    utcEpochMillis: Long = System.currentTimeMillis(),
): SkyObserverContext? {
    if (latitudeDeg == null || longitudeDeg == null) return null
    if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite()) return null
    val declination =
        runCatching {
            GeomagneticField(latitudeDeg.toFloat(), longitudeDeg.toFloat(), 0f, utcEpochMillis).declination.toDouble()
        }.getOrNull()?.takeIf { it.isFinite() }
    return SkyObserverContext(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        utcEpochMillis = utcEpochMillis,
        horizontalAccuracyM = null,
        magneticDeclinationDeg = declination,
    )
}

/**
 * Owns the per-session runtime wiring: pairing, geometry, intrinsics, and the recorder. Lives outside
 * the composable so a recomposition can never rebuild it mid-capture, and so the per-frame path is a
 * plain function call rather than a chain of Compose state writes on the analysis thread.
 */
internal class SkySessionCaptureSession(
    private val synchronizer: CameraTimestampSynchronizer,
    private val geometryProvider: CameraSessionGeometryProvider,
    private val intrinsicsResolver: SessionScopedCameraIntrinsicsResolver,
) {
    // Written on the main thread (onCameraInfo, startRecording/stopRecording from the UI) and read on
    // the analysis thread (onFrame), so each needs its own visibility guarantee. analyzedFrameCount is
    // deliberately not volatile: it is only ever touched from the single analysis executor thread.
    @Volatile private var recorder: SkySessionRecorder? = null

    @Volatile private var cameraInfo: CameraInfo? = null

    @Volatile private var cameraId: String? = null
    private var analyzedFrameCount = 0L

    val isRecording: Boolean get() = recorder?.isRecording == true

    fun onCameraInfo(
        info: CameraInfo,
        physicalCameraId: String,
    ) {
        cameraInfo = info
        cameraId = physicalCameraId
    }

    /**
     * Called once per analyzed frame, on the analysis thread. Resolves the session intrinsics from the
     * first real frame's dimensions (never a guessed size — see [SessionScopedCameraIntrinsicsResolver.resolveOnce]),
     * pairs the frame to a rotation sample, projects the star subset for the resulting geometry, and
     * hands the whole lot to the recorder.
     */
    fun onFrame(
        frame: SkyAnalyzedFrame,
        observer: SkyObserverContext?,
        stars: List<EquatorialStarDirection>,
        previous: SkyCaptureUiState,
    ): SkyCaptureUiState {
        analyzedFrameCount += 1

        val info = cameraInfo
        if (info != null) {
            val resolution =
                intrinsicsResolver.resolveOnce(
                    cameraInfo = info,
                    imageWidthPx = frame.metadata.bufferWidthPx,
                    imageHeightPx = frame.metadata.bufferHeightPx,
                    sensorToBufferTransform = frame.metadata.sensorToBufferTransform,
                )
            geometryProvider.onIntrinsicsResolved(resolution)
        }

        val pairing = synchronizer.onCameraFrame(frame.metadata)
        if (pairing != null) {
            geometryProvider.onPairedFrame(frame.metadata, pairing)
        }

        val geometryResult = geometryProvider.state.value
        val geometry = (geometryResult as? CameraSessionGeometryResult.Ready)?.geometry
        val prediction =
            if (geometry != null && observer != null && stars.isNotEmpty()) {
                observer.toStarProjectionContext()?.let { context -> projectStars(stars, context, geometry) }
            } else {
                null
            }

        val active = recorder
        val outcome =
            if (active != null && geometry != null) {
                active.record(
                    frame = frame,
                    geometry = geometry,
                    capturedAtEpochMillis = System.currentTimeMillis(),
                    observer = observer,
                    stars = if (prediction is StarPredictionBatchResult.Ready) stars else emptyList(),
                    prediction = prediction ?: StarPredictionBatchResult.Ready.of(emptyList()),
                )
            } else {
                previous.lastOutcome
            }

        return previous.copy(
            analyzedFrameCount = analyzedFrameCount,
            recordedFrameCount = active?.recordedFrameCount ?: 0L,
            droppedFrameCount = active?.droppedFrameCount ?: 0L,
            writtenLumaBytes = active?.writtenLumaBytes ?: 0L,
            lastOutcome = outcome,
            // A start failure ("intrinsics not resolved yet") must survive the next frame's status
            // update - it is the one message the operator needs to act on, and wiping it a frame later
            // would make Record look like it silently did nothing.
            lastFailureReason = active?.lastFailureReason ?: previous.lastFailureReason,
            geometryStatus = geometryResult::class.simpleName ?: "UNKNOWN",
            predictedStarCount = (prediction as? StarPredictionBatchResult.Ready)?.projections?.size ?: 0,
            exposureAvailable = frame.exposure?.exposureTimeNanos != null,
            sessionDirectoryPath = active?.sessionDirectoryPath,
        )
    }

    /**
     * Opens a new session directory under the app's own external files dir (falling back to internal
     * storage), writes the header, and starts recording.
     *
     * The header needs the session's intrinsics, which only exist once a frame has been analyzed — so
     * starting before the first frame is refused rather than writing a header with a fabricated
     * intrinsics value.
     */
    fun startRecording(
        context: Context,
        resolution: AnalysisResolutionRequest,
        previous: SkyCaptureUiState,
    ): SkyCaptureUiState {
        if (isRecording) return previous
        val intrinsics =
            intrinsicsResolver.lastPublishedResolution
                ?: return previous.copy(lastFailureReason = "intrinsics_not_resolved_yet")
        val geometry = (geometryProvider.state.value as? CameraSessionGeometryResult.Ready)?.geometry

        val sessionId = "sky_" + System.currentTimeMillis()
        val directory = File(skySessionsRoot(context), sessionId)
        val writer = SkySessionLogWriter(directory)
        val header =
            buildSkySessionHeader(
                sessionId = sessionId,
                startedAtEpochMillis = System.currentTimeMillis(),
                bufferWidthPx = geometry?.frame?.bufferWidthPx ?: resolution.widthPx,
                bufferHeightPx = geometry?.frame?.bufferHeightPx ?: resolution.heightPx,
                intrinsics = intrinsics,
                maxPairDeltaNanos = synchronizer.maxAllowedDeltaNanos,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                cameraId = cameraId,
                physicalCameraIds =
                    intrinsicsResolver.lastCalibrationDiagnostics
                        ?.physicalCameraIds
                        ?.toList()
                        ?.sorted()
                        .orEmpty(),
                calibration = intrinsicsResolver.lastCalibrationDiagnostics?.toSkyCalibrationRecord(),
                pinhole = geometry?.let { skyPinholeRecordOrNull(it) },
                notes = null,
            )
        val started = SkySessionRecorder(writer).takeIf { it.start(header) }
        recorder = started
        return previous.copy(
            sessionDirectoryPath = started?.sessionDirectoryPath,
            lastFailureReason = if (started == null) writer.lastFailureReason ?: "session_start_failed" else null,
        )
    }

    fun stopRecording() {
        recorder?.stop()
    }

    fun dispose() {
        stopRecording()
        recorder = null
        synchronizer.dispose()
        geometryProvider.dispose()
    }

    private fun skySessionsRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sky_sessions")
}

/** Maps this session's calibration diagnostics into the log's own plain-value record. */
internal fun CameraCalibrationDiagnostics.toSkyCalibrationRecord(): SkyCalibrationRecord =
    SkyCalibrationRecord(
        activeArrayWidthPx = activeArrayWidthPx,
        activeArrayHeightPx = activeArrayHeightPx,
        activeArrayLeftPx = activeArrayLeftPx,
        activeArrayTopPx = activeArrayTopPx,
        activeArrayRightPx = activeArrayRightPx,
        activeArrayBottomPx = activeArrayBottomPx,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = sensorHeightMm,
        focalLengthMm = focalLengthMm,
        activeFxPx = activeFxPx,
        activeFyPx = activeFyPx,
        activeCxPx = activeCxPx,
        activeCyPx = activeCyPx,
        bufferFxPx = bufferFxPx,
        bufferFyPx = bufferFyPx,
        bufferCxPx = bufferCxPx,
        bufferCyPx = bufferCyPx,
        quality = quality.name,
        sensorToBufferMappingSource = sensorToBufferMappingSource,
        transformClass = transformClass.name,
    )

/** The capture HUD text. Pure so it can be asserted on without a device. */
internal fun skyCaptureStatusText(
    state: SkyCaptureUiState,
    exposureCapability: SkyManualExposureCapability?,
    manualExposure: SkyManualExposureRequest?,
    bindFailure: String?,
    resolution: AnalysisResolutionRequest,
    physicalCameraId: String?,
): String =
    buildString {
        appendLine("SKY-1 session capture")
        appendLine("camera=${physicalCameraId ?: "-"} analysis=${resolution.widthPx}x${resolution.heightPx}")
        appendLine("geometry=${state.geometryStatus} stars=${state.predictedStarCount}")
        appendLine(
            "exposure=" +
                when {
                    manualExposure == null -> "AUTO (not suitable for sky capture)"
                    exposureCapability == null -> "requested ${formatSkyExposure(manualExposure)}, capability unknown"
                    !exposureCapability.supported -> "MANUAL UNSUPPORTED (${exposureCapability.unsupportedReason})"
                    else -> "manual ${formatSkyExposure(manualExposure)}"
                },
        )
        appendLine("captureResultExposure=${if (state.exposureAvailable) "present" else "absent"}")
        appendLine(
            "frames analyzed=${state.analyzedFrameCount} recorded=${state.recordedFrameCount} dropped=${state.droppedFrameCount}",
        )
        appendLine("luma=${state.writtenLumaBytes / 1024L} KiB last=${state.lastOutcome?.name ?: "-"}")
        state.sessionDirectoryPath?.let { appendLine("dir=$it") }
        state.lastFailureReason?.let { appendLine("failure=$it") }
        bindFailure?.let { appendLine("bindFailure=$it") }
    }.trimEnd()
