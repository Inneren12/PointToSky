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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticsGate
import dev.pointtosky.mobile.ar.camera.CameraSessionGeometryProvider
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsCoordinator
import dev.pointtosky.mobile.ar.camera.CameraTimestampSynchronizer
import dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver
import dev.pointtosky.mobile.ar.camera.buildCameraGeometryDiagnosticText
import dev.pointtosky.mobile.ar.camera.nextDebugSessionId
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
    val intrinsicsResolver = remember { SessionScopedCameraIntrinsicsResolver() }
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

    // CAM-1g: debug-only consumer of geometryProvider.state, gated so it never affects a production
    // build (see docs/camera_coordinate_calibration_contract.md §11). Collected with
    // collectAsStateWithLifecycle so collection stops automatically when this composition leaves -
    // the same pattern ArRoute already uses for the AR view-model state - never a manually launched
    // permanent coroutine. This is purely a read of the existing provider's state; it starts no new
    // camera, sensor, resolver, or provider, and never feeds calculateOverlay/projectionParams.
    val cameraGeometryDiagnosticSnapshot: CameraGeometryDiagnosticSnapshot?
    val cameraGeometryDiagnosticSessionId: Long
    val cameraGeometryStatusTransitionCount: Int
    val cameraGeometryObservedFrameCount: Long
    val cameraGeometryReadyBundleCount: Long
    if (CameraGeometryDiagnosticsGate.isEnabled) {
        val geometryResult by geometryProvider.state.collectAsStateWithLifecycle()
        val sessionId = remember { nextDebugSessionId() }
        val snapshot = remember(geometryResult) { geometryResult.toDiagnosticSnapshot() }
        val transitionCount = remember { mutableStateOf(0) }
        val lastCategory = remember { mutableStateOf<CameraGeometryDiagnosticCategory?>(null) }
        SideEffect {
            val previous = lastCategory.value
            if (previous != null && previous != snapshot.category) {
                transitionCount.value++
            }
            lastCategory.value = snapshot.category
        }
        val debugState = geometryProvider.debugState()

        cameraGeometryDiagnosticSnapshot = snapshot
        cameraGeometryDiagnosticSessionId = sessionId
        cameraGeometryStatusTransitionCount = transitionCount.value
        cameraGeometryObservedFrameCount = debugState.observedFrameCount
        cameraGeometryReadyBundleCount = debugState.readyBundleCount
    } else {
        cameraGeometryDiagnosticSnapshot = null
        cameraGeometryDiagnosticSessionId = 0L
        cameraGeometryStatusTransitionCount = 0
        cameraGeometryObservedFrameCount = 0L
        cameraGeometryReadyBundleCount = 0L
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
                val declinationDeg = remember(state.location) {
                    android.hardware.GeomagneticField(
                        state.location.latDeg.toFloat(),
                        state.location.lonDeg.toFloat(),
                        0f,
                        System.currentTimeMillis(),
                    ).declination.toDouble()
                }
                val overlay =
                    remember(state, rotationFrame, overlaySize, declinationDeg) {
                        if (rotationFrame != null && overlaySize != IntSize.Zero) {
                            calculateOverlay(
                                state = state,
                                frame = rotationFrame,
                                viewport = overlaySize,
                                resolveConstellation = resolveConstellation,
                                declinationDeg = declinationDeg,
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

                Reticle(modifier = Modifier.align(Alignment.Center))

                // Counter-rotate overlay labels so they stay upright relative to the real horizon
                // while the projected constellation rotates with device roll (portrait-locked UI).
                val labelRoll = rotationFrame?.let { deviceRollDegrees(it.rotationMatrix) } ?: 0f

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
                    InfoPanel(
                        overlay = overlay,
                        targetLabel = targetLabel,
                        onSetTarget = { target?.let(onSetTarget) },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                    )
                }
            }
        }

        // CAM-1g: debug-only camera-geometry diagnostic overlay - see the gate/collection setup
        // above. Non-interactive (plain Text, no clickable/pointerInput modifier), so it never
        // intercepts AR gestures or camera interaction, and it never modifies calculateOverlay,
        // projectionParams, or star positions - it only displays what CameraSessionGeometryProvider
        // already publishes.
        if (CameraGeometryDiagnosticsGate.isEnabled && cameraGeometryDiagnosticSnapshot != null) {
            CameraGeometryDiagnosticOverlay(
                snapshot = cameraGeometryDiagnosticSnapshot,
                sessionId = cameraGeometryDiagnosticSessionId,
                statusTransitionCount = cameraGeometryStatusTransitionCount,
                observedFrameCount = cameraGeometryObservedFrameCount,
                readyBundleCount = cameraGeometryReadyBundleCount,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(top = 72.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

/**
 * CAM-1g compact diagnostic overlay (§5) - translucent, monospace, width-bounded, non-clickable.
 * Purely a display of [CameraGeometryDiagnosticSnapshot]; touches no renderer/matcher/detector state.
 */
@Composable
private fun CameraGeometryDiagnosticOverlay(
    snapshot: CameraGeometryDiagnosticSnapshot,
    sessionId: Long,
    statusTransitionCount: Int,
    observedFrameCount: Long,
    readyBundleCount: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            buildCameraGeometryDiagnosticText(
                snapshot = snapshot,
                sessionId = sessionId,
                statusTransitionCount = statusTransitionCount,
                observedFrameCount = observedFrameCount,
                readyBundleCount = readyBundleCount,
            ),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            modifier
                .widthIn(max = 280.dp)
                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
    )
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

@Composable
private fun Reticle(modifier: Modifier = Modifier) {
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

@Composable
private fun InfoPanel(
    overlay: OverlayData,
    targetLabel: String,
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
                    formatAngle(locale, overlay.reticleHorizontal.altDeg),
                    formatAngle(locale, overlay.reticleHorizontal.azDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    id = R.string.ar_reticle_ra_dec,
                    formatAngle(locale, overlay.reticleEquatorial.raDeg),
                    formatAngle(locale, overlay.reticleEquatorial.decDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        overlay.nearestLabel?.let { nearest ->
            Text(
                text =
                    stringResource(
                        id = R.string.ar_nearest_object,
                        nearest.title ?: stringResource(id = R.string.ar_unknown_object),
                        formatAngle(locale, nearest.separationDeg),
                    ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onSetTarget,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.ar_set_target_button, targetLabel))
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
    val visibleStars: List<StarRecord> =
        (state.catalog?.catalog?.allStars().orEmpty())
            .filter { it.isRenderablePoint() }
            // effectiveMagLimit already encodes "no cap" as +∞, so apply it directly — no ≤ 0
            // sentinel (a visibility limit can legitimately be ≤ 0, e.g. daytime, and must gate stars).
            .filter { it.magnitude.toDouble() <= state.effectiveMagLimit }

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
