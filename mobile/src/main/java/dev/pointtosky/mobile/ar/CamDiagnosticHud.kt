package dev.pointtosky.mobile.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import dev.pointtosky.mobile.ar.camera.CameraCalibrationDiagnostics
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.buildCameraCalibrationDiagnosticText
import dev.pointtosky.mobile.ar.camera.buildCameraGeometryDiagnosticText
import dev.pointtosky.mobile.ar.camera.buildCameraSessionIntrinsicsDiagnosticText
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.name

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-1g geometry-diagnostic overlay's full-text [Text]
 * (shown only while the HUD's details are expanded), for Compose UI tests.
 */
const val CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG = "camera_geometry_diagnostic_overlay"

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-2c calibration-diagnostic overlay's full-text
 * [Text] (shown only while the HUD's details are expanded, and only once a calibrated mapping has
 * resolved for this session).
 */
const val CAMERA_CALIBRATION_DIAGNOSTIC_OVERLAY_TEST_TAG = "camera_calibration_diagnostic_overlay"

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-2c runtime-integration diagnostic overlay's
 * full-text [Text] (CAM-2c runtime integration fix §5) — shown only while the HUD's details are
 * expanded, but unlike [CAMERA_CALIBRATION_DIAGNOSTIC_OVERLAY_TEST_TAG] above, rendered whenever a
 * CAM-2c attempt has been made at all, whether it succeeded or failed. This is the overlay that
 * closes the "screen shows only PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED with no CAM-2c attempt"
 * gap.
 */
const val CAMERA_SESSION_INTRINSICS_DIAGNOSTIC_OVERLAY_TEST_TAG = "camera_session_intrinsics_diagnostic_overlay"

/** [androidx.compose.ui.platform.testTag] for the outer HUD container (header + summary/details). */
const val CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG = "cam_diagnostic_hud_panels"

/** [androidx.compose.ui.platform.testTag] for the always-visible, compact, non-scrolling header row. */
const val CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG = "cam_diagnostic_hud_header"

/** [androidx.compose.ui.platform.testTag] for the collapsed one-line CAM-1g summary column. */
const val CAM_DIAGNOSTIC_HUD_SUMMARY_TEST_TAG = "cam_diagnostic_hud_summary"

/** [androidx.compose.ui.platform.testTag] for the CAM-1g one-line summary shown while collapsed. */
const val CAM1G_SUMMARY_TEST_TAG = "cam_diagnostic_hud_cam1g_summary"

/**
 * [androidx.compose.ui.platform.testTag] for the CAM-2b one-line compact status row (HUD-visibility
 * follow-up §2) - always visible, never gated by the HUD's details-expanded/controls-expanded state:
 * collapsed, diagnostics expanded, controls expanded, and while the details/controls content is scrolled
 * all show this same row, so a tester never has to close controls to discover the current CAM-2b state.
 */
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
     * the reticle/central sky content. The top HUD - which now also hosts the CAM-2b controls entry
     * point, see [CamDiagnosticTopPanels]'s own KDoc - may never extend into this band.
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
}

/**
 * CAM-1g/CAM-2b physical-device diagnostic HUD (debug-layout fix, hardened for gesture/state-ownership
 * correctness, then hardened again for whole-container height and debug-control placement). A single
 * parent layout, anchored `TopStart`:
 *
 * - An always-visible, compact header row - never scrollable, never pointer-capturing beyond its own
 *   small tap target - that toggles [detailsExpanded].
 * - A second always-visible, compact row - [PredictedStarOverlayControlsToggle] - that toggles
 *   [controlsExpanded]. Hosted *inside* this HUD (never at `BottomEnd`) precisely so it can never share
 *   screen space with the bottom altitude/azimuth/target-info card, regardless of that card's actual
 *   measured height on a given device - see task hardening §4 for why the previous `BottomEnd` placement
 *   was only ever an unverified claim, not a proven guarantee.
 * - A third always-visible, compact, non-scrolling row (HUD-visibility follow-up §2) - the CAM-2b compact
 *   status line ([CAM2B_SUMMARY_TEST_TAG]) - rendered unconditionally, *outside* both the
 *   collapsed-only summary block and the expanded/scrollable detail region below. Unlike the CAM-1g
 *   summary (collapsed-only, see next bullet), this row never disappears while `detailsExpanded` or
 *   `controlsExpanded` is true: a tester must never have to collapse the controls body or scroll the
 *   details region merely to discover the current CAM-2b state.
 * - While collapsed (`!detailsExpanded && !controlsExpanded`): a short, non-scrolling one-line CAM-1g
 *   category summary. No [androidx.compose.foundation.verticalScroll] container exists in this state at
 *   all, so it can never consume a drag gesture over the AR viewport - the previous version's bug.
 * - While expanded (`detailsExpanded` and/or `controlsExpanded`): the full CAM-1g/CAM-2b diagnostic text
 *   (gated on [detailsExpanded]) and the CAM-2b interactive controls body (gated on [controlsExpanded],
 *   via [controlsContent]) render inside a scrollable region that fills whatever height remains below
 *   the two header rows - never floating independently over the reticle or the bottom target-info card.
 *   Only in this state does any part of the HUD intercept pointer input, and only within that bounded
 *   region.
 *
 * Height containment (task hardening §1, closing the previous gap): [CamDiagnosticHudLayout]'s bound is
 * applied to the **outer** `Column` - the one holding both header rows *and* the summary/details region
 * together - via `Modifier.heightIn(max = hudContentMaxHeight)`. The scrollable detail region is then
 * given `Modifier.weight(1f)`, so Compose's own `Column` measurement policy sizes it to "whatever height
 * remains after the header rows' *actual measured* height," never a guessed/assumed constant. This means
 * the complete HUD - header rows included - can never cross into [CamDiagnosticHudLayout]'s center
 * exclusion band, regardless of how tall the header rows render (e.g. under a larger font scale),
 * because the bound applies to their shared container, not to the scrollable child alone.
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
    onControlsExpandedChange: (Boolean) -> Unit,
    controlsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    calibrationDiagnostics: CameraCalibrationDiagnostics? = null,
    intrinsicsDiagnosticState: CameraSessionIntrinsicsDiagnosticState? = null,
) {
    val showDetails = detailsExpanded || controlsExpanded
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val exclusionTop = maxHeight * (0.5f - CamDiagnosticHudLayout.CENTER_EXCLUSION_HEIGHT_FRACTION / 2f)
        // Bounds the WHOLE HUD container below (header rows + summary/details together) - not just the
        // scrollable detail child - so header height (real or guessed) can never push the total past
        // exclusionTop. See this function's own KDoc "Height containment" section.
        val hudContentMaxHeight: Dp =
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
                    .widthIn(max = CamDiagnosticHudLayout.TOP_HUD_MAX_WIDTH)
                    .heightIn(max = hudContentMaxHeight),
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

            PredictedStarOverlayControlsToggle(
                expanded = controlsExpanded,
                onExpandedChange = onControlsExpandedChange,
                modifier = Modifier.fillMaxWidth(),
            )

            // CAM-2b (HUD-visibility follow-up §2): always-visible compact status - a direct child of
            // this Column, never nested inside the collapsed-only summary block or the expanded/
            // scrollable detail region below, so it renders identically regardless of
            // detailsExpanded/controlsExpanded/scroll position. See this function's own KDoc.
            Text(
                text = cam2bSummaryLine(cam2bState),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(CAM2B_SUMMARY_TEST_TAG)
                        .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )

            if (!showDetails) {
                if (cam1gSnapshot != null) {
                    Column(
                        modifier =
                            Modifier
                                .testTag(CAM_DIAGNOSTIC_HUD_SUMMARY_TEST_TAG)
                                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "CAM-1g: ${cam1gSnapshot.category.name}",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag(CAM1G_SUMMARY_TEST_TAG),
                        )
                    }
                }
            } else {
                // weight(1f) - not a second heightIn - fills whatever height the outer Column's own
                // heightIn(max = hudContentMaxHeight) leaves over after the two header rows above are
                // measured, so this never relies on a guessed header height (task hardening §1/§2).
                Box(
                    modifier =
                        Modifier
                            .testTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG)
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(CamDiagnosticHudLayout.TOP_HUD_PANEL_SPACING)) {
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
                            if (intrinsicsDiagnosticState != null) {
                                CameraSessionIntrinsicsDiagnosticOverlay(intrinsicsDiagnosticState)
                            }
                            if (calibrationDiagnostics != null) {
                                CameraCalibrationDiagnosticOverlay(calibrationDiagnostics)
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
}

/**
 * One-line, non-locale-sensitive, always-visible summary for the CAM-2b panel (task hardening §2/§5,
 * HUD-visibility follow-up §2/§3) - never the sealed state's own `toString()`. `Waiting` always carries
 * its exact [PredictedStarOverlayWaitingReason.name] (never a bare "waiting" with no reason - the bug
 * this follow-up fixes) and `Unavailable` its exact [IntrinsicsMappingUnavailableReason.name], so a
 * physical-device tester can read *why* CAM-2b hasn't reached Ready without expanding anything.
 */
private fun cam2bSummaryLine(state: PredictedStarOverlayState?): String =
    when (state) {
        null -> "CAM-2b: n/a"
        PredictedStarOverlayState.Disabled -> "CAM-2b: disabled"
        is PredictedStarOverlayState.Waiting -> "CAM-2b: waiting · ${state.reason.name}"
        is PredictedStarOverlayState.Unavailable -> "CAM-2b: unavailable · ${state.reason.name}"
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

/**
 * CAM-2c §9 compact diagnostic overlay - translucent, monospace, non-clickable. Purely a display of
 * [CameraCalibrationDiagnostics]; touches no renderer/matcher/detector state, no CAM-2a projection
 * math. Only rendered while this session's calibrated `AnalysisBuffer` mapping has actually
 * succeeded (`calibrationDiagnostics != null` at the call site) - never shown for a
 * `PhysicalSensor`/legacy-fallback session.
 */
@Composable
private fun CameraCalibrationDiagnosticOverlay(
    diagnostics: CameraCalibrationDiagnostics,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildCameraCalibrationDiagnosticText(diagnostics),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            modifier
                .testTag(CAMERA_CALIBRATION_DIAGNOSTIC_OVERLAY_TEST_TAG)
                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
    )
}

/**
 * CAM-2c runtime-integration diagnostic overlay (fix §5) - translucent, monospace, non-clickable.
 * Purely a display of [CameraSessionIntrinsicsDiagnosticState]; touches no renderer/matcher/detector
 * state, no CAM-2a projection math. Unlike [CameraCalibrationDiagnosticOverlay] above, rendered
 * whenever a CAM-2c attempt has been made at all - it is the one place a physical-device tester can
 * see the CAM-2c root cause (e.g. `UnsupportedLogicalMultiCameraMapping`) even when this session
 * published a CAM-1b `PhysicalSensor` fallback instead.
 */
@Composable
private fun CameraSessionIntrinsicsDiagnosticOverlay(
    state: CameraSessionIntrinsicsDiagnosticState,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildCameraSessionIntrinsicsDiagnosticText(state),
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            modifier
                .testTag(CAMERA_SESSION_INTRINSICS_DIAGNOSTIC_OVERLAY_TEST_TAG)
                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
    )
}
