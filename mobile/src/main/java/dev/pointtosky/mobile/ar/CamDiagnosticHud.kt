package dev.pointtosky.mobile.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.buildCameraGeometryDiagnosticText
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-1g geometry-diagnostic overlay's [Text], for
 * Compose UI tests.
 */
const val CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG = "camera_geometry_diagnostic_overlay"

/**
 * [androidx.compose.ui.platform.testTag] for the single scrollable [Column] hosting both the CAM-1g
 * and CAM-2b debug panels, for Compose UI tests asserting the HUD container itself is present.
 */
const val CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG = "cam_diagnostic_hud_panels"

/**
 * Fraction of the available viewport height the top debug-panel stack may occupy before it must
 * scroll internally instead of growing further toward the center reticle. A fraction (not a fixed
 * dp value) so the bound scales with the actual device viewport instead of being tuned to one
 * physical screen size.
 */
private const val HUD_MAX_HEIGHT_FRACTION = 0.42f
private val HUD_MAX_WIDTH = 230.dp
private val HUD_TOP_OFFSET = 56.dp
private val HUD_SIDE_PADDING = 12.dp
private val HUD_PANEL_SPACING = 8.dp

/**
 * CAM-1g/CAM-2b physical-device diagnostic HUD (debug-layout fix, task §1). A single parent layout
 * stacking the CAM-1g geometry panel directly above the CAM-2b predicted-overlay panel - both
 * anchored `TopStart`, width- and height-bounded, and internally vertically scrollable - so neither
 * panel needs a hard-coded per-device offset and the two are never drawn on top of each other (the
 * previous bug: CAM-1g at `TopStart` and CAM-2b at `TopEnd`, each up to 280dp wide, collided in the
 * middle of a ~410dp-wide portrait viewport and CAM-1g - composed last - painted over CAM-2b).
 *
 * Purely a debug display composition: touches no renderer/matcher/detector state, no CAM-2a
 * projection math, and no `displayPoint` coordinates.
 */
@Composable
fun CamDiagnosticTopPanels(
    cam1gSnapshot: CameraGeometryDiagnosticSnapshot?,
    cam1gSessionId: Long,
    cam1gStatusTransitionCount: Int,
    cam1gObservedFrameCount: Long,
    cam1gReadyBundleCount: Long,
    cam2bState: PredictedStarOverlayState?,
    modifier: Modifier = Modifier,
) {
    if (cam1gSnapshot == null && cam2bState == null) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val hudMaxHeight = maxHeight * HUD_MAX_HEIGHT_FRACTION
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .testTag(CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG)
                    .statusBarsPadding()
                    .padding(top = HUD_TOP_OFFSET, start = HUD_SIDE_PADDING, end = HUD_SIDE_PADDING)
                    .widthIn(max = HUD_MAX_WIDTH)
                    .heightIn(max = hudMaxHeight)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(HUD_PANEL_SPACING),
        ) {
            if (cam1gSnapshot != null) {
                CameraGeometryDiagnosticOverlay(
                    snapshot = cam1gSnapshot,
                    sessionId = cam1gSessionId,
                    statusTransitionCount = cam1gStatusTransitionCount,
                    observedFrameCount = cam1gObservedFrameCount,
                    readyBundleCount = cam1gReadyBundleCount,
                )
            }
            if (cam2bState != null) {
                PredictedStarOverlayPanel(state = cam2bState)
            }
        }
    }
}

/**
 * CAM-1g compact diagnostic overlay (§5) - translucent, monospace, non-clickable. Purely a display of
 * [CameraGeometryDiagnosticSnapshot]; touches no renderer/matcher/detector state. Width is bounded by
 * the parent [CamDiagnosticTopPanels] column, not here, so it never fights the shared HUD bound.
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
                .testTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG)
                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
    )
}
