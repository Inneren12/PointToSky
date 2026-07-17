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
 * what it actually launches) — `ExperimentLaunchIntentTest`/`ExperimentLaunchIntentUiTest`
 * (`androidTestInternalDebug`) assert its `component.className` resolves via `PackageManager` and that
 * the underlying `Activity` stays `exported="false"`. [PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME]
 * guards against the class name itself drifting from the manifest's own declared `android:name`.
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
 * (`CamDiagnosticFullReportDialog.kt`) and `PhysicalCameraExperimentLaunchTest`/
 * `ExperimentLaunchIntentUiTest` (`androidTestInternalDebug`) call this exact function - never a second,
 * independently hand-written `Intent(...)` construction that could silently drift from it.
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
            CandidatePicker(candidates = candidates, onSelected = { uiModel = uiModel.startAttempt(it) })
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (!state.isTerminallyFailed) {
            val selector = remember(physicalCameraId) { explicitPhysicalCameraSelector(physicalCameraId) }
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraSelectorOverride = selector,
                onCameraInfo = { cameraInfo: CameraInfo ->
                    val binding = resolvePhysicalCameraBindingFromCameraInfo(cameraInfo, physicalCameraId, context)
                    onUpdateSession(attemptId) { it.reduceCameraInfoResolved(attemptId, binding) }
                },
                onExplicitBindFailure = { reason ->
                    onUpdateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, reason) }
                },
                onFrameMetadata = { frame ->
                    onUpdateSession(attemptId) { it.reduceFrame(attemptId, frame) }
                },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Bind failed: ${state.explicitBindFailureReason} - camera unbound.",
                    color = Color.White,
                    modifier = Modifier.testTag("physical_camera_experiment_failed_banner"),
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
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .background(Color(0x99000000))
                    .padding(12.dp)
                    .testTag("physical_camera_experiment_report"),
        ) {
            Text(
                text = buildPhysicalCameraExperimentReportText(state),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
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
