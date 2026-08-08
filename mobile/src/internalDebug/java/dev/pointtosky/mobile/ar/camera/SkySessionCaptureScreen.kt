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
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.skylog.SkyCalibrationRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.mobile.ar.camera.prediction.selectPredictedStarDirections
import dev.pointtosky.mobile.ar.rememberRotationFrame
import dev.pointtosky.mobile.location.DeviceLocationRepository
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.Locale

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
 * the `CaptureResult` exposure matched to that exact frame, and the CAM-2a predicted stars for that
 * pose. Once per session: camera ids, buffer size, intrinsics and calibration.
 *
 * ## Two-phase bind
 * The first bind of a camera carries **no** exposure request. Nothing is known yet about what the
 * device supports, and a `SENSOR_EXPOSURE_TIME` outside its advertised range makes it discard the
 * whole manual mode and silently auto-expose. So phase one binds plain, [probeSkyManualExposureCapability]
 * reads the ranges from the bound `CameraInfo`, the requested exposure is resolved against them, and
 * phase two rebinds with the resolved values. Recording is blocked until the resolved exposure is the
 * one actually bound — see [evaluateSkyRecordingGate].
 *
 * ## Two permissions, two states
 * Camera permission gates the whole screen: without it there is nothing to show. Location permission
 * does not — the preview still runs, the HUD still reports, and only *recording* is blocked, because a
 * session whose frames carry no observing context is a directory of pixels no detector can use (see
 * [SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE]). Neither request is fired from composition:
 * both are explicit operator actions, and the HUD names which one is missing.
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

/** Exposure presets spanning what a hand-held to lightly-braced night shot can realistically use. */
internal val SKY_EXPOSURE_PRESETS =
    listOf(
        SkyManualExposureRequest(exposureTimeNanos = 125_000_000L, sensitivityIso = 3200),
        DEFAULT_SKY_EXPOSURE,
        SkyManualExposureRequest(exposureTimeNanos = 1_000_000_000L, sensitivityIso = 800),
        SkyManualExposureRequest(exposureTimeNanos = 2_000_000_000L, sensitivityIso = 400),
    )

private val SKY_RESOLUTION_CANDIDATES =
    listOf(
        AnalysisResolutionRequest(1280, 720, AnalysisResolutionFamily.NEAR_16_9),
        AnalysisResolutionRequest(1920, 1080, AnalysisResolutionFamily.NEAR_16_9),
        AnalysisResolutionRequest(640, 480, AnalysisResolutionFamily.NEAR_4_3),
    )

internal const val TAG_SKY_REQUEST_PERMISSION = "sky_request_permission"
internal const val TAG_SKY_REQUEST_LOCATION_PERMISSION = "sky_request_location_permission"
internal const val TAG_SKY_START_RECORDING = "sky_start_recording"
internal const val TAG_SKY_STOP_RECORDING = "sky_stop_recording"
internal const val TAG_SKY_STATUS = "sky_status"

/**
 * The permissions [DeviceLocationRepository] treats as sufficient — it accepts *either*, so the check
 * must too. Mirroring the repository's own test rather than guessing at what "location" means is what
 * keeps the two from drifting into a state where the HUD says granted and the flow emits nothing.
 */
internal val SKY_ACCEPTED_LOCATION_PERMISSIONS: Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * What this screen actually asks for: coarse only, because coarse is the only location permission
 * `AndroidManifest.xml` declares.
 *
 * Requesting an undeclared permission is not a no-op — the system denies it immediately without ever
 * showing a dialog, so asking for `ACCESS_FINE_LOCATION` here would be reported back as a user
 * refusal that never happened. Adding fine location to the app's declared permissions is a
 * product-level decision with data-safety consequences (`docs/data-safety.md`), not a SKY-1 one; if it
 * is ever declared, adding it here is a one-line change and the accepted set above already covers it.
 *
 * Coarse is adequate for this dataset: a kilometre of position error moves an altitude/azimuth
 * prediction by well under an arcsecond, orders of magnitude below the pointing error the log exists
 * to measure.
 */
internal val SKY_LOCATION_PERMISSIONS: Array<String> = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

/** Whether [DeviceLocationRepository] would consider location permission granted right now. */
internal fun hasSkyLocationPermission(context: Context): Boolean =
    SKY_ACCEPTED_LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * What the screen knows about location permission. Separate from the camera permission state on
 * purpose: the two are independent grants with independent consequences, and collapsing them into one
 * "permissions ok" flag is how a screen ends up either blocking the camera because location was denied
 * or recording an observer-less session because camera was granted.
 */
internal enum class SkyLocationPermissionState {
    GRANTED,

    /** Not granted, and this screen has not asked. The operator has an action to take. */
    NOT_REQUESTED,

    /**
     * Asked, and still not granted. Distinct from [NOT_REQUESTED] so the HUD can say "denied" rather
     * than repeating an invitation the operator already declined — and so the reason recording is
     * blocked reads as a decision rather than as a pending step.
     */
    DENIED,
}

/**
 * The permission state to display, from whether it is granted and whether this screen has asked.
 *
 * Pure, because it is the part worth asserting on: the request itself needs an `Activity`, the answer
 * does not.
 */
internal fun skyLocationPermissionState(
    granted: Boolean,
    requested: Boolean,
): SkyLocationPermissionState =
    when {
        granted -> SkyLocationPermissionState.GRANTED
        requested -> SkyLocationPermissionState.DENIED
        else -> SkyLocationPermissionState.NOT_REQUESTED
    }

/**
 * The point a sky session should observe from: the manual override if there is one, otherwise the
 * device fix, otherwise **nothing**.
 *
 * The precedence is deliberately the same one `ArViewModel` and `SkyMapViewModel` already apply, so an
 * operator who has pinned a manual location in the app records against that same location here rather
 * than against a second, incompatible policy invented for this screen.
 *
 * What it does not copy is those screens' final branch. They fall back to a `DEFAULT_LOCATION` of
 * `(0, 0)` and mark the snapshot `resolved = false`, which is right for a renderer that must draw
 * *something* — a null island sky is visibly wrong and the UI says so. It is exactly wrong for a
 * detector dataset: `(0, 0)` is a valid-looking latitude/longitude that would be written into every
 * frame line, projected through, and indistinguishable offline from a real fix in the Gulf of Guinea.
 * So this returns `null`, and the recording gate refuses to start.
 */
internal fun resolveSkyObserverPoint(
    manual: GeoPoint?,
    device: GeoPoint?,
): GeoPoint? = manual ?: device

/** Live, bounded status for the capture HUD. Never a growing list — counters and latest values only. */
internal data class SkyCaptureUiState(
    val analyzedFrameCount: Long = 0L,
    val recordedFrameCount: Long = 0L,
    val droppedFrameCount: Long = 0L,
    val staleFrameCount: Long = 0L,
    val joinDropCount: Long = 0L,
    val lastJoinDropReason: SkyJoinDropReason? = null,
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
            hasCameraPermission = granted
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

    // What the operator wants, and what the currently bound session actually carries. They differ for
    // exactly one bind after every change - see the two-phase bind in this file's KDoc.
    var requestedExposure by remember { mutableStateOf<SkyManualExposureRequest?>(DEFAULT_SKY_EXPOSURE) }
    var capabilityByCameraId by remember { mutableStateOf<Map<String, SkyManualExposureCapability>>(emptyMap()) }
    var bindFailure by remember { mutableStateOf<String?>(null) }
    var uiState by remember { mutableStateOf(SkyCaptureUiState()) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    var viewportHeightPx by remember { mutableStateOf(0) }

    val exposureCapability = selectedPhysicalCameraId?.let { capabilityByCameraId[it] }
    val resolvedExposure =
        remember(exposureCapability, requestedExposure) {
            val request = requestedExposure ?: return@remember null
            (exposureCapability?.resolve(request) as? SkyExposureResolution.Resolved)?.exposure
        }

    val configuration =
        remember(selectedPhysicalCameraId, resolution, resolvedExposure) {
            selectedPhysicalCameraId?.let { id ->
                SkyCaptureConfiguration(physicalCameraId = id, resolution = resolution, exposure = resolvedExposure)
            }
        }

    // The catalog subset to predict, loaded once. Reuses the same bounded selection the CAM-2b overlay
    // uses rather than inventing a second star-selection policy for the log.
    var starDirections by remember { mutableStateOf<List<EquatorialStarDirection>>(emptyList()) }
    LaunchedEffect(Unit) {
        val catalog = runCatching { PtskCatalogLoader(context.assets).load() }.getOrNull()
        starDirections = selectPredictedStarDirections(catalog?.allStars().orEmpty().filter { it.isRenderablePoint() })
    }

    // ---------------------------------------------------------------------------------------------
    // Observer context: location permission, the resolved point, and the gate that depends on both.
    // ---------------------------------------------------------------------------------------------

    val locationRepository = remember { DeviceLocationRepository(context.applicationContext) }
    val locationPrefs = remember { LocationPrefs.fromContext(context) }

    var hasLocationPermission by remember { mutableStateOf(hasSkyLocationPermission(context)) }
    var locationPermissionRequested by remember { mutableStateOf(false) }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Re-read the permissions rather than trusting the result map: the repository's own
            // "granted" test is coarse-or-fine, and this must agree with it exactly or the HUD and the
            // location flow will disagree about whether there is any point waiting for a fix.
            hasLocationPermission = hasSkyLocationPermission(context)
            locationRepository.onPermissionChanged()
        }
    // Deliberately not launched from a LaunchedEffect: a system permission dialog thrown at an operator
    // the instant a screen composes is both hostile and unreliable (it can fire during a rebind, or
    // twice after a configuration change). The request is an explicit action below.

    // The point to observe from — manual override first, then the device fix. Null until one resolves;
    // a frame captured before then records no observer context rather than a guessed one, and the
    // recording gate refuses to start at all.
    var observerPoint by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(locationRepository, locationPrefs) {
        combine(
            locationRepository.deviceLocationFlow,
            locationPrefs.manualPointFlow,
        ) { device, manual -> resolveSkyObserverPoint(manual = manual, device = device) }
            .collect { point -> observerPoint = point }
    }

    /**
     * The observing context for a frame captured *now*. Recomputed at every call site rather than held
     * in state, because it carries the instant as well as the place.
     */
    fun observerNow(): SkyObserverContext? = skyObserverContext(observerPoint?.latDeg, observerPoint?.lonDeg)

    // Only the *presence* of a context matters to the gate, so this one is remembered against the point
    // it came from instead of being rebuilt on every recomposition.
    val gateObserver = remember(observerPoint) { observerNow() }

    val session =
        remember {
            SkySessionCaptureSession(
                sessionsRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "sky_sessions"),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }

    // Feeds the rotation history the CAM-1d pairing reads, routed through the session so it always
    // lands in the live generation's synchronizer and never in a disposed one. The matrix reaching the
    // log is exactly the display-remapped, magnetic-north-referenced one the math consumes - never
    // pre-corrected for true north, which projectStars applies itself from the recorded declination.
    rememberRotationFrame(onRotationSample = { sample -> session.onRotationSample(sample) })

    LaunchedEffect(viewportWidthPx, viewportHeightPx) {
        if (viewportWidthPx > 0 && viewportHeightPx > 0) {
            session.onViewportChanged(viewportWidthPx, viewportHeightPx)
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
        if (configuration != null) {
            SkySessionCameraPreview(
                modifier = Modifier.fillMaxSize(),
                configuration = configuration,
                onBind = { epoch, boundConfiguration, info ->
                    if (session.onBind(epoch, boundConfiguration, info)) {
                        capabilityByCameraId =
                            capabilityByCameraId +
                            (boundConfiguration.physicalCameraId to probeSkyManualExposureCapability(info))
                    }
                },
                onExplicitBindFailure = { reason -> bindFailure = reason },
                onFrame = { epoch, frameConfiguration, joined ->
                    uiState =
                        session.onFrame(
                            epoch = epoch,
                            configuration = frameConfiguration,
                            joined = joined,
                            observer = observerNow(),
                            stars = starDirections,
                            previous = uiState,
                        )
                },
                onJoinDrops = { epoch, drops -> uiState = session.onJoinDrops(epoch, drops, uiState) },
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
            val gate = session.recordingGate(requestedExposure, exposureCapability, gateObserver)
            SelectionContainer {
                Text(
                    text =
                        skyCaptureStatusText(
                            state = uiState,
                            capability = exposureCapability,
                            requested = requestedExposure,
                            applied = configuration?.exposure,
                            gate = gate,
                            bindFailure = bindFailure,
                            resolution = resolution,
                            physicalCameraId = selectedPhysicalCameraId,
                            locationPermission =
                                skyLocationPermissionState(hasLocationPermission, locationPermissionRequested),
                            observer = gateObserver,
                        ),
                    color = Color(0xFFB8E0FF),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(TAG_SKY_STATUS),
                )
            }

            SkyLocationPermissionAction(
                hasLocationPermission = hasLocationPermission,
                onRequestLocationPermission = {
                    locationPermissionRequested = true
                    locationPermissionLauncher.launch(SKY_LOCATION_PERMISSIONS)
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!session.isRecording) {
                    Button(
                        onClick = {
                            uiState =
                                session.startRecording(
                                    requested = requestedExposure,
                                    capability = exposureCapability,
                                    // Re-derived at the tap, not the composition that enabled the
                                    // button: the fix can go away in between.
                                    observer = observerNow(),
                                    previous = uiState,
                                )
                        },
                        enabled = gate is SkyRecordingGate.Allowed,
                        modifier = Modifier.testTag(TAG_SKY_START_RECORDING),
                    ) {
                        Text("Record")
                    }
                } else {
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
                        Button(onClick = { requestedExposure = preset }) { Text(formatSkyExposure(preset)) }
                    }
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

/**
 * The explicit location-permission action, rendered only while the permission is missing.
 *
 * Its own composable, and stateless, for the same reason the rest of this screen's logic is pulled out
 * of the camera path: a Compose test can drive it without a camera bind, a `FusedLocationProvider`, or
 * a real permission dialog. The button is absent — not merely disabled — once permission is granted,
 * so the HUD never invites an operator to re-grant something they already have.
 */
@Composable
internal fun SkyLocationPermissionAction(
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    if (hasLocationPermission) return
    Button(
        onClick = onRequestLocationPermission,
        modifier = Modifier.testTag(TAG_SKY_REQUEST_LOCATION_PERMISSION),
    ) {
        Text("Grant location permission")
    }
}

private fun Double.formatSkyDegrees(decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", this)

internal fun formatSkyExposure(request: SkyManualExposureRequest): String {
    val millis = request.exposureTimeNanos / 1_000_000L
    return "${millis}ms/${request.sensitivityIso}"
}

private fun formatSkyExposure(exposure: SkyResolvedExposure): String =
    "${exposure.exposureTimeNanos / 1_000_000L}ms/${exposure.sensitivityIso}" +
        " frameDur=${exposure.frameDurationNanos / 1_000_000L}ms"

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

/**
 * The capture HUD text. Pure so it can be asserted on without a device.
 *
 * States the *gate* rather than only the request: an operator needs to know whether this session can
 * be recorded at all, and if not, which specific check failed.
 */
internal fun skyCaptureStatusText(
    state: SkyCaptureUiState,
    capability: SkyManualExposureCapability?,
    requested: SkyManualExposureRequest?,
    applied: SkyResolvedExposure?,
    gate: SkyRecordingGate,
    bindFailure: String?,
    resolution: AnalysisResolutionRequest,
    physicalCameraId: String?,
    locationPermission: SkyLocationPermissionState,
    observer: SkyObserverContext?,
): String =
    buildString {
        appendLine("SKY-1 session capture")
        appendLine("camera=${physicalCameraId ?: "-"} analysis=${resolution.widthPx}x${resolution.heightPx}")
        appendLine("geometry=${state.geometryStatus} stars=${state.predictedStarCount}")
        appendLine("requested=${requested?.let { formatSkyExposure(it) } ?: "AUTO"}")
        appendLine("applied=${applied?.let { formatSkyExposure(it) } ?: "none"}")
        appendLine(
            "manualExposure=" +
                when {
                    capability == null -> "capability unknown (camera not bound yet)"
                    !capability.supported -> "UNSUPPORTED (${capability.unsupportedReason?.name})"
                    else ->
                        "supported exposure=${capability.exposureTimeRangeNanos} iso=${capability.sensitivityRange} " +
                            "maxFrameDur=${capability.maxFrameDurationNanos}"
                },
        )
        appendLine("locationPermission=${locationPermission.name}")
        val declinationDeg = observer?.magneticDeclinationDeg
        appendLine(
            "observer=" +
                when {
                    observer == null -> "UNAVAILABLE (no location fix)"
                    declinationDeg == null -> "UNAVAILABLE (no magnetic declination)"
                    // Locale.ROOT: this is a diagnostic readout an operator pastes into a report, so a
                    // decimal comma from the device locale would be actively confusing next to the
                    // dot-decimal numbers the log itself contains.
                    else ->
                        "lat=${observer.latitudeDeg.formatSkyDegrees(4)} " +
                            "lon=${observer.longitudeDeg.formatSkyDegrees(4)} " +
                            "decl=${declinationDeg.formatSkyDegrees(2)}"
                },
        )
        appendLine(
            "recording=" +
                when (gate) {
                    is SkyRecordingGate.Allowed -> "ALLOWED"
                    is SkyRecordingGate.Blocked -> "BLOCKED(${gate.reason.name})"
                },
        )
        if (gate is SkyRecordingGate.Blocked &&
            gate.reason == SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE
        ) {
            appendLine(
                when (locationPermission) {
                    SkyLocationPermissionState.GRANTED ->
                        "  -> location permission is granted; waiting for a fix"

                    SkyLocationPermissionState.NOT_REQUESTED ->
                        "  -> grant location permission below to enable Record"

                    SkyLocationPermissionState.DENIED ->
                        "  -> location permission was DENIED; Record stays blocked until it is granted"
                },
            )
        }
        appendLine("captureResultExposure=${if (state.exposureAvailable) "present" else "absent"}")
        appendLine(
            "frames analyzed=${state.analyzedFrameCount} recorded=${state.recordedFrameCount} " +
                "dropped=${state.droppedFrameCount} stale=${state.staleFrameCount}",
        )
        appendLine("joinDrops=${state.joinDropCount} last=${state.lastJoinDropReason?.name ?: "-"}")
        appendLine("luma=${state.writtenLumaBytes / 1024L} KiB lastFrame=${state.lastOutcome?.name ?: "-"}")
        state.sessionDirectoryPath?.let { appendLine("dir=$it") }
        state.lastFailureReason?.let { appendLine("failure=$it") }
        bindFailure?.let { appendLine("bindFailure=$it") }
    }.trimEnd()
