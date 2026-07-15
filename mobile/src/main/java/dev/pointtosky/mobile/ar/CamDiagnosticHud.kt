package dev.pointtosky.mobile.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.buildCameraGeometryDiagnosticText
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-1g geometry-diagnostic overlay's full-text [Text]
 * (shown only while the HUD's details are expanded), for Compose UI tests.
 */
const val CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG = "camera_geometry_diagnostic_overlay"

/** [androidx.compose.ui.platform.testTag] for the outer HUD container (header + summary/details). */
const val CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG = "cam_diagnostic_hud_panels"

/** [androidx.compose.ui.platform.testTag] for the always-visible, compact, non-scrolling header row. */
const val CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG = "cam_diagnostic_hud_header"

/** [androidx.compose.ui.platform.testTag] for the collapsed one-line CAM-1g/CAM-2b summary column. */
const val CAM_DIAGNOSTIC_HUD_SUMMARY_TEST_TAG = "cam_diagnostic_hud_summary"

/** [androidx.compose.ui.platform.testTag] for the CAM-1g one-line summary shown while collapsed. */
const val CAM1G_SUMMARY_TEST_TAG = "cam_diagnostic_hud_cam1g_summary"

/** [androidx.compose.ui.platform.testTag] for the CAM-2b one-line summary shown while collapsed. */
const val CAM2B_SUMMARY_TEST_TAG = "cam_diagnostic_hud_cam2b_summary"

/**
 * [androidx.compose.ui.platform.testTag] for the bounded, scrollable detail container - the only part
 * of this HUD that ever intercepts a drag/scroll gesture, and only while expanded (task hardening §2).
 */
const val CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG = "cam_diagnostic_hud_details"

/**
 * Pure layout contract (task hardening §3) for where each region of the physical-device diagnostic HUD
 * may occupy the portrait viewport. Expressed as dp offsets and dimensionless fractions of the actual
 * measured viewport - never raw device pixels - so it holds across viewport sizes, not just the
 * observed Pixel 9 1080x2424 buffer this fix was written against.
 */
object CamDiagnosticHudLayout {
    /**
     * Vertically centered band, as a fraction of the full viewport height, reserved exclusively for
     * the reticle/central sky content. Neither the top HUD nor the bottom controls entry point may
     * extend into this band.
     */
    const val CENTER_EXCLUSION_HEIGHT_FRACTION = 0.22f

    /**
     * Fixed offset before the HUD's own content starts, clearing the back button and status bar.
     * Combined with [TOP_HUD_SAFETY_MARGIN] when computing the expanded detail region's own max
     * height, so that region can never reach [CENTER_EXCLUSION_HEIGHT_FRACTION]'s top boundary.
     */
    val TOP_HUD_TOP_OFFSET = 56.dp

    /**
     * Extra headroom subtracted on top of [TOP_HUD_TOP_OFFSET] to absorb the real (not statically
     * known at this layer) status-bar inset height, so the computed bound stays conservative rather
     * than assuming a zero-height status bar.
     */
    val TOP_HUD_SAFETY_MARGIN = 32.dp

    val TOP_HUD_SIDE_PADDING = 12.dp
    val TOP_HUD_MAX_WIDTH = 230.dp
    val TOP_HUD_PANEL_SPACING = 8.dp

    /**
     * Documentation-only contract upper bound for the bottom-anchored CAM-2b controls entry point: it
     * must never grow beyond this fraction of the viewport height, measured from the bottom edge. The
     * entry point itself ([PredictedStarOverlayControlsToggle]) is a single compact row and never
     * approaches this bound in practice - the constant exists so the bottom region's contract is
     * explicit and testable, matching the top HUD's.
     */
    const val BOTTOM_REGION_MAX_HEIGHT_FRACTION = 0.35f
}

/**
 * CAM-1g/CAM-2b physical-device diagnostic HUD (debug-layout fix, hardened for gesture/state-ownership
 * correctness). A single parent layout, anchored `TopStart`:
 *
 * - An always-visible, compact header row - never scrollable, never pointer-capturing beyond its own
 *   small tap target - that toggles [detailsExpanded].
 * - While collapsed (`!detailsExpanded && !controlsExpanded`): a short, non-scrolling one-line summary
 *   per panel (CAM-1g category, CAM-2b status/intrinsics-mode/visible-count). No
 *   [androidx.compose.foundation.verticalScroll] container exists in this state at all, so it can never
 *   consume a drag gesture over the AR viewport - the previous version's bug.
 * - While expanded (`detailsExpanded` and/or `controlsExpanded`): a width/height-bounded, scrollable
 *   column holding the full CAM-1g and CAM-2b diagnostic text (gated on [detailsExpanded]) and the
 *   CAM-2b interactive controls body (gated on [controlsExpanded], via [controlsContent] - hosted here,
 *   inside this HUD's own bounded region, rather than floating independently over the reticle or the
 *   bottom target-info card; see [PredictedStarOverlayControlsBody]'s own KDoc). Only in this state does
 *   any part of the HUD intercept pointer input, and only within its own bounded panel.
 *
 * The expanded detail region's max height is derived from [CamDiagnosticHudLayout] so it can never reach
 * that layout's center-exclusion band, regardless of viewport size - never a value tuned to one specific
 * device's raw pixel dimensions.
 *
 * Purely a debug display composition: touches no renderer/matcher/detector state, no CAM-2a projection
 * math, and no `displayPoint` coordinate.
 */
@Composable
fun CamDiagnosticTopPanels(
    cam1gSnapshot: CameraGeometryDiagnosticSnapshot?,
    cam1gSessionId: Long,
    cam1gStatusTransitionCount: Int,
    cam1gObservedFrameCount: Long,
    cam1gReadyBundleCount: Long,
    cam2bState: PredictedStarOverlayState?,
    detailsExpanded: Boolean,
    onDetailsExpandedChange: (Boolean) -> Unit,
    controlsExpanded: Boolean,
    controlsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showDetails = detailsExpanded || controlsExpanded
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val exclusionTop = maxHeight * (0.5f - CamDiagnosticHudLayout.CENTER_EXCLUSION_HEIGHT_FRACTION / 2f)
        val detailMaxHeight: Dp =
            maxOf(
                exclusionTop - CamDiagnosticHudLayout.TOP_HUD_TOP_OFFSET - CamDiagnosticHudLayout.TOP_HUD_SAFETY_MARGIN,
                0.dp,
            )

        Column(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .testTag(CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG)
                    .statusBarsPadding()
                    .padding(
                        top = CamDiagnosticHudLayout.TOP_HUD_TOP_OFFSET,
                        start = CamDiagnosticHudLayout.TOP_HUD_SIDE_PADDING,
                        end = CamDiagnosticHudLayout.TOP_HUD_SIDE_PADDING,
                    )
                    .widthIn(max = CamDiagnosticHudLayout.TOP_HUD_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(CamDiagnosticHudLayout.TOP_HUD_PANEL_SPACING),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG)
                        .clickable { onDetailsExpandedChange(!detailsExpanded) }
                        .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CAM diagnostics",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = if (detailsExpanded) "▴ Collapse" else "▾ Expand diagnostics",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (!showDetails) {
                Column(
                    modifier =
                        Modifier
                            .testTag(CAM_DIAGNOSTIC_HUD_SUMMARY_TEST_TAG)
                            .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (cam1gSnapshot != null) {
                        Text(
                            text = "CAM-1g: ${cam1gSnapshot.category.name}",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(CAM1G_SUMMARY_TEST_TAG),
                        )
                    }
                    Text(
                        text = cam2bSummaryLine(cam2bState),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(CAM2B_SUMMARY_TEST_TAG),
                    )
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .testTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG)
                            .heightIn(max = detailMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CamDiagnosticHudLayout.TOP_HUD_PANEL_SPACING),
                ) {
                    if (detailsExpanded) {
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
                    if (controlsExpanded) {
                        controlsContent()
                    }
                }
            }
        }
    }
}

/** One-line, non-locale-sensitive collapsed summary for the CAM-2b panel (task hardening §2/§5) - never
 * the sealed state's own `toString()`. */
private fun cam2bSummaryLine(state: PredictedStarOverlayState?): String =
    when (state) {
        null -> "CAM-2b: n/a"
        PredictedStarOverlayState.Disabled -> "CAM-2b: disabled"
        is PredictedStarOverlayState.Waiting -> "CAM-2b: waiting"
        is PredictedStarOverlayState.Unavailable -> "CAM-2b: unavailable (${state.reason.name})"
        is PredictedStarOverlayState.Ready -> {
            val modeLabel =
                when (state.metadata.intrinsicsMode) {
                    PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS -> "session"
                    PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK -> "fallback"
                }
            "CAM-2b: ready · $modeLabel · visible: ${state.metadata.visibleCount}"
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
