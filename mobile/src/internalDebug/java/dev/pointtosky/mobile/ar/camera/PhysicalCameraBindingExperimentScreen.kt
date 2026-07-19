package dev.pointtosky.mobile.ar.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pointtosky.mobile.ar.CameraPreview
import dev.pointtosky.mobile.ar.EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO

/**
 * CAM-2c physical-camera provenance experiment (`internalDebug` only, task §4). Standalone screen -
 * not layered onto the live AR renderer's own [dev.pointtosky.mobile.ar.CameraPreview] binding, since
 * most devices only support one open camera session at a time and this experiment needs full control
 * over the [androidx.camera.core.CameraSelector] used for that one session.
 *
 * **Launch path (fix for a reachability defect, and later a testability defect).** This `Activity` is
 * `android:exported="false"` (see `mobile/src/internalDebug/AndroidManifest.xml`) — a prior revision of
 * this file's own KDoc documented launching it via `adb shell am start -n ...`, which is not a launch
 * mechanism this codebase can stand behind for a non-exported component. The actual, verified entry
 * point is in-app: the existing CAM diagnostics dialog (`CamDiagnosticFullReportDialog.kt`,
 * `internalDebug`-only) has an "Open physical-camera experiment" action that calls
 * `context.startActivity(buildPhysicalCameraBindingExperimentIntent(context))` — a same-app,
 * same-process `Intent` this way is always permitted regardless of `exported`, since that flag only
 * restricts *other* apps/processes from starting this component. [buildPhysicalCameraBindingExperimentIntent]
 * is the one function that ever constructs that `Intent` (fix for a testability gap: a prior revision
 * inlined the `Intent(...)` construction directly in the button's `onClick`, so no test could observe
 * what it actually launches) — `ExperimentLaunchIntentTest`/`CamDiagnosticPhysicalCameraExperimentLaunchUiTest`
 * (`androidTestInternalDebug`) assert its `component.className` and that `PackageManager` resolves it, and
 * that the underlying `Activity` stays `exported="false"` - **compiled against real Android/`PackageManager`
 * APIs, not yet executed on a device or emulator in this environment; see
 * `docs/validation/cam_2c_pixel9_evidence.md` for the exact, honest claim.**
 * [PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME] guards against the class name itself drifting
 * from the manifest's own declared `android:name`.
 *
 * Flow: enumerate the rear logical camera's declared physical candidates (never inferred from ID
 * ordering - task §4) &#8594; user taps a candidate &#8594; [CameraPreview] binds with an explicit
 * `CameraSelector.Builder().setPhysicalCameraId(candidate)` selector (task requires Preview and
 * ImageAnalysis share one coherent binding - guaranteed here since both are bound in the same
 * `bindToLifecycle` call inside [CameraPreview]) &#8594; the bound [CameraInfo] is verified
 * ([resolvePhysicalCameraBindingFromCameraInfo]) &#8594; once a frame's buffer dimensions and
 * sensor-to-buffer transform are known alongside a [SensorToBufferDomainProof],
 * [resolveCam2cForExplicitPhysicalCamera] is attempted and its exact typed outcome is displayed -
 * never silently downgraded to "it worked" or "it's blocked".
 */
class PhysicalCameraBindingExperimentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhysicalCameraBindingExperimentScreen()
        }
    }
}

/** Fully-qualified class name of [PhysicalCameraBindingExperimentActivity], as reflection actually
 * sees it - never a hand-typed string literal duplicated between this file, the manifest, and any
 * launch `Intent`. `PhysicalCameraBindingExperimentActivityLaunchTest` asserts this equals the exact
 * string `mobile/src/internalDebug/AndroidManifest.xml` declares as that activity's `android:name`, so
 * a future rename cannot silently desynchronize the two. */
internal val PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME: String =
    PhysicalCameraBindingExperimentActivity::class.java.name

/**
 * Builds the one explicit, same-app [Intent] that actually launches [PhysicalCameraBindingExperimentActivity]
 * (fix for a launch-path testability gap: a prior revision inlined `Intent(context,
 * PhysicalCameraBindingExperimentActivity::class.java)` directly inside `CamDiagnosticFullReportDialog`'s
 * "Open physical-camera experiment" `onClick`, which meant no test could observe *what* that click
 * actually launches - only that the reflected class name matched a hand-written string, which does not
 * prove the in-app action launches the registered `Activity`). Both the real button
 * (`CamDiagnosticFullReportDialog.kt`) and `ExperimentLaunchIntentTest`/
 * `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` (`androidTestInternalDebug`) call this exact
 * function - never a second, independently hand-written `Intent(...)` construction that could silently
 * drift from it.
 */
internal fun buildPhysicalCameraBindingExperimentIntent(context: Context): Intent =
    Intent(context, PhysicalCameraBindingExperimentActivity::class.java)

@Composable
internal fun PhysicalCameraBindingExperimentScreen() {
    val context = LocalContext.current
    var hasCameraPermission by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }
    val topology = remember { buildCameraTopologyReport(context, boundCameraInfo = null) }
    val candidates = remember(topology) { topology.entries.flatMap { it.declaredPhysicalCameraIds }.distinct().sorted() }
    // All attempt/session transition logic ([ExperimentUiModel.startAttempt]/[retry]/[backToCandidates]/
    // [updateSession]) lives in a pure, independently unit-tested type - this composable only owns the
    // single `remember`ed value and wires Compose events to it.
    var uiModel by remember { mutableStateOf(ExperimentUiModel()) }
    // Dual-basis slice (task §11): a tapped candidate first goes through an explicit resolution pick
    // (CameraX default, plus the device-declared near-4:3/16:9 candidates) before an attempt starts.
    // A resolution switch later always goes back through startAttempt - a brand-new attemptId /
    // generation - never a mutation of a live session's dimensions.
    var pendingCandidate by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        if (!hasCameraPermission) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Camera permission required", color = Color.White)
                Text(
                    "Grant",
                    color = Color.Cyan,
                    modifier =
                        Modifier.testTag("physical_camera_experiment_grant_permission").clickable {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                )
            }
            return@Surface
        }

        val session = uiModel.session
        if (session == null) {
            val candidate = pendingCandidate
            if (candidate == null) {
                CandidatePicker(candidates = candidates, onSelected = { pendingCandidate = it })
            } else {
                // Resolution candidates come from the real, device-declared YUV_420_888 sizes of the
                // logical camera that declares this physical candidate (never assumed sizes - task §11).
                val resolutionCandidates =
                    remember(topology, candidate) {
                        val declaringEntry = topology.entries.firstOrNull { candidate in it.declaredPhysicalCameraIds }
                        selectAnalysisResolutionCandidates(
                            parseAnalysisResolutions(declaringEntry?.imageAnalysisStreamConfigurationsPx ?: emptyList()),
                        )
                    }
                ResolutionPicker(
                    physicalCameraId = candidate,
                    candidates = resolutionCandidates,
                    onSelected = { resolution ->
                        uiModel = uiModel.startAttempt(candidate, resolution)
                        pendingCandidate = null
                    },
                    onBack = { pendingCandidate = null },
                )
            }
        } else {
            // key(attemptId): a NEW attempt (retry, or a different candidate) always gets an entirely
            // fresh composable subtree - a fresh CameraPreview, a fresh
            // DisposableEffect/CameraSessionLifecycle, a fresh bind. This is the mechanism that
            // guarantees candidate-A's CameraX session is fully torn down (never merely
            // recomposed-over) before candidate-B's session exists - never relying on callback
            // freshness alone to isolate one attempt from the next.
            key(session.attemptId) {
                PhysicalCameraBindingSession(
                    state = session,
                    onUpdateSession = { attemptId, reducer -> uiModel = uiModel.updateSession(attemptId, reducer) },
                    onRetry = { uiModel = uiModel.retry() },
                    onBackToCandidates = { uiModel = uiModel.backToCandidates() },
                )
            }
        }
    }
}

/** Resolution pick step (task §11): CameraX default plus the device-declared near-4:3/16:9
 * candidates. Every option is explicit and user-selectable; nothing is auto-selected. */
@Composable
private fun ResolutionPicker(
    physicalCameraId: String,
    candidates: List<AnalysisResolutionCandidate>,
    onSelected: (AnalysisResolutionCandidate?) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Select ImageAnalysis resolution for physical id=$physicalCameraId",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "CameraX default (no override)",
            color = Color.White,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag("physical_camera_resolution_default")
                    .background(Color(0xFF1B4B66), RoundedCornerShape(8.dp))
                    .clickable { onSelected(null) }
                    .padding(12.dp),
        )
        for (candidate in candidates) {
            Text(
                text = "${candidate.label()} (family=${candidate.family}, aspect=${candidate.aspectRatio})",
                color = Color.White,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("physical_camera_resolution_${candidate.label()}")
                        .background(Color(0xFF1B4B66), RoundedCornerShape(8.dp))
                        .clickable { onSelected(candidate) }
                        .padding(12.dp),
            )
        }
        Text(
            "Back to candidates",
            color = Color.Cyan,
            modifier = Modifier.testTag("physical_camera_resolution_back").clickable { onBack() },
        )
    }
}

@Composable
private fun CandidatePicker(
    candidates: List<String>,
    onSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Select a physical camera candidate (rear)", color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (candidates.isEmpty()) {
            Text(
                "No physical camera candidates declared - this device's rear camera is not a logical " +
                    "multi-camera, or topology recon could not read CameraManager.",
                color = Color.White,
            )
        }
        for (candidate in candidates) {
            Text(
                text = "physical id=$candidate",
                color = Color.White,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("physical_camera_candidate_$candidate")
                        .background(Color(0xFF1B4B66), RoundedCornerShape(8.dp))
                        .clickable { onSelected(candidate) }
                        .padding(12.dp),
            )
        }
    }
}

/**
 * Renders exactly one attempt ([state]). While the attempt is not yet terminally failed, binds and
 * shows the live [CameraPreview]; every event it reports is applied via [onUpdateSession] against the
 * pure [ExperimentSessionState] reducers (`reduceCameraInfoResolved`/`reduceFrame`/
 * `reduceExplicitBindFailure`) - never an ad-hoc, hand-rolled state mutation here.
 *
 * **Terminal cleanup (fix for a resource-lifecycle gap).** Once [ExperimentSessionState.isTerminallyFailed]
 * is `true` (an explicit bind or zoom failure was reported), this composable stops calling
 * [CameraPreview] entirely - the live camera/analyzer this attempt bound is not merely hidden behind a
 * failure banner, it is fully removed from composition, which runs `CameraPreview`'s own
 * `DisposableEffect`/`CameraSessionLifecycle` disposal path and actually unbinds the camera. No new,
 * separate unbind call is added here - reusing the existing, already-idempotent disposal path avoids
 * any risk of double-unbinding/double-shutting-down `CameraSessionLifecycle`. [onRetry]/[onBackToCandidates]
 * give the user a way to leave this terminal state - retrying starts a brand new attempt (a new
 * `attemptId`, so a late callback from this failed attempt can never mutate the retry's state; see
 * [ExperimentSessionState]'s own KDoc).
 *
 * **Terminal-UI reachability (fix for a layout defect).** A prior revision rendered the failure
 * banner/Retry/Back controls as the *first* child of a plain [Box], then unconditionally rendered a
 * `fillMaxSize`, scrollable report layer as the *second* child - later children draw on top in a `Box`,
 * so that fullscreen report layer visually covered the controls underneath it and intercepted every
 * pointer event over the whole screen, making Retry/Back physically unreachable. Fixed: while
 * [ExperimentSessionState.isTerminallyFailed], this composable renders exactly one scrollable `Column`
 * - failure banner, then the report text, then Retry, then Back to candidates - as sibling items in a
 * single layout, never two independently-`fillMaxSize`d layers stacked in a `Box`. Every item is a real
 * sibling in the scroll order, so on a small screen or with a long report, scrolling to the bottom always
 * reaches Retry/Back - never blocked by an overlay drawn on top. The live (non-terminal) state is
 * unaffected: it has no controls to obscure, so `CameraPreview` plus a translucent report overlay drawn
 * on top of it (via `Box`) is unchanged.
 */
@Composable
internal fun PhysicalCameraBindingSession(
    state: ExperimentSessionState,
    onUpdateSession: (attemptId: Long, reducer: (ExperimentSessionState) -> ExperimentSessionState) -> Unit,
    onRetry: () -> Unit,
    onBackToCandidates: () -> Unit,
) {
    val context = LocalContext.current
    val attemptId = state.attemptId
    val physicalCameraId = state.physicalCameraId

    if (state.isTerminallyFailed) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFF0A0A0A))
                    .padding(16.dp)
                    // A distinct tag from physical_camera_experiment_report below - this is the
                    // scrollable *container* a test scrolls, never the report text node itself, so
                    // hasText assertions against the report tag below always target a node whose own
                    // semantics actually carry that text, never a parent relying on merged-descendant
                    // semantics this Column never opts into.
                    .testTag("physical_camera_experiment_terminal_scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Bind failed: ${state.explicitBindFailureReason} - camera unbound.",
                color = Color.White,
                modifier = Modifier.testTag("physical_camera_experiment_failed_banner"),
            )
            Text(
                text = buildPhysicalCameraExperimentReportText(state),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("physical_camera_experiment_report"),
            )
            Text(
                "Retry",
                color = Color.Cyan,
                modifier = Modifier.testTag("physical_camera_experiment_retry").clickable { onRetry() },
            )
            Text(
                "Back to candidates",
                color = Color.Cyan,
                modifier = Modifier.testTag("physical_camera_experiment_back_to_candidates").clickable { onBackToCandidates() },
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val selector = remember(physicalCameraId) { explicitPhysicalCameraSelector(physicalCameraId) }
        // The complete resolution request — dimensions plus the EXPLICIT family the selection band
        // assigned (P1 fix) — never a bare Size the bind would have to re-infer a family from.
        val analysisResolutionOverride =
            remember(
                state.requestedAnalysisResolutionWidthPx,
                state.requestedAnalysisResolutionHeightPx,
                state.requestedAnalysisResolutionFamily,
            ) {
                state.requestedAnalysisResolutionWidthPx?.let { width ->
                    state.requestedAnalysisResolutionHeightPx?.let { height ->
                        state.requestedAnalysisResolutionFamily?.let { family ->
                            AnalysisResolutionRequest(widthPx = width, heightPx = height, family = family)
                        }
                    }
                }
            }
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraSelectorOverride = selector,
            analysisResolutionOverride = analysisResolutionOverride,
            onCameraInfo = { cameraInfo: CameraInfo ->
                // Dual-basis slice: capture BOTH identity halves from the same bound CameraInfo -
                // the physical verification and the opened logical parent's own snapshot - plus the
                // zoom the camera actually reports at this moment (evidence, never a guess).
                val dualBinding = resolveDualBasisBindingFromCameraInfo(cameraInfo, physicalCameraId, context)
                val observedZoom = cameraInfo.zoomState.value?.zoomRatio
                onUpdateSession(attemptId) {
                    it.reduceDualBasisBindingResolved(
                        attemptId = attemptId,
                        dualBinding = dualBinding,
                        zoomTargetRatio = EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO,
                        observedZoomRatio = observedZoom,
                    )
                }
            },
            onExplicitBindFailure = { reason ->
                onUpdateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, reason) }
            },
            onFrameMetadata = { frame ->
                onUpdateSession(attemptId) { it.reduceFrame(attemptId, frame) }
            },
        )

        // Freeze/export (task §16's device workflow): freezing pins the displayed/exported state to
        // one captured moment (the camera keeps running underneath); Copy/Share always use the frozen
        // state when present, so an export can never mix two moments. Keyed to this attempt - a new
        // generation never inherits a stale frozen snapshot.
        var frozenState by remember(attemptId) { mutableStateOf<ExperimentSessionState?>(null) }
        val displayedState = frozenState ?: state
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .background(Color(0x99000000))
                    .padding(12.dp),
        ) {
            Text(
                text = if (frozenState == null) "Freeze" else "Resume live",
                color = Color.Cyan,
                modifier =
                    Modifier.testTag("physical_camera_experiment_freeze").clickable {
                        frozenState = if (frozenState == null) state else null
                    },
            )
            Text(
                text = "Copy report",
                color = Color.Cyan,
                modifier =
                    Modifier.testTag("physical_camera_experiment_copy").clickable {
                        dev.pointtosky.mobile.ar.copyCamDiagnosticTextToClipboard(
                            context,
                            "CAM-2c dual-basis experiment report",
                            buildPhysicalCameraExperimentReportText(displayedState),
                        )
                    },
            )
            Text(
                text = "Share JSON",
                color = Color.Cyan,
                modifier =
                    Modifier.testTag("physical_camera_experiment_share_json").clickable {
                        dev.pointtosky.mobile.ar.shareCamDiagnosticText(
                            context,
                            "CAM-2c dual-basis experiment JSON",
                            buildPhysicalCameraExperimentJson(displayedState, System.currentTimeMillis()),
                        )
                    },
            )
            Text(
                text = buildPhysicalCameraExperimentReportText(displayedState),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("physical_camera_experiment_report"),
            )
        }
    }
}

/**
 * Deterministic, plain-text report of the current experiment [session] (`null` means no candidate has
 * been selected yet) - the same field set a device validation run (task §10) needs: attempt id,
 * selected logical/physical IDs, binding method/source, provenance status, transform source-domain
 * proof, and the published buffer-space `K` or the exact typed block. Directly testable with a plain
 * [ExperimentSessionState] value - no private wrapper type stands between this function and its input,
 * so a test can construct any binding/frame/failure combination and assert the exact rendered text.
 *
 * **Frame and binding are independent fields (fix for a runtime correctness gap - task §1).**
 * [ExperimentSessionState.latestFrame] and [ExperimentSessionState.bindingResolution] are reported
 * independently, in whichever order they actually became available - `latestFrame` can read a real
 * frame size while `cam2cResult` still reads "awaiting frame" (binding not yet resolved), and vice
 * versa; neither ever regresses or is erased once set (see [ExperimentSessionState]'s own KDoc).
 *
 * **Fix for a units defect:** a prior revision printed `CameraIntrinsics.focalLengthMm` (a physical,
 * millimetre-space quantity) labelled as `"K: fx="` — conflating it with the buffer-space pixel
 * quantity `CameraCalibrationDiagnostics.bufferFxPx`. `fxPx`/`fyPx`/`cxPx`/`cyPx` below are always the
 * real buffer-space `K` (`CameraCalibrationDiagnostics.bufferFxPx`/`bufferFyPx`/`bufferCxPx`/`bufferCyPx`);
 * `focalLengthMm` is printed separately, explicitly labelled in millimetres, never conflated with the
 * pixel-space fields.
 */
internal fun buildPhysicalCameraExperimentReportText(session: ExperimentSessionState?): String =
    buildString {
        appendLine("CAM-2c PHYSICAL CAMERA BINDING EXPERIMENT")
        if (session == null) {
            appendLine("status=SELECTING_CANDIDATE")
            return@buildString
        }
        appendLine("requestedPhysicalCameraId=${session.physicalCameraId}")
        appendLine("attemptId=${session.attemptId}")
        if (session.isTerminallyFailed) {
            appendLine("status=EXPLICIT_BIND_FAILED")
            appendLine("reason=${session.explicitBindFailureReason}")
            return@buildString
        }
        when (val binding = session.bindingResolution) {
            null -> appendLine("status=BINDING")
            else -> {
                appendLine("status=BOUND")
                appendLine("binding=${binding::class.simpleName}")
                when (binding) {
                    is PhysicalCameraBindingResolution.Bound -> {
                        // logicalCameraId is nullable and never fabricated - distinguish "known" from
                        // "unavailable" explicitly, never silently substitute the physical ID.
                        appendLine(
                            "selectedLogicalCameraId=" +
                                (binding.provenance.logicalCameraId?.let { "known($it)" } ?: "unavailable"),
                        )
                        appendLine("selectedPhysicalCameraId=known(${binding.provenance.physicalCameraId})")
                        appendLine("bindingMethod=${binding.provenance.bindingMethod}")
                        appendLine("bindingSource=${binding.provenance.bindingSource}")
                        appendLine("provenanceConfidence=${binding.provenance.confidence}")
                    }
                    is PhysicalCameraBindingResolution.PhysicalCameraBindingUnavailable ->
                        appendLine("bindingUnavailableReason=${binding.reason}")
                    PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified ->
                        appendLine("identityUnverified=true")
                    is PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch ->
                        appendLine(
                            "characteristicsMismatch expected=${binding.expectedPhysicalCameraId} " +
                                "actual=${binding.actualCameraId ?: "unavailable"}",
                        )
                }
            }
        }
        appendLine("latestFrame=${session.latestFrame?.let { "${it.bufferWidthPx}x${it.bufferHeightPx}" } ?: "none yet"}")
        append(formatCam2cResultLines(session.cam2cResult))
        // Dual-basis slice: session identity, matrix stability, the opened logical camera's own
        // snapshot, both labelled basis assessments, and the explicit safety state (task §12/§13).
        append(formatDualBasisReportLines(session))
    }

/**
 * Renders [cam2cResult] (extracted from [buildPhysicalCameraExperimentReportText] so it is directly
 * unit-testable without needing to construct the private `ExperimentPhase` this file otherwise wraps
 * it in). Every line is `appendLine`-terminated, so callers can `append(...)` the result directly.
 *
 * **Fix for a units defect:** always prints the real buffer-space `K`
 * (`CameraCalibrationDiagnostics.bufferFxPx`/`bufferFyPx`/`bufferCxPx`/`bufferCyPx`) under `fxPx`/
 * `fyPx`/`cxPx`/`cyPx` - never `CameraIntrinsics.focalLengthMm` (a physical, millimetre-space
 * quantity) mislabelled as `fx`. `focalLengthMm` is printed on its own, explicitly-labelled line.
 */
internal fun formatCam2cResultLines(cam2cResult: Cam2cPhysicalCameraResolution?): String =
    buildString {
        when (cam2cResult) {
            null -> appendLine("cam2cResult=awaiting frame")
            is Cam2cPhysicalCameraResolution.Resolved -> {
                appendLine("cam2cResult=RESOLVED")
                val diag = cam2cResult.diagnostics
                appendLine(
                    "K (buffer-space px): fxPx=${diag.bufferFxPx} fyPx=${diag.bufferFyPx} " +
                        "cxPx=${diag.bufferCxPx} cyPx=${diag.bufferCyPx}",
                )
                appendLine("focalLengthMm=${diag.focalLengthMm}")
                appendLine(
                    "reference=${cam2cResult.intrinsics.reference} quality=${cam2cResult.intrinsics.quality} " +
                        "source=${cam2cResult.intrinsics.source}",
                )
            }
            is Cam2cPhysicalCameraResolution.BindingFailure ->
                appendLine("cam2cResult=BINDING_FAILURE(${cam2cResult.binding::class.simpleName})")
            is Cam2cPhysicalCameraResolution.DomainNotProven ->
                appendLine(
                    "cam2cResult=DOMAIN_NOT_PROVEN(${cam2cResult.proof::class.simpleName}) - " +
                        "physical-camera identity alone never publishes calibrated AnalysisBuffer intrinsics",
                )
            is Cam2cPhysicalCameraResolution.IntrinsicsFailure ->
                appendLine("cam2cResult=INTRINSICS_FAILURE(${cam2cResult.attempt::class.simpleName})")
        }
    }
