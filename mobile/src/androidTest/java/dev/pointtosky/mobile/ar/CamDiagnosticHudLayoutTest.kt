package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the physical-device diagnostic-layout fix: the shared CAM-1g/CAM-2b HUD
 * ([CamDiagnosticTopPanels]) and the collapsible [PredictedStarOverlayControls] card. Fixture values
 * below (buffer 640x480, viewport 1080x2424, rotationDegrees 90, ~+3.7ms pair delta, `READY_CALIBRATED`/
 * `CAMERA_CHARACTERISTICS`, FOV 70.7x56.2, scale 3.788, offset -369,0) mirror the physical Pixel 9
 * observation this fix addresses. Not pixel-perfect screenshot tests - this repository does not use
 * those.
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

    /**
     * A bounded (not unbounded/infinite) host so [CamDiagnosticTopPanels]'s internal
     * `BoxWithConstraints` has real constraints to compute its scroll bound against - tall enough that
     * neither fixture's full panel text needs to scroll out of view for these tests, so `isDisplayed`
     * checks below assert full-text presence rather than partial-clip visibility.
     */
    @Composable
    private fun BoundedHudHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(400.dp, 1600.dp)) {
            content()
        }
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
                )
            }
        }

        composeTestRule.onNodeWithTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertIsDisplayed()

        val cam1gBounds = composeTestRule.onNodeWithTag(CAMERA_GEOMETRY_DIAGNOSTIC_OVERLAY_TEST_TAG).getUnclippedBoundsInRoot()
        val cam2bBounds = composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).getUnclippedBoundsInRoot()

        // Stacked vertically in one shared column - never two independently-aligned overlays
        // occupying (and colliding within) the same top corner.
        assertTrue(
            "expected CAM-2b panel to start at or below CAM-1g panel's bottom edge, " +
                "was cam1g=$cam1gBounds cam2b=$cam2bBounds",
            cam2bBounds.top >= cam1gBounds.bottom,
        )
    }

    @Test
    fun collapsedControlsRemainPresentAndCompact() {
        composeTestRule.setContent {
            var showLegacyOverlay by remember { mutableStateOf(true) }
            PredictedStarOverlayControls(
                showMarkers = true,
                onShowMarkersChange = {},
                showPanel = true,
                onShowPanelChange = {},
                intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                onIntrinsicsModeChange = {},
                showLegacyOverlay = showLegacyOverlay,
                onShowLegacyOverlayChange = { showLegacyOverlay = it },
            )
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Session intrinsics").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Diagnostic fallback").assertCountEquals(0)
    }

    @Test
    fun expandingControlsExposesBothIntrinsicsModes() {
        composeTestRule.setContent {
            var showLegacyOverlay by remember { mutableStateOf(true) }
            PredictedStarOverlayControls(
                showMarkers = true,
                onShowMarkersChange = {},
                showPanel = true,
                onShowPanelChange = {},
                intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                onIntrinsicsModeChange = {},
                showLegacyOverlay = showLegacyOverlay,
                onShowLegacyOverlayChange = { showLegacyOverlay = it },
            )
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG).performClick()

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_BODY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Session intrinsics").assertIsDisplayed()
        composeTestRule.onNodeWithText("Diagnostic fallback").assertIsDisplayed()
    }

    @Test
    fun legacyOverlayToggleInvokesCallbackWithNewValue() {
        var lastValue: Boolean? = null
        composeTestRule.setContent {
            var showLegacyOverlay by remember { mutableStateOf(true) }
            PredictedStarOverlayControls(
                showMarkers = true,
                onShowMarkersChange = {},
                showPanel = true,
                onShowPanelChange = {},
                intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                onIntrinsicsModeChange = {},
                showLegacyOverlay = showLegacyOverlay,
                onShowLegacyOverlayChange = {
                    showLegacyOverlay = it
                    lastValue = it
                },
            )
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_CONTROLS_HEADER_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_SHOW_LEGACY_SWITCH_TEST_TAG).performClick()

        assertEquals(false, lastValue)
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
                )
            }
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
    }

    @Test
    fun cam2bPanelTextFullyPresentInDiagnosticFallbackReadyState() {
        val fallbackReady =
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

        composeTestRule.setContent {
            BoundedHudHost {
                CamDiagnosticTopPanels(
                    cam1gSnapshot = cam1gSnapshot,
                    cam1gSessionId = 1L,
                    cam1gStatusTransitionCount = 0,
                    cam1gObservedFrameCount = 10L,
                    cam1gReadyBundleCount = 10L,
                    cam2bState = fallbackReady,
                )
            }
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("status: ready", substring = true))
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(640x480)", substring = true))
    }
}
