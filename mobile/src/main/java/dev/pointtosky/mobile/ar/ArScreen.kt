package dev.pointtosky.mobile.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.catalog.isRenderablePoint
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.angularSeparationDeg
import dev.pointtosky.core.astro.projection.ViewportSize
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.horizontalToVector
import dev.pointtosky.core.astro.projection.multiply
import dev.pointtosky.core.astro.projection.projectDeviceVector
import dev.pointtosky.core.astro.projection.projectionParams
import dev.pointtosky.core.astro.projection.transpose
import dev.pointtosky.core.astro.projection.vectorToHorizontal
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.ar.camera.Camera2CameraIntrinsicsProvider
import dev.pointtosky.mobile.ar.camera.CameraCalibrationDiagnostics
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticsGate
import dev.pointtosky.mobile.ar.camera.CameraSessionGeometryProvider
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsCoordinator
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.CameraTimestampSynchronizer
import dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver
import dev.pointtosky.mobile.ar.camera.nextDebugSessionId
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.reducePredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.selectPredictedStarDirections
import dev.pointtosky.mobile.ar.camera.toDiagnosticSnapshot
import dev.pointtosky.mobile.datalayer.AimTargetOption
import dev.pointtosky.mobile.location.DeviceLocationRepository
import dev.pointtosky.mobile.render.BvColor
import dev.pointtosky.mobile.visibility.BortleSource
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun ArRoute(
    identifySolver: IdentifySolver,
    locationPrefs: LocationPrefs,
    onBack: () -> Unit,
    onSendAimTarget: (AimTargetOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceLocationRepository = remember { DeviceLocationRepository(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, deviceLocationRepository) {
        deviceLocationRepository.onPermissionChanged()
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    deviceLocationRepository.onPermissionChanged()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val viewModel: ArViewModel =
        viewModel(
            factory =
                ArViewModelFactory(
                    identifySolver = identifySolver,
                    assetManager = context.assets,
                    locationPrefs = locationPrefs,
                    deviceLocationRepository = deviceLocationRepository,
                ),
        )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ArScreen(
        state = state,
        onConstellationModeChange = viewModel::setConstellationMode,
        onProModeChange = viewModel::setProMode,
        onAsterismContext = viewModel::updateAsterismContext,
        resolveConstellation = viewModel::resolveConstellationId,
        onMagLimitChange = viewModel::setMagLimit,
        onShowStarLabelsToggle = viewModel::setShowStarLabels,
        onShowStarPointsToggle = viewModel::setShowStarPoints,
        onReticleTargetOnlyToggle = viewModel::setReticleTargetOnly,
        onVisibilityFilterToggle = viewModel::setVisibilityFilterEnabled,
        onBortleChange = viewModel::setBortle,
        onBortleSourceChange = viewModel::setBortleSource,
        onBack = onBack,
        onSetTarget = { target ->
            val option =
                AimTargetOption(
                    id = "ar-target",
                    label = target.label,
                    buildMessage = { cid ->
                        AimSetTargetMessage(
                            cid = cid,
                            kind = AimTargetKind.EQUATORIAL,
                            payload =
                                JsonCodec.encodeToElement(
                                    AimTargetEquatorialPayload(
                                        raDeg = target.raDeg,
                                        decDeg = target.decDeg,
                                    ),
                                ),
                        )
                    },
                )
            onSendAimTarget(option)
        },
        modifier = modifier,
    )
}

data class ArTarget(
    val raDeg: Double,
    val decDeg: Double,
    val label: String,
)

/**
 * [androidx.compose.ui.platform.testTag] for [LegacySkyOverlay]'s own wrapper [Box] - present exactly
 * when the legacy sky overlay is composed at all, for Compose UI tests (HUD-visibility follow-up §4).
 * One node for the whole group, never one per star/label - see [LegacySkyOverlay]'s own KDoc.
 */
const val LEGACY_SKY_OVERLAY_TEST_TAG = "legacy_sky_overlay"

/**
 * [androidx.compose.ui.platform.testTag] for the central aiming [Reticle] - present unconditionally,
 * independent of [PredictedStarDebugControlsState.showLegacyOverlay] (HUD-visibility follow-up §4).
 */
const val AR_RETICLE_TEST_TAG = "ar_reticle"

/**
 * CAM-2b (HUD-visibility follow-up §1): the single conditional boundary for every legacy sky-render
 * visual that can be confused with CAM-2b's predicted markers - star points, constellation/asterism/art
 * lines, the legacy nearest-star target highlight, and every on-sky star/asterism label. [content] is
 * composed inside one [Box] carrying [LEGACY_SKY_OVERLAY_TEST_TAG], so a future new legacy visual added
 * to a caller's [content] is automatically covered by the same `if (showLegacyOverlay)` gate the caller
 * applies around this whole function - never an independent, easy-to-forget per-layer check (the bug
 * this follow-up fixes: only two of six legacy visual kinds were previously gated).
 *
 * Deliberately generic - no `OverlayData` or other `ArScreen`-internal type appears in this function's
 * own signature - so the gating mechanism itself is directly Compose-UI-testable from `androidTest`
 * (which cannot see `internal` declarations in this module), independent of the real legacy content
 * `ArScreen` composes inside it.
 *
 * Explicitly outside this boundary (see `ArScreen`'s call site and [InfoPanel]'s own KDoc): the camera
 * preview, the CAM-1g/CAM-2b HUD and controls, the CAM-2b predicted-marker canvas
 * ([PredictedStarMarkersCanvas]), system nav/back/settings controls, the central aiming [Reticle], and
 * the bottom [InfoPanel]'s alt/az/ra-dec content. [InfoPanel]'s "nearest object" line *and* its
 * legacy-target watch-action button are both legacy-object-identifying content and are gated the same
 * way, separately, via [infoPanelVisibility] - see that function's own KDoc for why a button whose
 * rendered label still names a hidden legacy object (and whose click still sends that name as the
 * outgoing target's label) is not full isolation (the legacy-target-identity-leak fix).
 */
@VisibleForTesting
@Composable
fun LegacySkyOverlay(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().testTag(LEGACY_SKY_OVERLAY_TEST_TAG),
        content = content,
    )
}

/**
 * [androidx.compose.ui.platform.testTag] for [InfoPanel]'s Altitude/Azimuth [Text] - present
 * unconditionally, regardless of [PredictedStarDebugControlsState.showLegacyOverlay]: it is the
 * reticle's own aim readout, not a legacy sky-position visual.
 */
const val AR_INFO_PANEL_ALT_AZ_TEST_TAG = "ar_info_panel_alt_az"

/** [androidx.compose.ui.platform.testTag] for [InfoPanel]'s RA/Dec [Text] - present unconditionally,
 * for the same reason as [AR_INFO_PANEL_ALT_AZ_TEST_TAG]. */
const val AR_INFO_PANEL_RA_DEC_TEST_TAG = "ar_info_panel_ra_dec"

/**
 * [androidx.compose.ui.platform.testTag] for [InfoPanel]'s nearest-legacy-object [Text] - present only
 * while [InfoPanelVisibility.showNearestObject] is true (see [infoPanelVisibility]).
 */
const val AR_INFO_PANEL_NEAREST_OBJECT_TEST_TAG = "ar_info_panel_nearest_object"

/**
 * [androidx.compose.ui.platform.testTag] for [InfoPanel]'s watch-target action [Button] - present only
 * while [InfoPanelVisibility.showTargetAction] is true (see [infoPanelVisibility]). Legacy-target-
 * identity-leak fix: this button's rendered label names a specific legacy-catalog object (`targetLabel`),
 * and clicking it sends that same label - as `ArTarget.label`, alongside the reticle's own aim
 * coordinates, never the object's actual position - via `onSetTarget`. Both the button and the
 * nearest-object text above it must disappear together, not just the text.
 */
const val AR_INFO_PANEL_TARGET_ACTION_TEST_TAG = "ar_info_panel_target_action"

/**
 * CAM-2b (legacy-target-identity-leak fix): the pure presentation policy for [InfoPanel]'s two
 * legacy-object-identifying pieces of content - the nearest-object text line and the watch-target
 * action button (`targetLabel` + `onSetTarget`). Both are driven by the exact same [showLegacyOverlay]
 * flag [LegacySkyOverlay] itself is gated by, expressed as one small pure function rather than two
 * independent `if` checks scattered at the call site - the bug this fix closes: the nearest-object text
 * was gated, but the watch-target button (whose rendered label also names the hidden legacy object, e.g.
 * "Set 32 Persei as watch target", and whose click still sends that name as the outgoing target's label)
 * was not. The button's *coordinates* (`ArTarget.raDeg`/`decDeg`) are always the reticle's own aim
 * direction regardless of `showLegacyOverlay` - it is specifically the *label* that is legacy-derived
 * content, which is why both presentation flags below are driven by the same input rather than treating
 * the button as legacy-independent.
 *
 * `@VisibleForTesting internal` (not `private`): exercised directly by a plain JVM test
 * (`InfoPanelVisibilityTest`) without needing a Compose host or the internal `OverlayData` type. Not
 * called from `androidTest` (`InfoPanelPresentationTest` uses this function's documented output as
 * literals instead) - see that test class's own KDoc for why.
 */
internal data class InfoPanelVisibility(
    val showNearestObject: Boolean,
    val showTargetAction: Boolean,
)

@VisibleForTesting
internal fun infoPanelVisibility(showLegacyOverlay: Boolean): InfoPanelVisibility =
    InfoPanelVisibility(showNearestObject = showLegacyOverlay, showTargetAction = showLegacyOverlay)

@Composable
fun ArScreen(
    state: ArUiState,
    onConstellationModeChange: (ConstellationMode) -> Unit,
    onProModeChange: (Boolean) -> Unit,
    onAsterismContext: (List<AsterismSummary>, AsterismId?) -> Unit,
    resolveConstellation: (Equatorial) -> ConstellationId?,
    onMagLimitChange: (Double) -> Unit,
    onShowStarLabelsToggle: (Boolean) -> Unit,
    onShowStarPointsToggle: (Boolean) -> Unit,
    onReticleTargetOnlyToggle: (Boolean) -> Unit,
    onVisibilityFilterToggle: (Boolean) -> Unit,
    onBortleChange: (Bortle) -> Unit,
    onBortleSourceChange: (BortleSource) -> Unit,
    onBack: () -> Unit,
    onSetTarget: (ArTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permission = Manifest.permission.CAMERA
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(permission)
        }
    }

    // CAM-1d: instrumentation only - pairs camera-frame and rotation-sensor timestamps and publishes
    // a diagnostic result/debug state. Does not feed rendering or matching (see
    // docs/camera_coordinate_calibration_contract.md §4). This instance is owned by this one
    // composition - dispose() is terminal, not reusable, so a new ArScreen composition gets a new
    // remember{}ed synchronizer rather than calling dispose() and continuing to use this one.
    val timestampSynchronizer = remember { CameraTimestampSynchronizer() }
    // CAM-1f: session-scoped geometry bundle owner and once-per-session intrinsics resolver. Fed
    // below, but not consumed by rendering or any matcher yet (see
    // docs/camera_coordinate_calibration_contract.md §11). Same one-composition ownership and
    // terminal-dispose convention as timestampSynchronizer above. The provider's pairing tolerance
    // is read from timestampSynchronizer itself, never a separately-assumed default, so the two
    // never disagree about what "within tolerance" means for this session.
    val geometryProvider =
        remember {
            CameraSessionGeometryProvider(maxAllowedPairDeltaNanos = timestampSynchronizer.maxAllowedDeltaNanos)
        }
    val intrinsicsResolver =
        remember {
            SessionScopedCameraIntrinsicsResolver(Camera2CameraIntrinsicsProvider(context.applicationContext))
        }
    // CAM-1f: resolves intrinsics only once BOTH the bound CameraInfo and the first analyzed
    // frame's real buffer dimensions are known - resolving from CameraInfo alone would cache a
    // wrong (default-aspect) legacy-fallback horizontal FOV, since the fallback path derives it
    // from the analyzed image's aspect ratio and resolution only ever happens once per session.
    // See docs/camera_coordinate_calibration_contract.md §10.7.
    val intrinsicsCoordinator =
        remember {
            CameraSessionIntrinsicsCoordinator(
                resolver = intrinsicsResolver,
                onResolved = geometryProvider::onIntrinsicsResolved,
            )
        }
    DisposableEffect(Unit) {
        onDispose {
            // Order: stop pairing first, then stop resolving intrinsics, then dispose the bundle
            // owner that consumes both - so neither upstream input can race a still-live provider
            // into publishing from a session that is already ending.
            timestampSynchronizer.dispose()
            intrinsicsCoordinator.dispose()
            geometryProvider.dispose()
        }
    }

    val rotationFrame = rememberRotationFrame(onRotationSample = timestampSynchronizer::onRotationSample)
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    // CAM-1f: overlaySize is the authoritative viewport for CropScaleTransform - it is where the
    // future matcher's predictions will be displayed, not PreviewView's own (unmeasured) size.
    LaunchedEffect(overlaySize) {
        geometryProvider.onViewportChanged(overlaySize.width, overlaySize.height)
    }

    // CAM-1g: debug-only consumer of geometryProvider.observation, gated so it never affects a
    // production build (see docs/camera_coordinate_calibration_contract.md §11). Collected with
    // collectAsStateWithLifecycle so collection stops automatically when this composition leaves -
    // the same pattern ArRoute already uses for the AR view-model state - never a manually launched
    // permanent coroutine. `observation` (not the plain `state` StateFlow, and not a direct
    // `debugState()` sample) is collected because it publishes the result together with the counters
    // as of that same recompute and therefore reliably emits even when consecutive recomputes
    // produce a structurally-equal result (e.g. repeated IntrinsicsUnavailable/RotationUnavailable) -
    // `state` alone would never re-emit there, leaving Compose un-invalidated and the counters stale.
    // This is purely a read of the existing provider's observation; it starts no new camera, sensor,
    // resolver, or provider, and never feeds calculateOverlay/projectionParams.
    val cameraGeometryDiagnosticSnapshot: CameraGeometryDiagnosticSnapshot?
    val cameraGeometryDiagnosticSessionId: Long
    val cameraGeometryStatusTransitionCount: Int
    val cameraGeometryObservedFrameCount: Long
    val cameraGeometryReadyBundleCount: Long
    // CAM-2b: the exact same CameraSessionGeometryResult the CAM-1g diagnostics above observed for
    // this recompute - reused as-is by the predicted-star overlay below rather than reading the
    // provider's observation a second time (docs/camera_star_prediction_contract.md §14 "no second
    // camera/session provider, no re-pairing timestamps").
    val cameraGeometrySessionResult: CameraSessionGeometryResult?
    // CAM-2c §9: the calibrated-mapping diagnostics intrinsicsResolver captured (at most once per
    // session) the moment its calibrated AnalysisBuffer resolution succeeded - null before that, or
    // whenever this session resolved a PhysicalSensor/legacy-fallback result instead. Read alongside
    // the observation above so the panel updates as soon as a later recompute reflects a since-
    // completed resolution; debug-only, never consumed by projection.
    val cameraCalibrationDiagnostics: CameraCalibrationDiagnostics?
    // CAM-2c runtime integration fix §2/§5: the coordinator's full CAM-2c picture - its own typed
    // attempt (Resolved or an explicit failure variant), the resolution it actually published (which
    // may legitimately be a CAM-1b PhysicalSensor fallback), the coordinator's own lifecycle state,
    // the raw camera characteristics snapshot, and the running per-frame transform-transport
    // counters - read alongside the observation above for the same reason cameraCalibrationDiagnostics
    // is: geometryObservation's emission (driven by onPairedFrame/onIntrinsicsResolved, both of which
    // this coordinator's own resolution always precedes or accompanies) is what actually drives
    // recomposition here, not a dedicated Flow of its own. Debug-only; never consumed by projection.
    val cameraIntrinsicsDiagnosticState: CameraSessionIntrinsicsDiagnosticState?
    if (CameraGeometryDiagnosticsGate.isEnabled) {
        val geometryObservation by geometryProvider.observation.collectAsStateWithLifecycle()
        val sessionId = remember { nextDebugSessionId() }
        val snapshot = remember(geometryObservation.result) { geometryObservation.result.toDiagnosticSnapshot() }
        val transitionCount = remember { mutableIntStateOf(0) }
        val lastCategory = remember { mutableStateOf<CameraGeometryDiagnosticCategory?>(null) }
        SideEffect {
            // Counter-only observation emissions (same category, advanced counters) must not bump
            // this - it tracks category transitions only, never raw emission count.
            val previous = lastCategory.value
            if (previous != null && previous != snapshot.category) {
                transitionCount.intValue++
            }
            lastCategory.value = snapshot.category
        }
        val debugState = geometryObservation.debugState

        cameraGeometryDiagnosticSnapshot = snapshot
        cameraGeometryDiagnosticSessionId = sessionId
        cameraGeometryStatusTransitionCount = transitionCount.intValue
        cameraGeometryObservedFrameCount = debugState.observedFrameCount
        cameraGeometryReadyBundleCount = debugState.readyBundleCount
        cameraGeometrySessionResult = geometryObservation.result
        cameraCalibrationDiagnostics = intrinsicsResolver.lastCalibrationDiagnostics
        cameraIntrinsicsDiagnosticState = intrinsicsCoordinator.diagnosticState
    } else {
        cameraGeometryDiagnosticSnapshot = null
        cameraGeometryDiagnosticSessionId = 0L
        cameraGeometryStatusTransitionCount = 0
        cameraGeometryObservedFrameCount = 0L
        cameraGeometryReadyBundleCount = 0L
        cameraGeometrySessionResult = null
        cameraCalibrationDiagnostics = null
        cameraIntrinsicsDiagnosticState = null
    }

    // Single declination computation point, hoisted above the Box so both the legacy renderer (below,
    // once Ready) and CAM-2b's derived overlay state (also below) share the exact same
    // GeomagneticField sample - never a second model instance. Only meaningful once the observer's
    // location is known (ArUiState.Ready); null otherwise. Hardening task §1: previously this lived
    // inside the Ready composition branch only, which was fine for the legacy renderer alone, but a
    // second, independent consumer (CAM-2b) needs the identical value, not a recomputation.
    val declinationDeg: Double? =
        if (state is ArUiState.Ready) {
            remember(state.location) {
                android.hardware.GeomagneticField(
                    state.location.latDeg.toFloat(),
                    state.location.lonDeg.toFloat(),
                    0f,
                    System.currentTimeMillis(),
                ).declination.toDouble()
            }
        } else {
            null
        }

    // CAM-2b (hardening task §1, keyed to a debug session by hardening task §3): session-scoped debug
    // toggles/mode and the HUD's own expand/collapse state, hoisted here - before the root Box content -
    // so a single, stable `predictedStarOverlayState` below can be read identically by both the marker
    // canvas (inside the Ready branch) and the shared HUD (after it), rather than one composition branch
    // writing a mutable local (`var predictedStarPanelState: PredictedStarOverlayState? = null`, the
    // removed anti-pattern) for another branch to read later. Keyed to
    // `cameraGeometryDiagnosticSessionId` (not unkeyed) so a fresh debug session resets every field to
    // its documented default rather than carrying over the previous session's fallback-mode/hidden-
    // overlay/expanded-HUD choices - see `PredictedStarDebugControlsState`'s own KDoc. Harmless
    // regardless of CameraGeometryDiagnosticsGate.isEnabled: no debug UI ever surfaces to change these in
    // a release/public build, and the session id stays a constant `0L` there, so behavior is exactly as
    // if this call were absent.
    val predictedStarDebugControls = rememberPredictedStarDebugControls(cameraGeometryDiagnosticSessionId)

    // CAM-2b: the single derived overlay-state value both consumers below read directly - never
    // recomputed per consumer, never smuggled across composition branches via a mutable local. Reuses
    // cameraGeometrySessionResult (the same raw geometry CAM-1g above observed for this recompute) and
    // declinationDeg (the same GeomagneticField sample the legacy renderer uses below) - never
    // re-paired, never a second model instance. null whenever the gate is disabled or the AR state
    // isn't Ready yet, so a non-Ready state (or a disposed/new session reflected in
    // cameraGeometrySessionResult) never leaves stale points on screen - both consumers simply see
    // `null`/`Waiting` and render nothing/the waiting text. See
    // docs/camera_star_prediction_contract.md §14.
    val predictedStarOverlayState: PredictedStarOverlayState? =
        if (CameraGeometryDiagnosticsGate.isEnabled && state is ArUiState.Ready && declinationDeg != null) {
            val predictedStars =
                remember(state.catalog, state.effectiveMagLimit) {
                    selectPredictedStarDirections(visibilitySelectedStars(state))
                }
            remember(
                cameraGeometrySessionResult,
                state.locationResolved,
                state.location,
                state.instant,
                declinationDeg,
                predictedStars,
                predictedStarDebugControls.predictedStarIntrinsicsMode,
            ) {
                reducePredictedStarOverlayState(
                    gateEnabled = true,
                    geometryResult = requireNotNull(cameraGeometrySessionResult) {
                        "cameraGeometrySessionResult must be populated whenever the gate is enabled"
                    },
                    observerLatitudeDeg = if (state.locationResolved) state.location.latDeg else null,
                    observerLongitudeDeg = if (state.locationResolved) state.location.lonDeg else null,
                    utcEpochMillis = state.instant.toEpochMilli(),
                    magneticDeclinationDeg = declinationDeg,
                    stars = predictedStars,
                    intrinsicsMode = predictedStarDebugControls.predictedStarIntrinsicsMode,
                )
            }
        } else {
            null
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { overlaySize = it },
    ) {
        if (hasPermission) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onFrameMetadata = { frame ->
                    intrinsicsCoordinator.onFrameMetadata(frame)

                    timestampSynchronizer.onCameraFrame(frame)?.let { pairingResult ->
                        geometryProvider.onPairedFrame(frame, pairingResult)
                    }
                },
                onCameraInfo = intrinsicsCoordinator::onCameraInfo,
            )
        } else {
            PermissionRequest(onRequest = { launcher.launch(permission) })
        }

        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(color = Color(0x66000000), shape = CircleShape),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }

        when (state) {
            ArUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            is ArUiState.Ready -> {
                // declinationDeg is hoisted above the Box (shared with CAM-2b's derived overlay state)
                // and is guaranteed non-null here: this branch only runs when `state is
                // ArUiState.Ready`, the exact condition under which it was computed.
                val resolvedDeclinationDeg =
                    requireNotNull(declinationDeg) { "declinationDeg must be computed whenever state is Ready" }
                val overlay =
                    remember(state, rotationFrame, overlaySize, resolvedDeclinationDeg) {
                        if (rotationFrame != null && overlaySize != IntSize.Zero) {
                            calculateOverlay(
                                state = state,
                                frame = rotationFrame,
                                viewport = overlaySize,
                                resolveConstellation = resolveConstellation,
                                declinationDeg = resolvedDeclinationDeg,
                            )
                        } else {
                            null
                        }
                    }

                overlay?.let {
                    LaunchedEffect(it.asterismState) {
                        onAsterismContext(it.asterismState.available, it.asterismState.highlighted)
                    }
                }

                // Counter-rotate overlay labels so they stay upright relative to the real horizon
                // while the projected constellation rotates with device roll (portrait-locked UI).
                // Hoisted above the legacy overlay group below (it needs this value); computing it
                // here vs. its old position after Reticle() changes nothing about its value.
                val labelRoll = rotationFrame?.let { deviceRollDegrees(it.rotationMatrix) } ?: 0f

                // CAM-2b (HUD-visibility follow-up §1): every legacy sky-render visual that can be
                // confused with CAM-2b's predicted markers, gated behind exactly one flag -
                // `predictedStarDebugControls.showLegacyOverlay` - rather than several independently
                // -checked layers (the previous bug: only StarPointLayer/ConstellationLayer were
                // gated, so star labels, asterism labels, and the legacy nearest-star target highlight
                // stayed on screen even with the toggle off). showLegacyOverlay lives in
                // predictedStarDebugControls, hoisted above the Box and reset per debug session
                // (hardening task §1/§3). See LegacySkyOverlay's own KDoc for exactly what is/isn't
                // covered.
                if (predictedStarDebugControls.showLegacyOverlay) {
                    LegacySkyOverlay(modifier = Modifier.fillMaxSize()) {
                        if (state.showStarPoints && !state.reticleTargetOnly) {
                            overlay?.let {
                                StarPointLayer(
                                    overlay = it,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        overlay?.let {
                            ConstellationLayer(
                                overlay = it,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        if (state.reticleTargetOnly) {
                            overlay?.let {
                                ReticleTargetHighlight(
                                    overlay = it,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        val labelsToShow = when {
                            state.reticleTargetOnly -> overlay?.nearestLabel?.let { listOf(it) }.orEmpty()
                            state.showStarLabels -> overlay?.labels.orEmpty()
                            else -> emptyList()
                        }
                        labelsToShow.forEach { label ->
                            ArObjectLabel(
                                data = label,
                                modifier = Modifier.align(Alignment.TopStart),
                                rollDegrees = labelRoll,
                            )
                        }

                        if (!state.reticleTargetOnly) {
                            overlay?.asterismLabels?.forEach { label ->
                                AsterismLabel(
                                    data = label,
                                    modifier = Modifier.align(Alignment.TopStart),
                                    rollDegrees = labelRoll,
                                )
                            }
                        }
                    }
                }

                // CAM-2b: internal-debug-only predicted-star overlay - visualizes the accepted CAM-2a
                // projectStars(...) pipeline purely for diagnosis. Reads the single hoisted
                // predictedStarOverlayState computed above the Box (never recomputed here). Drawn on
                // top of the legacy overlay group above (when shown) so both can be visually compared;
                // never feeds calculateOverlay, projectionParams, or any production star position. See
                // docs/camera_star_prediction_contract.md §14. Never gated by showLegacyOverlay - this
                // is the CAM-2b content the toggle exists to isolate, not legacy content itself.
                if (predictedStarDebugControls.showPredictedStarMarkers && predictedStarOverlayState is PredictedStarOverlayState.Ready) {
                    PredictedStarMarkersCanvas(
                        points = predictedStarOverlayState.points,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // The central aiming reticle is an independent aiming reference, never part of the
                // legacy sky overlay (see LegacySkyOverlay's own KDoc) - always visible regardless of
                // showLegacyOverlay, unlike ReticleTargetHighlight (the legacy yellow nearest-star ring
                // above, which *is* legacy sky-render content and is gated accordingly).
                Reticle(modifier = Modifier.align(Alignment.Center).testTag(AR_RETICLE_TEST_TAG))

                var settingsVisible by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { settingsVisible = !settingsVisible },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(12.dp)
                            .background(Color(0x66000000), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.ar_settings_content_desc),
                        tint = Color.White,
                    )
                }

                if (settingsVisible) {
                    ArControlsPanel(
                        constellationMode = state.constellationMode,
                        proMode = state.proMode,
                        magLimit = state.magLimit,
                        showStarLabels = state.showStarLabels,
                        showStarPoints = state.showStarPoints,
                        reticleTargetOnly = state.reticleTargetOnly,
                        visibilityFilterEnabled = state.visibilityFilterEnabled,
                        bortle = state.bortle,
                        bortleSource = state.bortleSource,
                        autoBortle = state.autoBortle,
                        lightPollutionAvailable = state.lightPollutionAvailable,
                        limitingMag = state.limitingMag,
                        onConstellationModeChange = onConstellationModeChange,
                        onProModeChange = onProModeChange,
                        onMagLimitChange = onMagLimitChange,
                        onShowStarLabelsToggle = onShowStarLabelsToggle,
                        onShowStarPointsToggle = onShowStarPointsToggle,
                        onReticleTargetOnlyToggle = onReticleTargetOnlyToggle,
                        onVisibilityFilterToggle = onVisibilityFilterToggle,
                        onBortleChange = onBortleChange,
                        onBortleSourceChange = onBortleSourceChange,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 52.dp, end = 16.dp),
                    )
                }

                val targetLabel =
                    overlay?.nearestLabel?.title
                        ?: stringResource(id = R.string.ar_target_fallback_label)

                val target =
                    overlay?.let {
                        ArTarget(
                            raDeg = it.reticleEquatorial.raDeg,
                            decDeg = it.reticleEquatorial.decDeg,
                            label = targetLabel,
                        )
                    }

                if (overlay != null) {
                    // The bottom info panel itself is retained as non-overlay UI regardless of
                    // showLegacyOverlay (it's the reticle's own aim readout, not a sky-position visual)
                    // - but its nearest-object text AND its watch-target button both name a specific
                    // legacy-catalog star, so both are gated together via infoPanelVisibility rather
                    // than leaking that identity through a panel that is otherwise exempt from the
                    // toggle (legacy-target-identity-leak fix).
                    val visibility = infoPanelVisibility(showLegacyOverlay = predictedStarDebugControls.showLegacyOverlay)
                    InfoPanel(
                        reticleHorizontal = overlay.reticleHorizontal,
                        reticleEquatorial = overlay.reticleEquatorial,
                        nearestObjectTitle = overlay.nearestLabel?.title,
                        nearestObjectSeparationDeg = overlay.nearestLabel?.separationDeg,
                        targetLabel = targetLabel,
                        showNearestObject = visibility.showNearestObject,
                        showTargetAction = visibility.showTargetAction,
                        onSetTarget = { target?.let(onSetTarget) },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                    )
                }
            }
        }

        // CAM-1g + CAM-2b: debug-only diagnostic HUD - see the gate/collection setup above. Both
        // panels are stacked in one shared, width/height-bounded column (never two independently
        // -aligned overlays fighting for the same screen corner) - see `CamDiagnosticTopPanels`'s own
        // KDoc. Collapsed by default: no scrollable/pointer-capturing container exists until the tester
        // explicitly expands it, and even then the WHOLE HUD container (header rows included, not just
        // the scrollable child) stays bounded away from the center reticle (task hardening §2/§3, closed
        // more precisely by the whole-HUD-bound hardening pass). The CAM-2b controls entry point is
        // rendered by `CamDiagnosticTopPanels` itself now, not floated separately at `BottomEnd` - see
        // that composable's KDoc for why. It never modifies calculateOverlay, projectionParams, CAM-2a
        // projection math, or any displayPoint coordinate - it only displays what
        // CameraSessionGeometryProvider/the CAM-2b reducer already publish.
        if (CameraGeometryDiagnosticsGate.isEnabled) {
            CamDiagnosticTopPanels(
                cam1gSnapshot = cameraGeometryDiagnosticSnapshot,
                cam1gSessionId = cameraGeometryDiagnosticSessionId,
                cam1gStatusTransitionCount = cameraGeometryStatusTransitionCount,
                cam1gObservedFrameCount = cameraGeometryObservedFrameCount,
                cam1gReadyBundleCount = cameraGeometryReadyBundleCount,
                calibrationDiagnostics = cameraCalibrationDiagnostics,
                intrinsicsDiagnosticState = cameraIntrinsicsDiagnosticState,
                cam2bState = predictedStarOverlayState.takeIf { predictedStarDebugControls.showPredictedStarPanel },
                detailsExpanded = predictedStarDebugControls.hudDetailsExpanded,
                onDetailsExpandedChange = { predictedStarDebugControls.hudDetailsExpanded = it },
                controlsExpanded = predictedStarDebugControls.predictedStarControlsExpanded,
                onControlsExpandedChange = { predictedStarDebugControls.predictedStarControlsExpanded = it },
                controlsContent = {
                    PredictedStarOverlayControlsBody(
                        showMarkers = predictedStarDebugControls.showPredictedStarMarkers,
                        onShowMarkersChange = { predictedStarDebugControls.showPredictedStarMarkers = it },
                        showPanel = predictedStarDebugControls.showPredictedStarPanel,
                        onShowPanelChange = { predictedStarDebugControls.showPredictedStarPanel = it },
                        intrinsicsMode = predictedStarDebugControls.predictedStarIntrinsicsMode,
                        onIntrinsicsModeChange = { predictedStarDebugControls.predictedStarIntrinsicsMode = it },
                        showLegacyOverlay = predictedStarDebugControls.showLegacyOverlay,
                        onShowLegacyOverlayChange = { predictedStarDebugControls.showLegacyOverlay = it },
                    )
                },
            )
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.ar_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Text(
            text = stringResource(id = R.string.ar_permission_message),
            modifier = Modifier.padding(top = 16.dp),
            color = Color.White,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(id = R.string.ar_permission_request))
        }
    }
}

/**
 * The central aiming reticle - a static crosshair, no `OverlayData` or other `ArScreen`-internal type
 * dependency. `@VisibleForTesting`, not `private` (HUD-visibility follow-up §4): tests need to compose
 * this directly, independent of `showLegacyOverlay`, to prove it is never gated by that toggle (see
 * [LegacySkyOverlay]'s own KDoc for why it's excluded from that boundary).
 */
@VisibleForTesting
@Composable
fun Reticle(modifier: Modifier = Modifier) {
    val strokeWidth = 2.dp
    Canvas(
        modifier =
            modifier
                .size(96.dp),
    ) {
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = size.minDimension / 2f,
            style = Stroke(width = strokeWidth.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = strokeWidth.toPx(),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = strokeWidth.toPx(),
        )
    }
}

/**
 * The bottom altitude/azimuth + RA/Dec readout for the reticle's own aim direction, plus (when
 * [showNearestObject]/[showTargetAction] allow it) the nearest legacy-catalog object's name and a
 * button to watch-target it. [showNearestObject]/[showTargetAction] are computed by [infoPanelVisibility]
 * - see that function's own KDoc for why both are driven by the same flag (the legacy-target-identity-
 * leak fix: a hidden nearest-object *text* line is not full isolation if the watch-target *button* right
 * below it still renders that same hidden object's name and still sends it, as the outgoing target's
 * label, on click).
 *
 * Deliberately takes plain public types ([Horizontal], [Equatorial], nullable `String`/`Double`) rather
 * than the internal `OverlayData`/`OverlayObject` `ArScreen` itself uses - keeps this composable directly
 * Compose-UI-testable from `androidTest` (`InfoPanelPresentationTest`) without exposing those internal
 * types publicly solely for testing. [nearestObjectSeparationDeg] is the presence signal for "is there a
 * nearest object at all" (mirrors `OverlayData.nearestLabel` being null vs. non-null);
 * [nearestObjectTitle] may still be null even when a nearest object exists, falling back to
 * [R.string.ar_unknown_object] exactly as before this refactor.
 */
@VisibleForTesting
@Composable
fun InfoPanel(
    reticleHorizontal: Horizontal,
    reticleEquatorial: Equatorial,
    nearestObjectTitle: String?,
    nearestObjectSeparationDeg: Double?,
    targetLabel: String,
    showNearestObject: Boolean,
    showTargetAction: Boolean,
    onSetTarget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color = Color(0x99000000), shape = RoundedCornerShape(16.dp))
                .padding(16.dp),
    ) {
        Text(
            text =
                stringResource(
                    id = R.string.ar_reticle_alt_az,
                    formatAngle(locale, reticleHorizontal.altDeg),
                    formatAngle(locale, reticleHorizontal.azDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(AR_INFO_PANEL_ALT_AZ_TEST_TAG),
        )
        Text(
            text =
                stringResource(
                    id = R.string.ar_reticle_ra_dec,
                    formatAngle(locale, reticleEquatorial.raDeg),
                    formatAngle(locale, reticleEquatorial.decDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp).testTag(AR_INFO_PANEL_RA_DEC_TEST_TAG),
        )
        // Gated by showNearestObject: this line names a specific legacy-catalog star, the same
        // "nearest-object label/card" content LegacySkyOverlay isolates elsewhere - never shown while a
        // tester has legacy content hidden for a CAM-2b comparison, even though the rest of this panel
        // (alt/az, ra/dec) is retained.
        if (showNearestObject && nearestObjectSeparationDeg != null) {
            Text(
                text =
                    stringResource(
                        id = R.string.ar_nearest_object,
                        nearestObjectTitle ?: stringResource(id = R.string.ar_unknown_object),
                        formatAngle(locale, nearestObjectSeparationDeg),
                    ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag(AR_INFO_PANEL_NEAREST_OBJECT_TEST_TAG),
            )
        }
        // Gated by showTargetAction (legacy-target-identity-leak fix): this button's rendered label
        // names a specific legacy-catalog object (targetLabel), and on click sends that same label -
        // never the object's actual position, onSetTarget's ArTarget always carries the reticle's own
        // aim coordinates - via onSetTarget. A hidden nearest-object text line alone does not isolate
        // that identity if this button still renders "Set <legacy star> as watch target" and still fires
        // onSetTarget carrying that name. Never merely reworded to generic text and never merely
        // disabled while still showing the legacy name - the button itself must not exist while legacy
        // content is hidden.
        if (showTargetAction) {
            Button(
                onClick = onSetTarget,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG),
            ) {
                Text(text = stringResource(id = R.string.ar_set_target_button, targetLabel))
            }
        }
    }
}

@Composable
private fun ArObjectLabel(
    data: OverlayObject,
    modifier: Modifier = Modifier,
    rollDegrees: Float = 0f,
) {
    val density = LocalDensity.current
    val offset =
        remember(data.position, density) {
            val anchorX = with(density) { 80.dp.toPx() }
            val anchorY = with(density) { 48.dp.toPx() }
            IntOffset(
                x = (data.position.x - anchorX).roundToInt(),
                y = (data.position.y - anchorY).roundToInt(),
            )
        }
    Column(
        modifier =
            modifier
                .offset { offset }
                .graphicsLayer { rotationZ = rollDegrees }
                .background(color = Color(0x99000000), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = data.title ?: stringResource(id = R.string.ar_unknown_object),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text =
                stringResource(
                    id = R.string.ar_label_meta,
                    data.magnitude,
                    data.separationDeg,
                ),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AsterismLabel(
    data: AsterismLabelOverlay,
    modifier: Modifier = Modifier,
    rollDegrees: Float = 0f,
) {
    val density = LocalDensity.current
    val offset =
        remember(data.position, density) {
            val anchorX = with(density) { 80.dp.toPx() }
            val anchorY = with(density) { 32.dp.toPx() }
            IntOffset(
                x = (data.position.x - anchorX).roundToInt(),
                y = (data.position.y - anchorY).roundToInt(),
            )
        }
    Column(
        modifier =
            modifier
                .offset { offset }
                .graphicsLayer { rotationZ = rollDegrees }
                .background(
                    color = if (data.highlighted) Color(0xFF8BC34A).copy(alpha = 0.8f) else Color(0x99000000),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = data.text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun StarPointLayer(
    overlay: OverlayData,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val maxRadiusPx = STAR_POINT_MAX_RADIUS_DP.dp.toPx()
        val minRadiusPx = STAR_POINT_MIN_RADIUS_DP.dp.toPx()
        overlay.starPoints.forEach { star ->
            val t = ((star.magnitude - STAR_MAG_BRIGHT) / (STAR_MAG_DIM - STAR_MAG_BRIGHT))
                .coerceIn(0.0, 1.0)
                .toFloat()
            val radius = maxRadiusPx + (minRadiusPx - maxRadiusPx) * t
            val alpha = STAR_POINT_ALPHA_MAX + (STAR_POINT_ALPHA_MIN - STAR_POINT_ALPHA_MAX) * t
            drawCircle(
                color = BvColor.toColor(star.bv).copy(alpha = alpha),
                radius = radius,
                center = star.position,
            )
        }
    }
}

@Composable
private fun ReticleTargetHighlight(
    overlay: OverlayData,
    modifier: Modifier = Modifier,
) {
    val nearest = overlay.nearestLabel ?: return
    Canvas(modifier = modifier) {
        drawCircle(
            color = Color(0xFFFFEB3B).copy(alpha = 0.85f),
            radius = 20.dp.toPx(),
            center = nearest.position,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private val asterismPalette = listOf(
    Color(0xFFFFC107), // amber
    Color(0xFF4FC3F7), // light blue
    Color(0xFF81C784), // green
    Color(0xFFBA68C8), // purple
    Color(0xFFFF8A65), // deep orange
    Color(0xFF4DD0E1), // cyan
    Color(0xFFF06292), // pink
    Color(0xFFAED581), // lime
)

@Composable
private fun ConstellationLayer(
    overlay: OverlayData,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val constellationStroke = 2.dp.toPx()
        val asterismStroke = 3.dp.toPx()
        overlay.constellationSegments.forEach { segment ->
            drawLine(
                color = Color(0x66FFFFFF),
                start = segment.start,
                end = segment.end,
                strokeWidth = constellationStroke,
            )
        }
        overlay.asterismSegments.forEach { segment ->
            val base = asterismPalette[segment.colorIndex % asterismPalette.size]
            drawLine(
                color = if (segment.highlighted) base else base.copy(alpha = 0.4f),
                start = segment.start,
                end = segment.end,
                strokeWidth = if (segment.highlighted) asterismStroke else constellationStroke,
            )
        }
        overlay.artOverlays.forEach { art ->
            val left = min(art.anchorA.x, art.anchorB.x)
            val top = min(art.anchorA.y, art.anchorB.y)
            val width = max(art.anchorA.x, art.anchorB.x) - left
            val height = max(art.anchorA.y, art.anchorB.y) - top
            drawRect(
                color = Color(0x33FFFFFF),
                topLeft = Offset(left, top),
                size = Size(width.coerceAtLeast(24f), height.coerceAtLeast(24f)),
            )
        }
    }
}

// PRODUCT NOTE / TODO(catalog-capabilities): The constellation selector and pro-mode controls are
// currently shown unconditionally. With the A1 star-points-only catalog there are no LINE_NODE stars
// and the ASTR/APLY/ASTN/ART0 sections are empty, so these toggles are inert. Capability-gating is
// deferred to a separate UI PR.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArControlsPanel(
    constellationMode: ConstellationMode,
    proMode: Boolean,
    magLimit: Double,
    showStarLabels: Boolean,
    showStarPoints: Boolean,
    reticleTargetOnly: Boolean,
    visibilityFilterEnabled: Boolean,
    bortle: Bortle,
    bortleSource: BortleSource,
    autoBortle: Double?,
    lightPollutionAvailable: Boolean,
    limitingMag: Double?,
    onConstellationModeChange: (ConstellationMode) -> Unit,
    onProModeChange: (Boolean) -> Unit,
    onMagLimitChange: (Double) -> Unit,
    onShowStarLabelsToggle: (Boolean) -> Unit,
    onShowStarPointsToggle: (Boolean) -> Unit,
    onReticleTargetOnlyToggle: (Boolean) -> Unit,
    onVisibilityFilterToggle: (Boolean) -> Unit,
    onBortleChange: (Bortle) -> Unit,
    onBortleSourceChange: (BortleSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(color = Color(0x99000000), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ar_constellation_mode_label),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        val modes = ConstellationMode.entries
        val modeLabels =
            listOf(
                stringResource(R.string.ar_constellation_mode_off),
                stringResource(R.string.ar_constellation_mode_asterisms),
                stringResource(R.string.ar_constellation_mode_figure),
            )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = constellationMode == mode,
                    onClick = { onConstellationModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(
                            text = modeLabels[index],
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        }
        ToggleRow(
            title = stringResource(R.string.ar_advanced_toggle),
            checked = proMode,
            onCheckedChange = onProModeChange,
        )
        if (proMode) {
            MagnitudeSlider(
                magLimit = magLimit,
                onMagLimitChange = onMagLimitChange,
            )
            if (limitingMag != null) {
                Text(
                    text = stringResource(
                        R.string.ar_visibility_readout,
                        String.format("%.1f", limitingMag),
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ToggleRow(
                title = stringResource(R.string.ar_visibility_filter_title),
                checked = visibilityFilterEnabled,
                onCheckedChange = onVisibilityFilterToggle,
            )
            if (visibilityFilterEnabled) {
                if (!lightPollutionAvailable) {
                    BortleSlider(bortle = bortle, onBortleChange = onBortleChange)
                } else {
                    BortleSourceToggle(
                        bortleSource = bortleSource,
                        autoBortle = autoBortle,
                        bortle = bortle,
                        onBortleSourceChange = onBortleSourceChange,
                        onBortleChange = onBortleChange,
                    )
                }
            }
            ToggleRow(
                title = stringResource(R.string.ar_hide_star_labels),
                checked = !showStarLabels,
                onCheckedChange = { hide -> onShowStarLabelsToggle(!hide) },
            )
            ToggleRow(
                title = stringResource(R.string.ar_show_star_points),
                checked = showStarPoints,
                onCheckedChange = onShowStarPointsToggle,
            )
            ToggleRow(
                title = stringResource(R.string.ar_reticle_target_only),
                checked = reticleTargetOnly,
                onCheckedChange = onReticleTargetOnlyToggle,
            )
        }
    }
}

@Composable
private fun MagnitudeSlider(
    magLimit: Double,
    onMagLimitChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderValue = magLimit.toFloat()
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.ar_mag_limit_title, String.format("%.1f", magLimit)),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = sliderValue,
            onValueChange = { onMagLimitChange(it.toDouble()) },
            valueRange = 0f..7f,
            steps = 14,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BortleSourceToggle(
    bortleSource: BortleSource,
    autoBortle: Double?,
    bortle: Bortle,
    onBortleSourceChange: (BortleSource) -> Unit,
    onBortleChange: (Bortle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val sources = BortleSource.entries
        val sourceLabels = listOf(
            stringResource(R.string.ar_bortle_source_auto),
            stringResource(R.string.ar_bortle_source_manual),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            sources.forEachIndexed { index, source ->
                SegmentedButton(
                    selected = bortleSource == source,
                    onClick = { onBortleSourceChange(source) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = sources.size),
                    label = {
                        Text(text = sourceLabels[index], style = MaterialTheme.typography.bodySmall)
                    },
                )
            }
        }
        if (bortleSource == BortleSource.AUTO) {
            if (autoBortle != null) {
                Text(
                    text = stringResource(
                        R.string.ar_bortle_auto_detected,
                        String.format(Locale.US, "%.1f", autoBortle),
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.ar_bortle_auto_unavailable),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                BortleSlider(bortle = bortle, onBortleChange = onBortleChange)
            }
        } else {
            BortleSlider(bortle = bortle, onBortleChange = onBortleChange)
        }
    }
}

@Composable
private fun BortleSlider(
    bortle: Bortle,
    onBortleChange: (Bortle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bortleNumber = bortle.ordinal + 1
    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.ar_sky_darkness_title, bortleNumber),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = bortleNumber.toFloat(),
            onValueChange = { onBortleChange(Bortle.entries[(it.toInt() - 1).coerceIn(0, 8)]) },
            valueRange = 1f..9f,
            steps = 7,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The phone's existing visibility selection: renderable catalog stars within
 * [ArUiState.Ready.effectiveMagLimit]. Shared by [calculateOverlay] (the legacy renderer) and the
 * CAM-2b predicted-star overlay (`dev.pointtosky.mobile.ar.camera.prediction`) so CAM-2b's bounded
 * catalog subset is built from the phone's one existing visibility selection rather than
 * re-implementing it (`docs/camera_star_prediction_contract.md` §14, "prefer the current
 * visibility-selected phone catalog prefix rather than reading all stars independently").
 */
@VisibleForTesting
internal fun visibilitySelectedStars(state: ArUiState.Ready): List<StarRecord> =
    state.catalog?.catalog?.allStars().orEmpty()
        .filter { it.isRenderablePoint() }
        // effectiveMagLimit already encodes "no cap" as +∞, so apply it directly — no ≤ 0
        // sentinel (a visibility limit can legitimately be ≤ 0, e.g. daytime, and must gate stars).
        .filter { it.magnitude.toDouble() <= state.effectiveMagLimit }

@VisibleForTesting
internal fun calculateOverlay(
    state: ArUiState.Ready,
    frame: RotationFrame,
    viewport: IntSize,
    resolveConstellation: (Equatorial) -> ConstellationId?,
    declinationDeg: Double = 0.0,
): OverlayData? {
    if (viewport.width == 0 || viewport.height == 0) return null

    val trueNorthFrame = frame.correctedForTrueNorth(declinationDeg)
    val reticleHorizontal = vectorToHorizontal(trueNorthFrame.forwardWorld)
    val reticleEquatorial =
        altAzToRaDec(
            reticleHorizontal,
            lstDeg = state.lstDeg,
            latDeg = state.location.latDeg,
        )
    // rotationMatrix is already remapped to the current display rotation.
    val worldToDevice = transpose(trueNorthFrame.rotationMatrix)
    val projectionParams = projectionParams(viewport.toViewportSize())

    data class ProjectionResult(
        val position: Offset,
        val distance: Double,
        val separationDeg: Double,
    )

    fun projectEquatorial(eq: Equatorial): ProjectionResult? {
        val horizontal =
            raDecToAltAz(
                eq,
                lstDeg = state.lstDeg,
                latDeg = state.location.latDeg,
                applyRefraction = false,
            )
        if (horizontal.altDeg < 0.0) return null
        val worldVec = horizontalToVector(horizontal)
        val deviceVec = multiply(worldToDevice, worldVec)
        val projection = projectDeviceVector(deviceVec, projectionParams) ?: return null
        val separation = angularSeparationDeg(reticleEquatorial, eq)
        return ProjectionResult(Offset(projection.x, projection.y), projection.distance, separation)
    }

    fun projectStarRecord(record: StarRecord): Offset? =
        projectEquatorial(
            Equatorial(
                record.rightAscensionDeg.toDouble(),
                record.declinationDeg.toDouble(),
            ),
        )?.position

    val constellationId = resolveConstellation(reticleEquatorial)

    // Full catalog — star points and labels span the whole frustum, not just the reticle's constellation.
    val visibleStars: List<StarRecord> = visibilitySelectedStars(state)

    // Project ALL mag-filtered stars; projectEquatorial returns null for off-screen ones.
    val projectedStars: List<OverlayObject> =
        visibleStars.mapNotNull { star ->
            val equatorial =
                Equatorial(
                    star.rightAscensionDeg.toDouble(),
                    star.declinationDeg.toDouble(),
                )
            val projection = projectEquatorial(equatorial) ?: return@mapNotNull null
            OverlayObject(
                title = star.name,
                magnitude = star.magnitude.toDouble(),
                position = projection.position,
                distance = projection.distance,
                separationDeg = projection.separationDeg,
                bv = star.bv,
            )
        }

    // Sort on-screen stars brightest-first; cap for points AFTER projection.
    val byBrightness = projectedStars.sortedBy { it.magnitude }

    val starPoints: List<StarPointOverlay> =
        byBrightness.take(MAX_STAR_POINTS)
            .map { StarPointOverlay(position = it.position, magnitude = it.magnitude, bv = it.bv) }

    // Hybrid label selection: reticle-nearest always first, then brightest non-colliding.
    val nearest = projectedStars.minByOrNull { it.separationDeg }
    val labels = mutableListOf<OverlayObject>()
    val placed = mutableListOf<Offset>()
    nearest?.let { labels += it; placed += it.position }
    for (candidate in byBrightness) {
        if (candidate === nearest) continue
        if (labels.size >= MAX_LABELS) break
        val cx = candidate.position.x
        val cy = candidate.position.y
        val noCollision =
            placed.all { p ->
                val dx = (p.x - cx).toDouble()
                val dy = (p.y - cy).toDouble()
                sqrt(dx * dx + dy * dy) >= MIN_LABEL_SPACING_PX
            }
        if (noCollision) {
            labels += candidate
            placed += candidate.position
        }
    }

    val constellationSegments =
        if (state.showConstellations) {
            state.catalog?.skeletonLines.orEmpty().mapNotNull { segment ->
                val start = projectStarRecord(segment.start) ?: return@mapNotNull null
                val end = projectStarRecord(segment.end) ?: return@mapNotNull null
                ScreenLineSegment(start = start, end = end, highlighted = false)
            }
        } else {
            emptyList()
        }

    var asterismState = state.asterismUiState
    val asterismSegments = mutableListOf<ScreenLineSegment>()
    val asterismLabels = mutableListOf<AsterismLabelOverlay>()
    val artRenderer = ConstellationArtRenderer()
    var artOverlays = emptyList<ConstellationArtOverlay>()

    if (!state.showAsterisms || !state.asterismUiState.isEnabled) {
        asterismState = asterismState.copy(available = emptyList())
    } else {
        val catalogState = state.catalog
        if (catalogState != null && constellationId != null) {
            val asterisms = catalogState.catalog.asterismsByConstellation(constellationId)
            val available =
                asterisms.map { asterism ->
                    AsterismSummary(
                        id = asterism.name,
                        name = asterism.name,
                        constellationId = constellationId,
                    )
                }
            val defaultHighlight =
                when (catalogState.catalog.getConstellationMeta(constellationId).abbreviation.uppercase(Locale.ROOT)) {
                    "ORI" -> available.firstOrNull { it.name == "Orion's Belt" }?.id
                    "LYR" -> available.firstOrNull { it.name == "Lyra Triangle" }?.id
                    else -> null
                }
            val highlighted = asterismState.highlighted ?: defaultHighlight
            asterismState = asterismState.copy(available = available, highlighted = highlighted)
            asterisms.forEachIndexed { index, asterism ->
                val segments = buildAsterismSegments(asterism, catalogState.catalog)
                val isHighlighted = asterismState.highlighted == asterism.name
                segments.forEach { segment ->
                    val start = projectStarRecord(segment.start) ?: return@forEach
                    val end = projectStarRecord(segment.end) ?: return@forEach
                    asterismSegments +=
                        ScreenLineSegment(
                            start = start,
                            end = end,
                            highlighted = isHighlighted,
                            colorIndex = index,
                        )
                }

                val labelStar = catalogState.starsById[asterism.labelStarId.raw]
                val labelPosition = labelStar?.let(::projectStarRecord)
                if (labelPosition != null) {
                    asterismLabels +=
                        AsterismLabelOverlay(
                            position = labelPosition,
                            text = asterism.name,
                            highlighted = isHighlighted,
                        )
                }
            }
            artOverlays =
                artRenderer.render(
                    overlays = catalogState.catalog.artOverlaysByConstellation(constellationId),
                    projectStar = { starId -> catalogState.starsById[starId.raw]?.let(::projectStarRecord) },
                ).map { overlay ->
                    if (overlay.key == ConstellationArtRenderer.ORION_SILHOUETTE_KEY) {
                        artRenderer.drawOrionSilhouette(overlay.anchorA, overlay.anchorB)
                    } else {
                        overlay
                    }
                }
        } else {
            asterismState = asterismState.copy(available = emptyList())
        }
    }

    return OverlayData(
        reticleHorizontal = reticleHorizontal,
        reticleEquatorial = reticleEquatorial,
        labels = labels,
        nearestLabel = nearest,
        starPoints = starPoints,
        constellationSegments = constellationSegments,
        asterismSegments = asterismSegments,
        asterismLabels = asterismLabels,
        asterismState = asterismState,
        artOverlays = artOverlays,
    )
}

@VisibleForTesting
internal data class OverlayObject(
    val title: String?,
    val magnitude: Double,
    val position: Offset,
    val distance: Double,
    val separationDeg: Double,
    val bv: Float? = null,
)

@VisibleForTesting
internal data class StarPointOverlay(
    val position: Offset,
    val magnitude: Double,
    val bv: Float? = null,
)

@VisibleForTesting
internal data class OverlayData(
    val reticleHorizontal: Horizontal,
    val reticleEquatorial: Equatorial,
    val labels: List<OverlayObject>,
    val nearestLabel: OverlayObject?,
    val starPoints: List<StarPointOverlay>,
    val constellationSegments: List<ScreenLineSegment>,
    val asterismSegments: List<ScreenLineSegment>,
    val asterismLabels: List<AsterismLabelOverlay>,
    val asterismState: AsterismUiState,
    val artOverlays: List<ConstellationArtOverlay>,
)

@VisibleForTesting
internal fun projectHorizontalsToScreen(
    frame: RotationFrame,
    viewport: IntSize,
    horizontals: List<Horizontal>,
    declinationDeg: Double = 0.0,
): List<Offset> {
    if (viewport.width == 0 || viewport.height == 0) return emptyList()

    val trueNorthFrame = frame.correctedForTrueNorth(declinationDeg)
    val worldToDevice = transpose(trueNorthFrame.rotationMatrix)
    val params = projectionParams(viewport.toViewportSize())

    return horizontals.mapNotNull { horizontal ->
        val worldVec = horizontalToVector(horizontal)
        val deviceVec = multiply(worldToDevice, worldVec)
        projectDeviceVector(deviceVec, params)?.let { Offset(it.x, it.y) }
    }
}

// Mobile boundary: convert the Compose IntSize viewport into the Android-independent core type.
private fun IntSize.toViewportSize(): ViewportSize = ViewportSize(width = width, height = height)

private fun formatAngle(
    locale: Locale,
    value: Double,
): String = String.format(locale, "%.1f", value)

// Label declutter: max labels on screen; minimum screen-distance between any two label anchors.
private const val MAX_LABELS = 9
private const val MIN_LABEL_SPACING_PX = 108.0

// Star-point layer: cap visible stars for Canvas perf; dot radius/alpha range by magnitude.
private const val MAX_STAR_POINTS = 1500
private const val STAR_POINT_MAX_RADIUS_DP = 4f
private const val STAR_POINT_MIN_RADIUS_DP = 1.5f
private const val STAR_MAG_BRIGHT = 0.0
private const val STAR_MAG_DIM = 6.0
private const val STAR_POINT_ALPHA_MAX = 1.0f
private const val STAR_POINT_ALPHA_MIN = 0.5f
