package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
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
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayWaitingReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A small test-only host mirroring exactly how `ArScreen.kt` composes the CAM-2b overlay: the marker
 * canvas only when [state] is [PredictedStarOverlayState.Ready], the status panel always. Used so UI
 * tests can verify stale-marker-layer removal (the canvas disappearing on a state transition), not just
 * panel text replacement.
 */
@Composable
private fun PredictedStarOverlayTestHost(state: PredictedStarOverlayState) {
    if (state is PredictedStarOverlayState.Ready) {
        PredictedStarMarkersCanvas(points = state.points)
    }
    PredictedStarOverlayPanel(state = state)
}

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
        PredictedStarOverlayState.Ready.of(
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
                    intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                    sessionIntrinsicsSource = "LEGACY_FALLBACK",
                    sessionIntrinsicsReference = "AnalysisBuffer(1000x500)",
                    projectionIntrinsicsSource = "LEGACY_FALLBACK",
                    projectionIntrinsicsReference = "AnalysisBuffer(1000x500)",
                    magneticDeclinationDeg = 4.2,
                ),
        )

    /**
     * Pure fixture check, not a Compose assertion: pins that [readyState] actually carries 3 drawable
     * points — the fact backing `readyStateRendersMarkerCanvasAndPanelCounts`'s single-canvas-node
     * assertion below. A Compose `Canvas` is one semantics node regardless of how many circles it
     * draws internally, so marker *count* must be verified here, at the plain state/points boundary,
     * not by counting semantics nodes.
     */
    @Test
    fun readyStatePointsCountMatchesConstructedFixture() {
        assertEquals(3, readyState.points.size)
    }

    @Test
    fun waitingStateRendersStatusButNoMarkers() {
        val waiting =
            PredictedStarOverlayState.Waiting(
                PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME),
            )

        composeTestRule.setContent {
            PredictedStarOverlayTestHost(state = waiting)
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
            PredictedStarOverlayTestHost(state = unavailable)
        }

        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED", substring = true))
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun readyStateRendersMarkerCanvasAndPanelCounts() {
        composeTestRule.setContent {
            PredictedStarOverlayTestHost(state = readyState)
        }

        // One Canvas node is present regardless of how many markers it draws internally - marker
        // *count* is verified separately at the pure state boundary, see
        // readyStatePointsCountMatchesConstructedFixture above.
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("visible: 3", substring = true))
    }

    @Test
    fun stateReplacementRemovesStaleMarkerContent() {
        var state by mutableStateOf<PredictedStarOverlayState>(readyState)

        composeTestRule.setContent {
            PredictedStarOverlayTestHost(state = state)
        }

        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("visible: 3", substring = true))

        composeTestRule.runOnUiThread {
            state =
                PredictedStarOverlayState.Waiting(
                    PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.DISPOSED),
                )
        }
        composeTestRule.waitForIdle()

        // The marker layer itself must disappear, not just its text description - this is what
        // distinguishes "stale marker-layer removal" from a pure text-replacement check.
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(hasText("DISPOSED", substring = true))
        composeTestRule.onNodeWithTag(PREDICTED_STAR_OVERLAY_PANEL_TEST_TAG)
            .assert(!hasText("visible: 3", substring = true))
    }
}
