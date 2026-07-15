package dev.pointtosky.mobile.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.buildPredictedStarOverlayDiagnosticText

/**
 * [androidx.compose.ui.platform.testTag] for [PredictedStarMarkersCanvas], for Compose UI tests. Public
 * (not `internal`) so it is visible from the `androidTest` compilation unit.
 */
const val PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG = "predicted_star_markers_canvas"

/**
 * [androidx.compose.ui.platform.testTag] for [PredictedStarOverlayPanel], for Compose UI tests. Public
 * (not `internal`) so it is visible from the `androidTest` compilation unit.
 */
const val PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG = "predicted_star_overlay_panel"

/**
 * [androidx.compose.ui.platform.testTag] for [PredictedStarOverlayControlsToggle], the always-visible,
 * compact `BottomEnd` entry point, for Compose UI tests.
 */
const val PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG = "predicted_star_overlay_controls_header"

/**
 * [androidx.compose.ui.platform.testTag] for [PredictedStarOverlayControlsBody] - present only while
 * expanded, and hosted inside `CamDiagnosticTopPanels`'s own detail region rather than floating at
 * `BottomEnd` - for Compose UI tests.
 */
const val PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG = "predicted_star_overlay_controls_body"

/**
 * [androidx.compose.ui.platform.testTag] for the "Show legacy overlay" [Switch] (task §4), for
 * Compose UI tests.
 */
const val PREDICTED_STAR_OVERLAY_SHOW_LEGACY_SWITCH_TEST_TAG = "predicted_star_overlay_show_legacy_switch"

/** Fixed diagnostic marker color — deliberately distinct from [dev.pointtosky.mobile.render.BvColor]'s
 * star-temperature palette and from the legacy overlay's white reticle/label colors, so predicted
 * markers are visually unambiguous during a physical-device side-by-side comparison (task §10). */
private val PREDICTED_STAR_MARKER_COLOR = Color(0xFF00E5FF) // bright cyan

private const val PREDICTED_STAR_MARKER_MAX_RADIUS_DP = 7f
private const val PREDICTED_STAR_MARKER_MIN_RADIUS_DP = 3f
private const val PREDICTED_STAR_MAG_BRIGHT = 0.0
private const val PREDICTED_STAR_MAG_DIM = 6.0

/** Deliberately thicker than a typical hairline (task §5, follow-up diagnostic layout fix) so the
 * hollow circle/crosshair reads clearly against the legacy overlay's filled dots on a physical device -
 * cosmetic only, never changes radius scaling, color, or marker position. */
private const val PREDICTED_STAR_MARKER_STROKE_WIDTH_DP = 2f

/**
 * CAM-2b (task §8): draws only [PredictedStarOverlayPoint]s — every one of which is already classified
 * `VISIBLE_IN_VIEWPORT` by the reducer — anchored exactly at `(displayX, displayY)`. No viewport scale,
 * crop offset, frame rotation, or declination is reapplied here; CAM-2a's `displayPoint` is already the
 * final display coordinate. A hollow circle plus a small cross, in a fixed diagnostic color distinct
 * from the legacy star overlay's filled `BvColor` dots, so both can be visually compared on top of each
 * other without confusion. No star textures, no names, no touch interaction.
 */
@Composable
fun PredictedStarMarkersCanvas(
    points: List<PredictedStarOverlayPoint>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.testTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG)) {
        val maxRadiusPx = PREDICTED_STAR_MARKER_MAX_RADIUS_DP.dp.toPx()
        val minRadiusPx = PREDICTED_STAR_MARKER_MIN_RADIUS_DP.dp.toPx()
        val strokeWidthPx = PREDICTED_STAR_MARKER_STROKE_WIDTH_DP.dp.toPx()
        points.forEach { point ->
            val magnitude = point.magnitude ?: PREDICTED_STAR_MAG_DIM
            val t =
                ((magnitude - PREDICTED_STAR_MAG_BRIGHT) / (PREDICTED_STAR_MAG_DIM - PREDICTED_STAR_MAG_BRIGHT))
                    .coerceIn(0.0, 1.0)
                    .toFloat()
            val radius = maxRadiusPx + (minRadiusPx - maxRadiusPx) * t
            val center = Offset(point.displayX.toFloat(), point.displayY.toFloat())
            drawCircle(
                color = PREDICTED_STAR_MARKER_COLOR,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx),
            )
            drawLine(
                color = PREDICTED_STAR_MARKER_COLOR,
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = strokeWidthPx,
            )
            drawLine(
                color = PREDICTED_STAR_MARKER_COLOR,
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = strokeWidthPx,
            )
        }
    }
}

/**
 * CAM-2b (task §9): compact, non-interactive, translucent status panel — mirrors CAM-1g's
 * `CameraGeometryDiagnosticOverlay` styling so the two debug panels read as one family. Purely a
 * display of [buildPredictedStarOverlayDiagnosticText]; touches no renderer/matcher/detector state.
 */
@Composable
fun PredictedStarOverlayPanel(
    state: PredictedStarOverlayState,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildPredictedStarOverlayDiagnosticText(state),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            modifier
                .testTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
                .widthIn(max = 280.dp)
                .background(color = Color(0xAA003340), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
    )
}

/**
 * CAM-2b (task §11, extended by the follow-up task §2, hardened by the state-ownership/gesture fix
 * task §1/§4): the compact, always-visible entry point for the CAM-2b interactive controls -
 * `internalDebug`-only, session-local, never persisted. [expanded] is hoisted by the caller
 * (`ArScreen`), not owned here, because the same boolean also decides whether
 * [PredictedStarOverlayControlsBody] is hosted inside `CamDiagnosticTopPanels`'s own bounded/scrollable
 * detail region (see that composable's KDoc for why the body no longer floats at `BottomEnd`, where an
 * expanded card risked permanently covering the bottom target-info card). This toggle itself stays
 * `BottomEnd` and stays tiny - a single row, never scrollable, never pointer-capturing beyond its own
 * small tap target.
 */
@Composable
fun PredictedStarOverlayControlsToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .testTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG)
                .clickable { onExpandedChange(!expanded) }
                .background(color = Color(0xAA003340), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "CAM-2b controls",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = if (expanded) "▴" else "▾",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * CAM-2b (task §11, extended by the follow-up task §2): the actual controls - "Show predicted stars",
 * "Show diagnostic panel", "Show legacy overlay" (task §4), and an explicit two-way selector between
 * [PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS] ("Session intrinsics") and
 * [PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK] ("Diagnostic fallback"). All
 * state is owned by the caller (`ArScreen`, via `remember`), never persisted, never a full settings
 * screen.
 *
 * No header, no expand/collapse affordance of its own, and no fixed screen alignment: the caller
 * ([PredictedStarOverlayControlsToggle]'s `expanded` gates whether this is composed at all) hosts this
 * inside `CamDiagnosticTopPanels`'s own bounded/scrollable detail region - never floating independently
 * over the reticle or the bottom target-info card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictedStarOverlayControlsBody(
    showMarkers: Boolean,
    onShowMarkersChange: (Boolean) -> Unit,
    showPanel: Boolean,
    onShowPanelChange: (Boolean) -> Unit,
    intrinsicsMode: PredictedStarOverlayIntrinsicsMode,
    onIntrinsicsModeChange: (PredictedStarOverlayIntrinsicsMode) -> Unit,
    showLegacyOverlay: Boolean,
    onShowLegacyOverlayChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .testTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG)
                .background(color = Color(0xAA003340), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PredictedStarOverlayToggleRow(
            title = "Show predicted stars",
            checked = showMarkers,
            onCheckedChange = onShowMarkersChange,
        )
        PredictedStarOverlayToggleRow(
            title = "Show CAM-2b panel",
            checked = showPanel,
            onCheckedChange = onShowPanelChange,
        )
        PredictedStarOverlayToggleRow(
            title = "Show legacy overlay",
            checked = showLegacyOverlay,
            onCheckedChange = onShowLegacyOverlayChange,
            switchModifier = Modifier.testTag(PREDICTED_STAR_OVERLAY_SHOW_LEGACY_SWITCH_TEST_TAG),
        )
        Text(
            text = "Intrinsics",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        val modes = PredictedStarOverlayIntrinsicsMode.entries
        val modeLabels = listOf("Session intrinsics", "Diagnostic fallback")
        SingleChoiceSegmentedButtonRow {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = intrinsicsMode == mode,
                    onClick = { onIntrinsicsModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = {
                        Text(text = modeLabels[index], style = MaterialTheme.typography.labelSmall)
                    },
                )
            }
        }
    }
}

@Composable
private fun PredictedStarOverlayToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchModifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = switchModifier)
    }
}
