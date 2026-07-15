package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryCenterProbeSnapshot
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayWaitingReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the physical-device diagnostic-layout fix and its two hardening follow-ups:
 * state-ownership/gesture correctness, then whole-HUD height containment, session-keyed debug-control
 * reset, and the controls toggle's relocation into the shared HUD. Fixture values below (buffer
 * 640x480, viewport 1080x2424, rotationDegrees 90, ~+3.7ms pair delta, `READY_CALIBRATED`/
 * `CAMERA_CHARACTERISTICS`, FOV 70.7x56.2, scale 3.788, offset -369,0) mirror the physical Pixel 9
 * observation this fix addresses. Not pixel-perfect screenshot tests - this repository does not use
 * those.
 *
 * [HOST_WIDTH]/[HOST_HEIGHT] approximate a realistic portrait viewport in dp (not an exaggerated claim
 * the real `ComponentActivity` window could silently clamp).
 */
@RunWith(AndroidJUnit4::class)
class CamDiagnosticHudLayoutTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val cam1gSnapshot =
        CameraGeometryDiagnosticSnapshot(
            category = CameraGeometryDiagnosticCategory.READY_CALIBRATED,
            quality = CameraGeometryQuality.CALIBRATED,
            frameTimestampNanos = 1_000_000L,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            cropLeftPx = null,
            cropTopPx = null,
            cropRightPx = null,
            cropBottomPx = null,
            rotationDegrees = 90,
            viewportWidthPx = 1080,
            viewportHeightPx = 2424,
            pairDeltaNanos = 3_700_000L,
            intrinsicsSource = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            horizontalFovDeg = 70.7,
            verticalFovDeg = 56.2,
            uniformScale = 3.788,
            displayOffsetX = -369.0,
            displayOffsetY = 0.0,
            centerProbe = null,
        )

    /** Same fixture as [cam1gSnapshot] plus a center-probe pair, so its rendered text is longer -
     * used to stress the whole-HUD height bound with realistically long CAM-1g content. */
    private val cam1gSnapshotLong =
        cam1gSnapshot.copy(
            centerProbe =
                CameraGeometryCenterProbeSnapshot(
                    viewportCenterXPx = 540.0,
                    viewportCenterYPx = 1212.0,
                    imagePointXPx = 320.0,
                    imagePointYPx = 240.0,
                    roundTripErrorPx = 0.05,
                ),
        )

    private val cam2bReadyState =
        PredictedStarOverlayState.Ready.of(
            points =
                listOf(
                    PredictedStarOverlayPoint(catalogIndex = 0, magnitude = 1.2, displayX = 500.0, displayY = 900.0),
                ),
            summary =
                StarPredictionSummary(
                    inputCount = 5,
                    behindCameraCount = 1,
                    outsideImageCount = 1,
                    insideImageOutsideViewportCount = 1,
                    visibleInViewportCount = 1,
                ),
            metadata =
                PredictedStarOverlayMetadata(
                    inputCount = 5,
                    visibleCount = 1,
                    frameTimestampNanos = 1_000_000L,
                    rotationTimestampNanos = 990_000L,
                    pairDeltaNanos = 3_700_000L,
                    frameRotationDegrees = 90,
                    intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                    sessionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                    sessionIntrinsicsReference = "PhysicalSensor",
                    projectionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                    projectionIntrinsicsReference = "PhysicalSensor",
                    magneticDeclinationDeg = 4.2,
                ),
        )

    /** Longer CAM-2b text than [cam2bReadyState] (fallback mode shows both session AND projection
     * intrinsics lines) - the other half of the "long content" fixture pair. */
    private val cam2bFallbackReadyState =
        PredictedStarOverlayState.Ready.of(
            points =
                listOf(
                    PredictedStarOverlayPoint(catalogIndex = 0, magnitude = 1.0, displayX = 200.0, displayY = 300.0),
                ),
            summary =
                StarPredictionSummary(
                    inputCount = 4,
                    behindCameraCount = 1,
                    outsideImageCount = 1,
                    insideImageOutsideViewportCount = 1,
                    visibleInViewportCount = 1,
                ),
            metadata =
                PredictedStarOverlayMetadata(
                    inputCount = 4,
                    visibleCount = 1,
                    frameTimestampNanos = 1_000_000L,
                    rotationTimestampNanos = 990_000L,
                    pairDeltaNanos = 3_700_000L,
                    frameRotationDegrees = 90,
                    intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK,
                    sessionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                    sessionIntrinsicsReference = "PhysicalSensor",
                    projectionIntrinsicsSource = "LEGACY_FALLBACK",
                    projectionIntrinsicsReference = "AnalysisBuffer(640x480)",
                    magneticDeclinationDeg = 4.2,
                ),
        )

    /**
     * A bounded, realistic-portrait-viewport host so [CamDiagnosticTopPanels]'s internal
     * `BoxWithConstraints` sees plausible (not unbounded, and not an exaggerated claim the real
     * Activity root could silently clamp) constraints to compute its exclusion-safe bound against.
     */
    @Composable
    private fun BoundedHudHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(HOST_WIDTH, HOST_HEIGHT)) {
            content()
        }
    }

    private fun expectedExclusionTop() = HOST_HEIGHT * (0.5f - CamDiagnosticHudLayout.CENTER_EXCLUSION_HEIGHT_FRACTION / 2f)

    /** [CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG] is the actual `heightIn`-bounded HUD container (header rows
     * + summary/details together) - asserting its bottom edge, not just a child's, is what proves the
     * *whole* HUD stays clear of the center exclusion zone (task hardening §1/§2). */
    private fun assertWholeHudWithinExclusionSafeBounds() {
        val hudBounds = composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG).getUnclippedBoundsInRoot()
        val exclusionTop = expectedExclusionTop()
        assertTrue(
            "expected the WHOLE HUD container (header rows included) to stay within the center " +
                "exclusion zone; hudBounds=$hudBounds exclusionTop=$exclusionTop",
            hudBounds.bottom <= exclusionTop,
        )
    }

    @Test
    fun cam1gAndCam2bPanelsBothPresentWithoutSharingAlignmentOrSlot() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bReadyState,
                    detailsExpanded = true,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertExists()

        val cam1gBounds = composeTestRule.onNodeWithTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG).getUnclippedBoundsInRoot()
        val cam2bBounds = composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).getUnclippedBoundsInRoot()

        // Stacked vertically in one shared column - never two independently-aligned overlays
        // colliding within the same top corner.
        assertTrue(
            "expected CAM-2b panel to start at or below CAM-1g panel's bottom edge, " +
                "was cam1g=$cam1gBounds cam2b=$cam2bBounds",
            cam2bBounds.top >= cam1gBounds.bottom,
        )
    }

    @Test
    fun collapsedDefaultStateShowsSummariesWithinExclusionSafeBounds() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bReadyState,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM1G_SUMMARY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM1G_SUMMARY_TEST_TAG).assert(hasText("READY_CALIBRATED", substring = true))
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assert(hasText("ready", substring = true))

        // controls body absent while collapsed
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertCountEquals(0)
        // no scrollable detail container while collapsed
        composeTestRule.onAllNodesWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertCountEquals(0)

        assertWholeHudWithinExclusionSafeBounds()
    }

    /**
     * Regression test for the HUD-visibility bug (task §2): previously, whenever `controlsExpanded` was
     * true but `detailsExpanded` was false, `showDetails` was true, so the collapsed-only summary block
     * never rendered - but `detailsExpanded` being false also meant the expanded detail region's own
     * `if (detailsExpanded)` guard never rendered [PredictedStarOverlayPanel] either. Net result: no
     * CAM-2b text anywhere on screen, so a tester had to collapse the controls body just to discover
     * whether CAM-2b was Ready/Waiting/Unavailable. The compact status row must now survive exactly this
     * state combination.
     */
    @Test
    fun cam2bCompactStatusStaysVisibleWhileOnlyControlsExpanded() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bReadyState,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = true,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("ready · session · visible: 1", substring = true))
    }

    @Test
    fun cam2bCompactStatusStaysVisibleWhileDiagnosticsExpanded() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bReadyState,
                    detailsExpanded = true,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun cam2bCompactStatusIncludesExactWaitingReason() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable),
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: waiting · OBSERVER_LOCATION_UNAVAILABLE", substring = true))
    }

    @Test
    fun cam2bCompactStatusIncludesExactGeometryNotReadyReason() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState =
                        PredictedStarOverlayState.Waiting(
                            PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME),
                        ),
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: waiting · GEOMETRY_NOT_READY(MISSING_FRAME)", substring = true))
    }

    @Test
    fun cam2bCompactStatusIncludesExactUnavailableReason() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState =
                        PredictedStarOverlayState.Unavailable(
                            IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,
                        ),
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: unavailable · PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
    }

    @Test
    fun cam2bCompactStatusIncludesFallbackModeAndVisibleCount() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = cam2bFallbackReadyState,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: ready · fallback · visible: 1", substring = true))
    }

    @Test
    fun collapsedHudHasNoScrollContainerAndExpansionTogglesDetailContainer() {
        composeTestRule.setContent {
            BoundedHudHost {
                var expanded by remember { mutableStateOf(false) }
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bReadyState,
                    detailsExpanded = expanded,
                    onDetailsExpandedChange = { expanded = it },
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        // Collapsed: no scroll/details container exists at all.
        composeTestRule.onAllNodesWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertCountEquals(0)

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertExists()

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG).performClick()
        composeTestRule.onAllNodesWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun expandedDiagnosticsWithLongContentStaysWithinWholeHudBound() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshotLong,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 3,
                    cam1gObservedFrameCount = 500L,
                    cam1gReadyBundleCount = 480L,
                    cam2bState = cam2bFallbackReadyState,
                    detailsExpanded = true,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        // Header stays visible even though the detail content below it is long enough to scroll.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertExists()
        // Long CAM-1g/CAM-2b content is present in the semantics tree (may need scrolling to bring
        // fully into view - assertExists, not assertIsDisplayed, checks presence not current visibility).
        composeTestRule.onNodeWithTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertExists()

        assertWholeHudWithinExclusionSafeBounds()
    }

    @Test
    fun wholeHudStaysWithinBoundsUnderLargeFontScale() {
        composeTestRule.setContent {
            BoundedHudHost {
                val inflatedDensity = Density(density = LocalDensity.current.density, fontScale = 2.5f)
                CompositionLocalProvider(LocalDensity provides inflatedDensity) {
                    CamDiagnosticTopPanels(
                        cam1gSnapshot = cam1gSnapshot,
                        cam1gSessionId = 1L,
                        cam1gStatusTransitionCount = 0,
                        cam1gObservedFrameCount = 10L,
                        cam1gReadyBundleCount = 10L,
                        cam2bState = cam2bReadyState,
                        detailsExpanded = true,
                        onDetailsExpandedChange = {},
                        controlsExpanded = false,
                        onControlsExpandedChange = {},
                        controlsContent = {},
                    )
                }
            }
        }

        // At 2.5x font scale the two header rows (and the summary/detail text) render noticeably
        // taller than a hypothetical fixed/guessed header-height constant would assume. The bound must
        // hold anyway, because it is derived from the outer Column's own `heightIn`, not a constant.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_HEADER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_DETAILS_TEST_TAG).assertExists()

        assertWholeHudWithinExclusionSafeBounds()
    }

    @Test
    fun collapsedControlsToggleIsCompactWithNoBody() {
        composeTestRule.setContent {
            PredictedStarOverlayControlsToggle(expanded = false, onExpandedChange = {})
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun expandedControlsExposeBothIntrinsicsModesAndStayHostedInTheTopRegion() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = null,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = true,
                    onControlsExpandedChange = {},
                    controlsContent = {
                        PredictedStarOverlayControlsBody(
                            showMarkers = true,
                            onShowMarkersChange = {},
                            showPanel = true,
                            onShowPanelChange = {},
                            intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                            onIntrinsicsModeChange = {},
                            showLegacyOverlay = true,
                            onShowLegacyOverlayChange = {},
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Session intrinsics").assertIsDisplayed()
        composeTestRule.onNodeWithText("Diagnostic fallback").assertIsDisplayed()

        // Hardening task §4: the expanded body must be hosted inside the top HUD's own bounded
        // region - clear of the center exclusion zone, and nowhere near the bottom target-info card -
        // rather than floating independently at BottomEnd where it used to risk covering it. Assert
        // the WHOLE HUD's bound, not only the controls body's own bottom edge, since a child could sit
        // inside its own sub-bound while the outer container it belongs to still overflows.
        val controlsBodyBounds =
            composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).getUnclippedBoundsInRoot()
        val exclusionTop = expectedExclusionTop()
        assertTrue(
            "expected the expanded controls body to stay within the top HUD region, clear of the " +
                "center exclusion zone; controlsBodyBounds=$controlsBodyBounds exclusionTop=$exclusionTop",
            controlsBodyBounds.bottom <= exclusionTop,
        )
        assertWholeHudWithinExclusionSafeBounds()
    }

    @Test
    fun legacyOverlayToggleInvokesCallbackWithNewValue() {
        var lastValue: Boolean? = null
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = null,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = true,
                    onControlsExpandedChange = {},
                    controlsContent = {
                        PredictedStarOverlayControlsBody(
                            showMarkers = true,
                            onShowMarkersChange = {},
                            showPanel = true,
                            onShowPanelChange = {},
                            intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                            onIntrinsicsModeChange = {},
                            showLegacyOverlay = true,
                            onShowLegacyOverlayChange = { lastValue = it },
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_SHOW_LEGACY_SWITCH_TEST_TAG).performClick()

        assertEquals(false, lastValue)
    }

    @Test
    fun collapsingControlsRemovesBody() {
        composeTestRule.setContent {
            BoundedHudHost {
                var expanded by remember { mutableStateOf(true) }
                CamDiagnosticTopPanels(
                    cam1gSnapshot = null,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 0L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = null,
                    detailsExpanded = false,
                    onDetailsExpandedChange = {},
                    controlsExpanded = expanded,
                    onControlsExpandedChange = { expanded = it },
                    controlsContent = {
                        PredictedStarOverlayControlsBody(
                            showMarkers = true,
                            onShowMarkersChange = {},
                            showPanel = true,
                            onShowPanelChange = {},
                            intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                            onIntrinsicsModeChange = {},
                            showLegacyOverlay = true,
                            onShowLegacyOverlayChange = {},
                        )
                    },
                )
            }
        }

        // The toggle is now rendered by CamDiagnosticTopPanels itself (task hardening §4) - not a
        // separately-placed composable - so clicking its tag here exercises the real composition shape.
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG).performClick()
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun cam2bPanelTextFullyPresentInSessionUnavailableState() {
        val unavailable =
            PredictedStarOverlayState.Unavailable(
                IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,
            )

        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 0L,
                    cam2bState = unavailable,
                    detailsExpanded = true,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        // assertExists (not assertIsDisplayed): the expanded detail column is bounded/scrollable, so
        // this text may need scrolling to bring fully into view - it must still be present, unclipped
        // from the semantics tree, either way.
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
    }

    @Test
    fun cam2bPanelTextFullyPresentInDiagnosticFallbackReadyState() {
        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = cam2bFallbackReadyState,
                    detailsExpanded = true,
                    onDetailsExpandedChange = {},
                    controlsExpanded = false,
                    onControlsExpandedChange = {},
                    controlsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("status: ready", substring = true))
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(640x480)", substring = true))
    }

    /**
     * Mirrors `ArScreen`'s hardened state-ownership pattern (task hardening §1): a single value from
     * the caller, read directly by both consumers - never a mutable local written by one composition
     * branch for another to read later (the removed `predictedStarPanelState` anti-pattern).
     */
    @Composable
    private fun StableStateOwnershipHost(
        overlayState: PredictedStarOverlayState?,
        showMarkers: Boolean,
        showPanel: Boolean,
    ) {
        if (showMarkers && overlayState is PredictedStarOverlayState.Ready) {
            PredictedStarMarkersCanvas(points = overlayState.points)
        }
        CamDiagnosticTopPanels(
            cam1gSnapshot = null,
            cam1gSessionId = 1L,
            cam1gStatusTransitionCount = 0,
            cam1gObservedFrameCount = 0L,
            cam1gReadyBundleCount = 0L,
            cam2bState = overlayState.takeIf { showPanel },
            detailsExpanded = true,
            onDetailsExpandedChange = {},
            controlsExpanded = false,
            onControlsExpandedChange = {},
            controlsContent = {},
        )
    }

    @Test
    fun stableStateOwnershipHasNoStaleResultAcrossTransitions() {
        var overlayState by mutableStateOf<PredictedStarOverlayState>(cam2bReadyState)
        var showPanel by mutableStateOf(true)

        composeTestRule.setContent {
            BoundedHudHost {
                StableStateOwnershipHost(overlayState = overlayState, showMarkers = true, showPanel = showPanel)
            }
        }

        // Ready: markers + panel agree on the same state.
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assert(hasText("status: ready", substring = true))

        // Ready -> non-Ready (Waiting): stale markers must disappear and the panel must not retain
        // stale "status: ready" text.
        composeTestRule.runOnUiThread {
            overlayState = PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assert(hasText("waiting", substring = true))
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assert(!hasText("status: ready", substring = true))

        // Back to Ready, then "show panel" true -> false: the panel node must disappear even though
        // the underlying state is Ready; markers stay governed independently by showMarkers.
        composeTestRule.runOnUiThread { overlayState = cam2bReadyState }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertExists()
        composeTestRule.runOnUiThread { showPanel = false }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)

        // session -> fallback intrinsics mode: swap to a Ready fixture using
        // DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK metadata - both consumers must reflect it, no stale
        // session-mode text left over.
        composeTestRule.runOnUiThread {
            showPanel = true
            overlayState = cam2bFallbackReadyState
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(640x480)", substring = true))
    }

    /**
     * HUD-visibility follow-up §4 state-transition coverage: the always-visible compact status
     * ([CAM2B_SUMMARY_TEST_TAG], never [PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG] - that one is only proven
     * stale-free above) must update on every CAM-2b state transition, dropping the previous state's text
     * entirely rather than accumulating it. Covers Waiting -> Ready, Ready -> Waiting, and a
     * session-unavailable intrinsics reference reaching Ready only via the diagnostic-fallback mode
     * ("Session unavailable -> fallback Ready").
     */
    @Test
    fun cam2bCompactStatusUpdatesAcrossWaitingReadyUnavailableAndFallbackReadyTransitions() {
        var overlayState by
            mutableStateOf<PredictedStarOverlayState>(
                PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable),
            )

        composeTestRule.setContent {
            BoundedHudHost {
                StableStateOwnershipHost(overlayState = overlayState, showMarkers = true, showPanel = true)
            }
        }

        // Waiting: exact reason in the compact status, no marker canvas yet.
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: waiting · OBSERVER_LOCATION_UNAVAILABLE", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)

        // Waiting -> Ready: compact status flips to ready/session/visible-count; marker canvas appears.
        composeTestRule.runOnUiThread { overlayState = cam2bReadyState }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: ready · session · visible: 1", substring = true))
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(!hasText("OBSERVER_LOCATION_UNAVAILABLE", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)

        // Ready -> Waiting (a new geometry-not-ready reason): stale "ready" text and stale markers must
        // both disappear, never left over from the previous Ready state.
        composeTestRule.runOnUiThread {
            overlayState =
                PredictedStarOverlayState.Waiting(
                    PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.DISPOSED),
                )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: waiting · GEOMETRY_NOT_READY(DISPOSED)", substring = true))
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assert(!hasText("ready · session", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)

        // Session-referenced intrinsics unavailable -> diagnostic-fallback Ready ("Session unavailable ->
        // fallback Ready"): the compact status must flip straight from "unavailable" to "ready ·
        // fallback", and markers must reappear.
        composeTestRule.runOnUiThread {
            overlayState =
                PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: unavailable · PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)

        composeTestRule.runOnUiThread { overlayState = cam2bFallbackReadyState }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2b: ready · fallback · visible: 1", substring = true))
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assert(!hasText("unavailable", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)
    }

    /**
     * task hardening §3: simulates a tester customizing session 1 (fallback intrinsics, legacy overlay
     * hidden, HUD/controls expanded) then the debug session ID changing to 2 - every field must reset
     * to its documented default, proving the reset policy without needing the full `ArScreen`/camera
     * stack (impractical to spin up a real CameraX/sensor pipeline in a Compose UI test).
     */
    @Test
    fun sessionKeyedDebugControlsResetOnNewSessionId() {
        var sessionId by mutableStateOf(1L)
        lateinit var controls: PredictedStarDebugControlsState

        composeTestRule.setContent {
            controls = rememberPredictedStarDebugControls(sessionId)
        }

        // session 1: tester selects fallback intrinsics, hides the legacy overlay, expands the HUD and
        // its controls.
        composeTestRule.runOnUiThread {
            controls.predictedStarIntrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK
            controls.showLegacyOverlay = false
            controls.hudDetailsExpanded = true
            controls.predictedStarControlsExpanded = true
        }
        composeTestRule.waitForIdle()
        assertEquals(PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK, controls.predictedStarIntrinsicsMode)
        assertEquals(false, controls.showLegacyOverlay)
        assertEquals(true, controls.hudDetailsExpanded)
        assertEquals(true, controls.predictedStarControlsExpanded)

        // session ID changes to 2 (a fresh CAM-1g/CAM-2b debug session): every field resets to its
        // documented default, never carrying over session 1's choices.
        composeTestRule.runOnUiThread { sessionId = 2L }
        composeTestRule.waitForIdle()

        assertEquals(PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS, controls.predictedStarIntrinsicsMode)
        assertEquals(true, controls.showLegacyOverlay)
        assertEquals(false, controls.hudDetailsExpanded)
        assertEquals(false, controls.predictedStarControlsExpanded)
        assertEquals(true, controls.showPredictedStarMarkers)
        assertEquals(true, controls.showPredictedStarPanel)
    }

    private companion object {
        val HOST_WIDTH = 400.dp
        val HOST_HEIGHT = 850.dp
    }
}
