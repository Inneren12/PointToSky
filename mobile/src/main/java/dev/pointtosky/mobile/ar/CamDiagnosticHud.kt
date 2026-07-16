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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraCalibrationDiagnostics
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticCompactSummaryText
import dev.pointtosky.mobile.ar.camera.captureCamDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.name

/**
 * [androidx.compose.ui.platform.testTag] for the compact CAM diagnostics summary (HUD redesign §6) -
 * the highest-value few-line root-cause summary shown while the HUD's details are expanded, replacing
 * the previous giant per-domain text blocks ([buildCamDiagnosticCompactSummaryText]'s output). The
 * full multi-section report lives behind [CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG] instead.
 */
const val CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG = "cam_diagnostic_compact_summary"

/**
 * [androidx.compose.ui.platform.testTag] for the "Open diagnostics" affordance (HUD redesign §6) that
 * opens [CamDiagnosticFullReportDialog] - the full, scrollable, Freeze/Copy/Share/JSON-capable
 * diagnostics surface, never shown directly over the AR viewport by default.
 */
const val CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG = "cam_diagnostic_open_button"

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
 * - While expanded (`detailsExpanded` and/or `controlsExpanded`): a bounded, few-line CAM diagnostics
 *   summary (gated on [detailsExpanded]; see [CamDiagnosticCompactSummaryPanel] - HUD redesign §6, no
 *   longer the giant per-domain text blocks a physical-device tester previously had to scroll/screenshot
 *   through), the CAM-2b panel, and the CAM-2b interactive controls body (gated on [controlsExpanded],
 *   via [controlsContent]) render inside a scrollable region that fills whatever height remains below
 *   the two header rows - never floating independently over the reticle or the bottom target-info card.
 *   Only in this state does any part of the HUD intercept pointer input, and only within that bounded
 *   region. The complete multi-section report - Freeze/Resume, Copy all, Share log, Share JSON - lives
 *   behind "Open diagnostics" ([CamDiagnosticFullReportDialog]), a full-screen [androidx.compose.ui.window.Dialog]
 *   that is never shown directly over the AR viewport by default.
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
    // HUD redesign §6: session-scoped ("fresh session -> closed dialog", matching every other
    // session-keyed debug toggle in this HUD) rather than a permanent global - a new AR/debug session
    // must never inherit a previous session's open full-diagnostics dialog.
    var openDiagnostics by remember(cam1gSessionId) { mutableStateOf(false) }
    // Diagnostic export/freeze fix §1: one immutable value snapshot, rebuilt fresh on every
    // recomposition from exactly the same parameters the old per-domain overlays read - never a second,
    // independent read of any live provider/coordinator. Cheap pure construction; safe to build even
    // while collapsed; see CamDiagnosticSnapshot's own KDoc for why this can never disagree with
    // [cam1gSnapshot]/[intrinsicsDiagnosticState]/[calibrationDiagnostics].
    val liveSnapshot =
        captureCamDiagnosticSnapshot(
            capturedAtEpochMillis = System.currentTimeMillis(),
            sessionId = cam1gSessionId,
            cam2bState = cam2bState,
            cameraGeometryState = cam1gSnapshot,
            cameraGeometryStatusTransitionCount = cam1gStatusTransitionCount,
            cameraGeometryObservedFrameCount = cam1gObservedFrameCount,
            cameraGeometryReadyBundleCount = cam1gReadyBundleCount,
            cameraIntrinsicsState = intrinsicsDiagnosticState,
            calibrationDiagnostics = calibrationDiagnostics,
        )
    // Rendered regardless of showDetails/detailsExpanded (HUD redesign §6): once opened, this
    // full-screen Dialog must not force-close just because the small top HUD is later collapsed.
    if (openDiagnostics) {
        CamDiagnosticFullReportDialog(
            liveSnapshot = liveSnapshot,
            onDismissRequest = { openDiagnostics = false },
        )
    }
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
                            // HUD redesign §6: a bounded, few-line root-cause summary - never the
                            // previous giant per-domain text blocks that required scrolling/multiple
                            // screenshots to read in full. The complete multi-section report is one tap
                            // away via "Open diagnostics", not shown directly over the AR viewport here.
                            CamDiagnosticCompactSummaryPanel(
                                snapshot = liveSnapshot,
                                onOpenDiagnostics = { openDiagnostics = true },
                            )
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
 * The compact CAM diagnostics summary (HUD redesign §6) - translucent, monospace, mostly non-clickable
 * except its own "Open diagnostics" line. Purely a display of [CamDiagnosticSnapshot] via
 * [buildCamDiagnosticCompactSummaryText]; touches no renderer/matcher/detector state, no CAM-2a
 * projection math, no camera binding. Width is bounded by the parent [CamDiagnosticTopPanels] column,
 * not here, so it never fights the shared HUD bound. Replaces the previous
 * `CameraGeometryDiagnosticOverlay`/`CameraSessionIntrinsicsDiagnosticOverlay`/
 * `CameraCalibrationDiagnosticOverlay` trio, whose combined output on a real Pixel 9 session could run
 * to dozens of lines - exactly the "multiple scrolling screenshots" workflow this fix replaces.
 */
@Composable
private fun CamDiagnosticCompactSummaryPanel(
    snapshot: CamDiagnosticSnapshot,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .testTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
                .background(color = Color(0xAA000000), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = buildCamDiagnosticCompactSummaryText(snapshot),
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
                    .clickable(onClick = onOpenDiagnostics),
        )
    }
}
