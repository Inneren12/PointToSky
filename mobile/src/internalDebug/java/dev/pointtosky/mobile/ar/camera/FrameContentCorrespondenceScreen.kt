package dev.pointtosky.mobile.ar.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pointtosky.mobile.ar.EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO
import dev.pointtosky.mobile.ar.copyCamDiagnosticTextToClipboard
import dev.pointtosky.mobile.ar.shareCamDiagnosticText

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §7). Standalone screen —
 * same "own the whole camera session" reasoning as [PhysicalCameraBindingExperimentScreen] — optimized
 * for the Pixel 9 device workflow: physical camera 3 listed first, 640x480/1280x720 resolution buttons,
 * Freeze, Copy report, Share JSON, target-placement label, distance label, live detected-point count and
 * per-hypothesis RMS.
 */
class FrameContentCorrespondenceExperimentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FrameContentCorrespondenceScreen() }
    }
}

internal val FRAME_CONTENT_EXPERIMENT_ACTIVITY_CLASS_NAME: String =
    FrameContentCorrespondenceExperimentActivity::class.java.name

/** The one function that ever constructs the launch [Intent] for [FrameContentCorrespondenceExperimentActivity]
 * — mirrors [buildPhysicalCameraBindingExperimentIntent]'s own testability rationale exactly. */
internal fun buildFrameContentCorrespondenceExperimentIntent(context: Context): Intent =
    Intent(context, FrameContentCorrespondenceExperimentActivity::class.java)

private val FIXED_RESOLUTION_CANDIDATES =
    listOf(
        AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3),
        AnalysisResolutionCandidate(1280, 720, AnalysisResolutionFamily.NEAR_16_9),
    )

@Composable
internal fun FrameContentCorrespondenceScreen() {
    val context = LocalContext.current
    var hasCameraPermission by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasCameraPermission = granted }

    val topology = remember { buildCameraTopologyReport(context, boundCameraInfo = null) }
    // Task §7: physical camera 3 first, when declared; every other candidate follows in sorted order.
    val candidates =
        remember(topology) {
            val declared = topology.entries.flatMap { it.declaredPhysicalCameraIds }.distinct().sorted()
            declared.sortedBy { if (it == "3") 0 else 1 }
        }

    var uiModel by remember { mutableStateOf(FrameContentCorrespondenceUiModel()) }
    var pendingCandidate by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        if (!hasCameraPermission) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Camera permission required", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.testTag(TAG_REQUEST_PERMISSION)) {
                    Text("Grant camera permission")
                }
            }
            return@Surface
        }

        val session = uiModel.session
        if (session == null) {
            val candidate = pendingCandidate
            if (candidate == null) {
                CandidatePicker(candidates) { pendingCandidate = it }
            } else {
                ResolutionPickerFixed(
                    onSelected = { resolution ->
                        uiModel = uiModel.startAttempt(candidate, resolution)
                        pendingCandidate = null
                    },
                    onBack = { pendingCandidate = null },
                )
            }
            return@Surface
        }

        key(session.attemptId) {
            FrameContentCorrespondenceSession(
                state = session,
                onUpdateSession = { attemptId, reducer -> uiModel = uiModel.updateSession(attemptId, reducer) },
                onRetry = { uiModel = uiModel.retry() },
                onBackToCandidates = { uiModel = uiModel.backToCandidates() },
            )
        }
    }
}

internal const val TAG_REQUEST_PERMISSION = "frame_content_experiment_request_permission"
internal const val TAG_CANDIDATE_PREFIX = "frame_content_experiment_candidate_"
internal const val TAG_RESOLUTION_PREFIX = "frame_content_experiment_resolution_"
internal const val TAG_FAILED_BANNER = "frame_content_experiment_failed_banner"
internal const val TAG_RETRY = "frame_content_experiment_retry"
internal const val TAG_BACK = "frame_content_experiment_back_to_candidates"
internal const val TAG_ACTION_HEADER = "frame_content_experiment_action_header"
internal const val TAG_FREEZE = "frame_content_experiment_freeze"
internal const val TAG_COPY = "frame_content_experiment_copy"
internal const val TAG_SHARE_JSON = "frame_content_experiment_share_json"
internal const val TAG_REPORT_SCROLL = "frame_content_experiment_report_scroll"
internal const val TAG_REPORT = "frame_content_experiment_report"
internal const val TAG_PLACEMENT_PREFIX = "frame_content_experiment_placement_"
internal const val TAG_PLACEMENT_ROW = "frame_content_experiment_placement_row"
internal const val TAG_DISTANCE_INPUT = "frame_content_experiment_distance_input"
internal const val TAG_LIVE_SUMMARY = "frame_content_experiment_live_summary"
internal const val TAG_POSE_ANCHOR_BANNER = "frame_content_experiment_pose_anchor_banner"
internal const val TAG_VERDICT_BANNER = "frame_content_experiment_verdict_banner"

@Composable
private fun CandidatePicker(
    candidates: List<String>,
    onSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select physical camera", color = Color.White)
        LazyColumn {
            items(candidates) { candidate ->
                Button(
                    onClick = { onSelected(candidate) },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_CANDIDATE_PREFIX + candidate),
                ) {
                    Text("Physical camera $candidate")
                }
            }
        }
    }
}

@Composable
private fun ResolutionPickerFixed(
    onSelected: (AnalysisResolutionCandidate) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select analysis resolution", color = Color.White)
        FIXED_RESOLUTION_CANDIDATES.forEach { candidate ->
            Button(
                onClick = { onSelected(candidate) },
                modifier = Modifier.fillMaxWidth().testTag(TAG_RESOLUTION_PREFIX + candidate.label()),
            ) {
                Text(candidate.label())
            }
        }
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
internal fun FrameContentCorrespondenceSession(
    state: FrameContentExperimentSessionState,
    onUpdateSession: (Long, (FrameContentExperimentSessionState) -> FrameContentExperimentSessionState) -> Unit,
    onRetry: () -> Unit,
    onBackToCandidates: () -> Unit,
) {
    if (state.isTerminallyFailed) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag("frame_content_experiment_terminal_scroll"),
        ) {
            Text("Bind failed: ${state.explicitBindFailureReason}", color = Color.Red, modifier = Modifier.testTag(TAG_FAILED_BANNER))
            Button(onClick = onRetry, modifier = Modifier.testTag(TAG_RETRY)) { Text("Retry") }
            Button(onClick = onBackToCandidates, modifier = Modifier.testTag(TAG_BACK)) { Text("Back") }
        }
        return
    }

    val attemptId = state.attemptId
    val requestedResolution =
        if (state.requestedAnalysisResolutionWidthPx != null && state.requestedAnalysisResolutionHeightPx != null) {
            AnalysisResolutionRequest(
                widthPx = state.requestedAnalysisResolutionWidthPx,
                heightPx = state.requestedAnalysisResolutionHeightPx,
                family = state.requestedAnalysisResolutionFamily ?: AnalysisResolutionFamily.NEAR_4_3,
            )
        } else {
            null
        }
    val capturedAtEpochMillisProvider = remember { { System.currentTimeMillis() } }

    Box(modifier = Modifier.fillMaxSize()) {
        FrameContentCameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraSelector = explicitPhysicalCameraSelector(state.physicalCameraId),
            analysisResolutionOverride = requestedResolution,
            targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
            detectionTolerances = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES,
            onCameraInfo = { cameraInfo ->
                val binding = resolveDualBasisBindingFromCameraInfo(cameraInfo, state.physicalCameraId, null)
                onUpdateSession(attemptId) {
                    it.reduceBindingResolved(
                        attemptId,
                        binding,
                        EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO,
                        cameraInfo.zoomState.value?.zoomRatio,
                        capturedAtEpochMillisProvider(),
                    )
                }
            },
            onExplicitBindFailure = { reason -> onUpdateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, reason) } },
            onFrame = { frame, detection ->
                onUpdateSession(attemptId) { it.reduceFrame(attemptId, frame, detection, capturedAtEpochMillisProvider()) }
            },
        )
        FrameContentExperimentLiveOverlay(
            state = state,
            onUpdateSession = onUpdateSession,
        )
    }
}

@Composable
private fun FrameContentExperimentLiveOverlay(
    state: FrameContentExperimentSessionState,
    onUpdateSession: (Long, (FrameContentExperimentSessionState) -> FrameContentExperimentSessionState) -> Unit,
) {
    var frozenState by remember(state.attemptId) { mutableStateOf<FrameContentExperimentSessionState?>(null) }
    val displayedState = frozenState ?: state
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
    ) {
        Column(modifier = Modifier.testTag(TAG_ACTION_HEADER)) {
            val snapshot = displayedState.latestSnapshot
            Text(
                "physicalId=${displayedState.physicalCameraId} attemptId=${displayedState.attemptId} " +
                    "frames=${displayedState.framesObserved} detectedPoints=${snapshot?.detectedPoints?.size ?: 0}",
                color = Color.White,
                modifier = Modifier.testTag(TAG_LIVE_SUMMARY),
            )
            // Task §8: pose anchor and the conditional verdict must be prominent, not buried below the
            // scrollable report — this is the one place a device operator must never miss the
            // "not an independent path-winner" caveat.
            Text(
                "poseReferenceHypothesis=${snapshot?.poseReferenceHypothesis ?: FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS} " +
                    "(MEASUREMENT-ONLY — residuals are CONDITIONAL_ON_PHYSICAL_ANCHORED_POSE, never an " +
                    "independent path-winner verdict)",
                color = Color.Cyan,
                modifier = Modifier.testTag(TAG_POSE_ANCHOR_BANNER),
            )
            if (snapshot != null) {
                Text(
                    snapshot.summariesByHypothesis.entries.joinToString(" | ") { (id, summary) -> "$id rms=${summary.rmsPx}" },
                    color = Color.White,
                )
                Text(
                    "verdict=${snapshot.verdict.verdict}",
                    color = Color.Yellow,
                    modifier = Modifier.testTag(TAG_VERDICT_BANNER),
                )
            }

            // Task §8: five placement controls must never overflow a portrait Pixel 9 screen — a
            // horizontally scrollable row guarantees no clipping regardless of screen width/font scale.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag(TAG_PLACEMENT_ROW),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TargetPlacementLabel.values().forEach { label ->
                    Button(
                        onClick = { onUpdateSession(state.attemptId) { it.reduceTargetPlacementLabel(state.attemptId, label) } },
                        modifier = Modifier.testTag(TAG_PLACEMENT_PREFIX + label.name),
                    ) {
                        Text(label.name, color = if (state.targetPlacementLabel == label) Color.Yellow else Color.White)
                    }
                }
            }
            OutlinedTextField(
                value = state.distanceLabelMm?.toString() ?: "",
                onValueChange = { text ->
                    onUpdateSession(state.attemptId) { it.reduceDistanceLabel(state.attemptId, text.toDoubleOrNull()) }
                },
                label = { Text("distance mm") },
                modifier = Modifier.testTag(TAG_DISTANCE_INPUT),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { frozenState = if (frozenState == null) displayedState else null }, modifier = Modifier.testTag(TAG_FREEZE)) {
                    Text(if (frozenState == null) "Freeze" else "Resume live")
                }
                // Task §8: disabled (not a silent no-op) whenever there is no snapshot to export yet.
                Button(
                    onClick = {
                        val snap = displayedState.latestSnapshot
                        if (snap != null) {
                            copyCamDiagnosticTextToClipboard(context, "CAM-2c frame content", buildFrameContentCorrespondenceReportText(snap))
                        }
                    },
                    enabled = displayedState.latestSnapshot != null,
                    modifier = Modifier.testTag(TAG_COPY),
                ) { Text("Copy report") }
                Button(
                    onClick = {
                        val snap = displayedState.latestSnapshot
                        if (snap != null) {
                            shareCamDiagnosticText(context, "CAM-2c frame content JSON", buildFrameContentCorrespondenceJson(snap))
                        }
                    },
                    enabled = displayedState.latestSnapshot != null,
                    modifier = Modifier.testTag(TAG_SHARE_JSON),
                ) { Text("Share JSON") }
            }
        }

        val reportText = displayedState.latestSnapshot?.let { buildFrameContentCorrespondenceReportText(it) } ?: "awaiting frame/binding"
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag(TAG_REPORT_SCROLL),
        ) {
            SelectionContainer {
                Text(reportText, color = Color.White, fontFamily = MaterialTheme.typography.bodySmall.fontFamily, modifier = Modifier.testTag(TAG_REPORT))
            }
        }
    }
}
