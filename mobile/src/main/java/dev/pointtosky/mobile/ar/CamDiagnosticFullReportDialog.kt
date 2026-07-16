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
import dev.pointtosky.mobile.ar.camera.CamDiagnosticLiveness
import dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticJson
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText
import dev.pointtosky.mobile.ar.camera.formatCapturedAt

/** [androidx.compose.ui.platform.testTag] for the full-screen diagnostics dialog's own root container. */
const val CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG = "cam_diagnostic_full_report_dialog"

/** [androidx.compose.ui.platform.testTag] for the scrollable region holding the complete report text. */
const val CAM_DIAGNOSTIC_FULL_REPORT_SCROLL_TEST_TAG = "cam_diagnostic_full_report_scroll"

/** [androidx.compose.ui.platform.testTag] for the single [Text] carrying the complete, deterministic report. */
const val CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG = "cam_diagnostic_full_report_text"

/** [androidx.compose.ui.platform.testTag] for the `Diagnostics: LIVE`/`FROZEN` banner (captured/session line). */
const val CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG = "cam_diagnostic_liveness_banner"

/** [androidx.compose.ui.platform.testTag] for the "Freeze snapshot"/"Resume live" toggle. */
const val CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG = "cam_diagnostic_freeze_resume_button"

/** [androidx.compose.ui.platform.testTag] for "Copy all". */
const val CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG = "cam_diagnostic_copy_all_button"

/** [androidx.compose.ui.platform.testTag] for "Share log". */
const val CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG = "cam_diagnostic_share_log_button"

/** [androidx.compose.ui.platform.testTag] for "Share JSON". */
const val CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG = "cam_diagnostic_share_json_button"

/** [androidx.compose.ui.platform.testTag] for "Close". */
const val CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG = "cam_diagnostic_close_button"

/** A small, tappable, monospace-free text "button" matching this HUD's existing translucent-chip
 * visual language (see `CamDiagnosticHud.kt`'s header row) - never a Material3 [androidx.compose.material3.Button],
 * so the full diagnostics surface stays visually consistent with the rest of the debug HUD. */
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
 * The full CAM diagnostics surface (diagnostic export/freeze fix §2/§3/§4/§5/§6) - a full-screen
 * [Dialog] (its own window, so it can never be permanently clipped by the small top HUD column, and
 * closing it - [onDismissRequest] - never leaves any part of it obscuring the reticle afterward).
 * Scrollable, with every action (Freeze/Resume, Copy all, Share log, Share JSON, Close) pinned above
 * the scrollable body so they stay reachable regardless of scroll position or font scale.
 *
 * **Freeze/Resume.** [liveSnapshot] is expected to be a fresh value on every recomposition (the
 * caller rebuilds it from live coordinator/provider state each time - see `CamDiagnosticTopPanels`).
 * While [CamDiagnosticLiveness.LIVE], the displayed report always reflects [liveSnapshot] as of the
 * latest recomposition - camera/projection keep running normally underneath, entirely unaffected.
 * "Freeze snapshot" captures the *current* [liveSnapshot] into local [remember]ed state; from then on
 * every recomposition (however often [liveSnapshot] itself changes) redisplays that one frozen value
 * unchanged, until "Resume live" clears it. No CAM-2a projection math, camera binding, or renderer
 * state is touched by any of this - purely a display freeze.
 */
@Composable
fun CamDiagnosticFullReportDialog(
    liveSnapshot: CamDiagnosticSnapshot,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var frozenSnapshot by remember { mutableStateOf<CamDiagnosticSnapshot?>(null) }
    val liveness = if (frozenSnapshot != null) CamDiagnosticLiveness.FROZEN else CamDiagnosticLiveness.LIVE
    val displayedSnapshot = frozenSnapshot ?: liveSnapshot
    val context = LocalContext.current
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
                        onClick = { copyCamDiagnosticTextToClipboard(context, "PointToSky CAM diagnostics", reportText) },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Share log",
                        onClick = { shareCamDiagnosticText(context, "PointToSky CAM diagnostics", reportText) },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG),
                    )
                    CamDiagnosticActionChip(
                        label = "Share JSON",
                        onClick = {
                            val jsonText = buildCamDiagnosticJson(displayedSnapshot, liveness)
                            shareCamDiagnosticText(context, "PointToSky CAM diagnostics (JSON)", jsonText)
                        },
                        modifier = Modifier.testTag(CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG),
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
                }
            }
        }
    }
}
