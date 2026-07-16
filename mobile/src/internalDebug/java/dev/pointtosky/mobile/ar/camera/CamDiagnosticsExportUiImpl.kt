package dev.pointtosky.mobile.ar.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialog

/** [androidx.compose.ui.platform.testTag] for the compact CAM diagnostics summary (HUD redesign §6) -
 * the highest-value few-line root-cause summary shown while the HUD's details are expanded, replacing
 * the previous giant per-domain text blocks. `internalDebug`-only, matching [CamDiagnosticsExportUi]'s
 * own gate - never referenced by shared (`main`) test sources, only by
 * `mobile/src/androidTestInternalDebug`. */
const val CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG = "cam_diagnostic_compact_summary"

/** [androidx.compose.ui.platform.testTag] for the "Open diagnostics" affordance that opens
 * [CamDiagnosticFullReportDialog]. `internalDebug`-only, see [CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG]. */
const val CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG = "cam_diagnostic_open_button"

/**
 * The real, `internalDebug`-only implementation of [CamDiagnosticsExportUi] (architecture fix §1) -
 * compiled only into the `internalDebug` variant; see [CamDiagnosticsExportUi]'s own KDoc for why the
 * no-op implementations elsewhere never reference any of this file's clipboard/share/JSON/dialog code.
 */
internal object CamDiagnosticsExportUiProvider : CamDiagnosticsExportUi {
    @Composable
    override fun Content(
        input: CamDiagnosticsExportInput,
        modifier: Modifier,
    ) {
        // Session-scoped ("fresh session -> closed dialog"), matching every other session-keyed debug
        // toggle in this HUD - a new AR/debug session must never inherit a previous session's open
        // full-diagnostics dialog.
        var openDiagnostics by remember(input.sessionId) { mutableStateOf(false) }
        // Timestamp/snapshot throttling (throttled-timestamp fix §6): incremented only when the dialog
        // transitions from closed to open, and used below as one of the `remember` keys driving
        // liveSnapshot's own recomputation - never a bare System.currentTimeMillis() read on every
        // arbitrary recomposition (e.g. an unrelated parent recomposing, or Compose merely re-running
        // this function without any of the diagnostic inputs having changed).
        var openGeneration by remember(input.sessionId) { mutableIntStateOf(0) }

        // Recomputed - and its capturedAtEpochMillis re-stamped - only when the diagnostic input itself
        // changes value, or when "Open diagnostics" bumps openGeneration; never on an unrelated
        // recomposition. See CamDiagnosticFullReportDialog's own KDoc "Freeze/Resume" section for how
        // Resume then immediately reflects this same throttled-but-current value.
        val liveSnapshot =
            remember(
                input.cam2bState,
                input.cameraGeometryState,
                input.cameraGeometryStatusTransitionCount,
                input.cameraGeometryObservedFrameCount,
                input.cameraGeometryReadyBundleCount,
                input.cameraIntrinsicsState,
                input.calibrationDiagnostics,
                openGeneration,
            ) {
                captureCamDiagnosticSnapshot(input = input, capturedAtEpochMillis = System.currentTimeMillis())
            }

        Column(
            modifier =
                modifier
                    .testTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
                    .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = buildCamDiagnosticCompactSummaryText(liveSnapshot),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "▸ Open diagnostics",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .testTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG)
                        .clickable {
                            openGeneration++
                            openDiagnostics = true
                        },
            )
        }

        if (openDiagnostics) {
            CamDiagnosticFullReportDialog(
                liveSnapshot = liveSnapshot,
                onDismissRequest = { openDiagnostics = false },
            )
        }
    }
}
