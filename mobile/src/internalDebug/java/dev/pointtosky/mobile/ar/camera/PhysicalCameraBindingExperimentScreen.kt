package dev.pointtosky.mobile.ar.camera

import android.Manifest
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
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.mobile.ar.CameraPreview

/**
 * CAM-2c physical-camera provenance experiment (`internalDebug` only, task §4). Standalone screen -
 * not layered onto the live AR renderer's own [dev.pointtosky.mobile.ar.CameraPreview] binding, since
 * most devices only support one open camera session at a time and this experiment needs full control
 * over the [androidx.camera.core.CameraSelector] used for that one session. Launch via
 * `adb shell am start -n <applicationId>/dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentActivity`
 * against a debuggable `internalDebug` build (not exported - see `mobile/src/internalDebug/AndroidManifest.xml`).
 *
 * Flow: enumerate the rear logical camera's declared physical candidates (never inferred from ID
 * ordering - task §4) &#8594; user taps a candidate &#8594; [CameraPreview] binds with an explicit
 * `CameraSelector.Builder().setPhysicalCameraId(candidate)` selector (task requires Preview and
 * ImageAnalysis share one coherent binding - guaranteed here since both are bound in the same
 * `bindToLifecycle` call inside [CameraPreview]) &#8594; the bound [CameraInfo] is verified
 * ([resolvePhysicalCameraBindingFromCameraInfo]) &#8594; once a frame's buffer dimensions and
 * sensor-to-buffer transform are known, [resolveCam2cForExplicitPhysicalCamera] is attempted and its
 * exact typed outcome is displayed - never silently downgraded to "it worked" or "it's blocked".
 */
class PhysicalCameraBindingExperimentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhysicalCameraBindingExperimentScreen()
        }
    }
}

private sealed interface ExperimentPhase {
    data object SelectingCandidate : ExperimentPhase

    data class Binding(val physicalCameraId: String) : ExperimentPhase

    data class Bound(
        val physicalCameraId: String,
        val binding: PhysicalCameraBindingResolution,
        val latestFrame: CameraFrameMetadata?,
        val cam2cResult: Cam2cPhysicalCameraResolution?,
    ) : ExperimentPhase

    data class ExplicitBindFailed(val physicalCameraId: String, val reason: String) : ExperimentPhase
}

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
    var phase by remember { mutableStateOf<ExperimentPhase>(ExperimentPhase.SelectingCandidate) }

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

        when (val currentPhase = phase) {
            is ExperimentPhase.SelectingCandidate ->
                CandidatePicker(candidates = candidates, onSelected = { phase = ExperimentPhase.Binding(it) })
            is ExperimentPhase.Binding, is ExperimentPhase.Bound, is ExperimentPhase.ExplicitBindFailed ->
                BindingSession(
                    physicalCameraId =
                        when (currentPhase) {
                            is ExperimentPhase.Binding -> currentPhase.physicalCameraId
                            is ExperimentPhase.Bound -> currentPhase.physicalCameraId
                            is ExperimentPhase.ExplicitBindFailed -> currentPhase.physicalCameraId
                            ExperimentPhase.SelectingCandidate -> error("unreachable")
                        },
                    phase = currentPhase,
                    onPhaseChange = { phase = it },
                )
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

@Composable
private fun BindingSession(
    physicalCameraId: String,
    phase: ExperimentPhase,
    onPhaseChange: (ExperimentPhase) -> Unit,
) {
    val context = LocalContext.current
    val selector = remember(physicalCameraId) { explicitPhysicalCameraSelector(physicalCameraId) }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            cameraSelectorOverride = selector,
            onCameraInfo = { cameraInfo: CameraInfo ->
                val binding = resolvePhysicalCameraBindingFromCameraInfo(cameraInfo, physicalCameraId, context)
                onPhaseChange(
                    ExperimentPhase.Bound(
                        physicalCameraId = physicalCameraId,
                        binding = binding,
                        latestFrame = null,
                        cam2cResult = null,
                    ),
                )
            },
            onExplicitBindFailure = { reason ->
                onPhaseChange(ExperimentPhase.ExplicitBindFailed(physicalCameraId, reason))
            },
            onFrameMetadata = { frame ->
                val current = phase
                if (current is ExperimentPhase.Bound) {
                    val cam2c =
                        resolveCam2cForExplicitPhysicalCamera(
                            binding = current.binding,
                            sensorToBufferTransform = frame.sensorToBufferTransform,
                            bufferWidthPx = frame.bufferWidthPx,
                            bufferHeightPx = frame.bufferHeightPx,
                        )
                    onPhaseChange(current.copy(latestFrame = frame, cam2cResult = cam2c))
                }
            },
        )

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
                text = buildPhysicalCameraExperimentReportText(physicalCameraId, phase),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Deterministic, plain-text report of the current experiment [phase] - the same field set a device
 * validation run (task §10) needs: selected logical/physical IDs, binding method, provenance status,
 * transform source-domain basis, and the published `AnalysisBuffer` K or the exact typed block. */
internal fun buildPhysicalCameraExperimentReportText(
    requestedPhysicalCameraId: String,
    phase: Any?,
): String =
    buildString {
        appendLine("CAM-2c PHYSICAL CAMERA BINDING EXPERIMENT")
        appendLine("requestedPhysicalCameraId=$requestedPhysicalCameraId")
        when (phase) {
            is ExperimentPhase.Binding -> appendLine("status=BINDING")
            is ExperimentPhase.ExplicitBindFailed -> {
                appendLine("status=EXPLICIT_BIND_FAILED")
                appendLine("reason=${phase.reason}")
            }
            is ExperimentPhase.Bound -> {
                appendLine("status=BOUND")
                appendLine("binding=${phase.binding::class.simpleName}")
                when (val binding = phase.binding) {
                    is PhysicalCameraBindingResolution.Bound -> {
                        appendLine("selectedLogicalCameraId=${binding.provenance.logicalCameraId}")
                        appendLine("selectedPhysicalCameraId=${binding.provenance.physicalCameraId}")
                        appendLine("bindingMethod=${binding.provenance.bindingMethod}")
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
                appendLine("latestFrame=${phase.latestFrame?.let { "${it.bufferWidthPx}x${it.bufferHeightPx}" } ?: "none yet"}")
                when (val cam2c = phase.cam2cResult) {
                    null -> appendLine("cam2cResult=awaiting frame")
                    is Cam2cPhysicalCameraResolution.Resolved -> {
                        appendLine("cam2cResult=RESOLVED")
                        appendLine(
                            "K: fx=${cam2c.intrinsics.focalLengthMm ?: "n/a"} " +
                                "source=${cam2c.intrinsics.source} reference=${cam2c.intrinsics.reference} " +
                                "quality=${cam2c.intrinsics.quality}",
                        )
                    }
                    is Cam2cPhysicalCameraResolution.BindingFailure ->
                        appendLine("cam2cResult=BINDING_FAILURE(${cam2c.binding::class.simpleName})")
                    is Cam2cPhysicalCameraResolution.IntrinsicsFailure ->
                        appendLine("cam2cResult=INTRINSICS_FAILURE(${cam2c.attempt::class.simpleName})")
                }
            }
            else -> Unit
        }
    }
