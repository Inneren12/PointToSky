package dev.pointtosky.mobile.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Intent
import androidx.camera.core.CameraInfo
import dev.pointtosky.mobile.ar.camera.CamDiagnosticLiveness
import dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentActivity
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticJson
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText
import dev.pointtosky.mobile.ar.camera.buildCameraTopologyJson
import dev.pointtosky.mobile.ar.camera.buildCameraTopologyReport
import dev.pointtosky.mobile.ar.camera.buildCameraTopologyReportText
import dev.pointtosky.mobile.ar.camera.formatCapturedAt

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the full-screen diagnostics
 * dialog's own root container. */
const val CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG = "cam_diagnostic_full_report_dialog"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the scrollable region holding the
 * complete report text. */
const val CAM_DIAGNOSTIC_FULL_REPORT_SCROLL_TEST_TAG = "cam_diagnostic_full_report_scroll"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the single [Text] carrying the
 * complete, deterministic report. */
const val CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG = "cam_diagnostic_full_report_text"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the trailing marker node after the
 * report text (test-hardening fix §4) - a scroll test must reach *this* node, not merely assert the
 * giant report `Text` itself exists, to prove the scrollable container actually reaches its end. */
const val CAM_DIAGNOSTIC_REPORT_END_TEST_TAG = "cam_diagnostic_report_end"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the `Diagnostics: LIVE`/`FROZEN`
 * banner (captured/session line). */
const val CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG = "cam_diagnostic_liveness_banner"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the horizontally scrollable action
 * row (test-hardening fix §4) - large-font-scale tests scroll this row to reach each action rather than
 * merely asserting the action exists in the semantics tree. */
const val CAM_DIAGNOSTIC_ACTIONS_ROW_TEST_TAG = "cam_diagnostic_actions_row"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for the "Freeze snapshot"/"Resume live"
 * toggle. */
const val CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG = "cam_diagnostic_freeze_resume_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Copy all". */
const val CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG = "cam_diagnostic_copy_all_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Share log". */
const val CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG = "cam_diagnostic_share_log_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Share JSON". */
const val CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG = "cam_diagnostic_share_json_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Close". */
const val CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG = "cam_diagnostic_close_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Share topology" (CAM-2c recon,
 * task §3). */
const val CAM_DIAGNOSTIC_SHARE_TOPOLOGY_BUTTON_TEST_TAG = "cam_diagnostic_share_topology_button"

/** `internalDebug`-only. [androidx.compose.ui.platform.testTag] for "Open physical-camera experiment"
 * (CAM-2c physical-camera provenance experiment fix — reachability defect fix: this is the actual,
 * verified in-app launch path for [PhysicalCameraBindingExperimentActivity], which is
 * `android:exported="false"` and therefore not reliably reachable via `adb shell am start`). */
const val CAM_DIAGNOSTIC_OPEN_PHYSICAL_CAMERA_EXPERIMENT_BUTTON_TEST_TAG = "cam_diagnostic_open_physical_camera_experiment_button"

/** `internalDebug`-only. A small, tappable, monospace-free text "button" matching this HUD's existing
 * translucent-chip visual language - never a Material3 [androidx.compose.material3.Button]. */
@Composable
private fun CamDiagnosticActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier =
            modifier
                .clickable(onClick = onClick)
                .background(color = Color(0xFF1B4B66), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * `internalDebug`-only. The full CAM diagnostics surface - a full-screen [Dialog] (its own window, so
 * it can never be permanently clipped by the small top HUD column, and closing it - [onDismissRequest]
 * - never leaves any part of it obscuring the reticle afterward). Scrollable, with every action pinned
 * above the scrollable body so they stay reachable regardless of scroll position or font scale.
 *
 * **Freeze/Resume.** [liveSnapshot] is expected to be a fresh value whenever the caller's own live
 * diagnostic input actually changes (see `CamDiagnosticsExportUiImpl`'s own throttled `remember` -
 * never a raw `System.currentTimeMillis()` read on every arbitrary recomposition of this dialog). While
 * [CamDiagnosticLiveness.LIVE], the displayed report always reflects [liveSnapshot] as of the latest
 * recomposition - camera/projection keep running normally underneath, entirely unaffected. "Freeze
 * snapshot" captures the *current* [liveSnapshot] into local [remember]ed state; from then on every
 * recomposition (however often [liveSnapshot] itself changes) redisplays that one frozen value
 * unchanged, until "Resume live" clears it - which then immediately reflects whatever [liveSnapshot]
 * value the caller is passing *right now*, not merely the next future update. No CAM-2a projection
 * math, camera binding, or renderer state is touched by any of this - purely a display freeze.
 */
@Composable
fun CamDiagnosticFullReportDialog(
    liveSnapshot: CamDiagnosticSnapshot,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    actions: CamDiagnosticActions? = null,
    boundCameraInfo: CameraInfo? = null,
) {
    var frozenSnapshot by remember { mutableStateOf<CamDiagnosticSnapshot?>(null) }
    val liveness = if (frozenSnapshot != null) CamDiagnosticLiveness.FROZEN else CamDiagnosticLiveness.LIVE
    val displayedSnapshot = frozenSnapshot ?: liveSnapshot
    val context = LocalContext.current
    // Defaults to the real Android clipboard/chooser implementation; tests inject a recording fake here
    // instead (share-wiring test fix) so payload wiring is asserted directly rather than only inferred
    // from buildCamDiagnosticShareIntent()'s own independent unit test.
    val effectiveActions = actions ?: remember(context) { AndroidCamDiagnosticActions(context) }
    val reportText = remember(displayedSnapshot, liveness) { buildCamDiagnosticReportText(displayedSnapshot, liveness) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG),
            color = Color(0xFF0A0A0A),
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "CAM diagnostics",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    CamDiagnosticActionChip(
                        label = "Close",
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG),
                    )
                }

                Text(
                    text =
                        buildString {
                            append("Diagnostics: ${liveness.name}\n")
                            append("captured: ${formatCapturedAt(displayedSnapshot.capturedAtEpochMillis)}\n")
                            append("session: ${displayedSnapshot.sessionId}")
                        },
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(CAM_DIAGNOSTIC_ACTIONS_ROW_TEST_TAG)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CamDiagnosticActionChip(
                        label = if (liveness == CamDiagnosticLiveness.FROZEN) "Resume live" else "Freeze snapshot",
                        onClick = { frozenSnapshot = if (frozenSnapshot != null) null else liveSnapshot },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Copy all",
                        onClick = { effectiveActions.copy("PointToSky CAM diagnostics", reportText) },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Share log",
                        onClick = { effectiveActions.share("PointToSky CAM diagnostics", reportText) },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Share JSON",
                        onClick = {
                            val jsonText = buildCamDiagnosticJson(displayedSnapshot, liveness)
                            effectiveActions.share("PointToSky CAM diagnostics (JSON)", jsonText)
                        },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Share topology",
                        onClick = {
                            val topology = buildCameraTopologyReport(context, boundCameraInfo)
                            val text =
                                buildCameraTopologyReportText(topology) + "\n\n" + buildCameraTopologyJson(topology)
                            effectiveActions.share("PointToSky camera topology (CAM-2c recon)", text)
                        },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_SHARE_TOPOLOGY_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Open physical-camera experiment",
                        onClick = {
                            // Same-app, same-process Intent - always permitted regardless of the
                            // target Activity's exported="false" (that flag restricts only other
                            // apps/processes from starting this component). This is the one verified
                            // launch path for PhysicalCameraBindingExperimentActivity; do not launch
                            // it via `adb shell am start`, which cannot reliably reach a
                            // non-exported component.
                            context.startActivity(Intent(context, PhysicalCameraBindingExperimentActivity::class.java))
                        },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_OPEN_PHYSICAL_CAMERA_EXPERIMENT_BUTTON_TEST_TAG),
                    )
                }

                HorizontalDivider(color = Color(0x33FFFFFF))

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag(CAM_DIAGNOSTIC_FULL_REPORT_SCROLL_TEST_TAG)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                ) {
                    Text(
                        text = reportText,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG),
                    )
                    Text(
                        text = "END OF CAM DIAGNOSTICS",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        modifier =
                            Modifier
                                .testTag(CAM_DIAGNOSTIC_REPORT_END_TEST_TAG)
                                .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
