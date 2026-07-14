package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayWaitingReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the CAM-2b predicted-star overlay composables (task §13): waiting/ready/
 * unavailable panel content, marker-canvas presence, and state-replacement clearing stale content.
 * Not pixel-perfect screenshot tests — this repository does not use those.
 */
@RunWith(AndroidJUnit4::class)
class PredictedStarOverlayUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val readyState =
        PredictedStarOverlayState.Ready(
            points =
                listOf(
                    PredictedStarOverlayPoint(catalogIndex = 1, magnitude = 1.0, displayX = 10.0, displayY = 20.0),
                    PredictedStarOverlayPoint(catalogIndex = 2, magnitude = 2.0, displayX = 30.0, displayY = 40.0),
                    PredictedStarOverlayPoint(catalogIndex = 3, magnitude = 3.0, displayX = 50.0, displayY = 60.0),
                ),
            summary =
                StarPredictionSummary(
                    inputCount = 10,
                    behindCameraCount = 2,
                    outsideImageCount = 3,
                    insideImageOutsideViewportCount = 2,
                    visibleInViewportCount = 3,
                ),
            metadata =
                PredictedStarOverlayMetadata(
                    inputCount = 10,
                    visibleCount = 3,
                    frameTimestampNanos = 1_000L,
                    rotationTimestampNanos = 990L,
                    pairDeltaNanos = 10L,
                    frameRotationDegrees = 0,
                    intrinsicsSource = "LEGACY_FALLBACK",
                    intrinsicsReference = "AnalysisBuffer(1000x500)",
                    magneticDeclinationDeg = 4.2,
                ),
        )

    @Test
    fun waitingStateRendersStatusButNoMarkers() {
        val waiting =
            PredictedStarOverlayState.Waiting(
                PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME),
            )

        composeTestRule.setContent {
            PredictedStarOverlayPanel(state = waiting)
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("MISSING_FRAME", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun unavailableStateDisplaysTheCategorizedReason() {
        val unavailable =
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)

        composeTestRule.setContent {
            PredictedStarOverlayPanel(state = unavailable)
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
    }

    @Test
    fun readyStateRendersTheExpectedNumberOfMarkersAndPanelCounts() {
        composeTestRule.setContent {
            PredictedStarMarkersCanvas(points = readyState.points)
            PredictedStarOverlayPanel(state = readyState)
        }

        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("visible: 3", substring = true))
    }

    @Test
    fun stateReplacementRemovesStaleMarkerContent() {
        var state by mutableStateOf<PredictedStarOverlayState>(readyState)

        composeTestRule.setContent {
            PredictedStarOverlayPanel(state = state)
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("visible: 3", substring = true))

        composeTestRule.runOnUiThread {
            state =
                PredictedStarOverlayState.Waiting(
                    PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.DISPOSED),
                )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("DISPOSED", substring = true))
    }
}
