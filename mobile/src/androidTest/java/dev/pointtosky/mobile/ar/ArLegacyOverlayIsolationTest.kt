package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayWaitingReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [androidx.compose.ui.platform.testTag] for this test file's stand-in legacy content. */
private const val FAKE_LEGACY_CONTENT_TEST_TAG = "fake_legacy_content"

/**
 * Mirrors `ArScreen.kt`'s real composition shape in its `ArUiState.Ready` branch (see that file): the
 * legacy sky overlay is gated by exactly one `if (showLegacyOverlay) { LegacySkyOverlay(...) }` boundary,
 * while the CAM-2b marker canvas, the CAM-1g/CAM-2b HUD, and the central [Reticle] are composed
 * unconditionally, outside that boundary. This proves the *parent rendering decision* - the same
 * `LegacySkyOverlay` gate function `ArScreen` calls, composed the same way `ArScreen` calls it - not just
 * the "Show legacy overlay" [androidx.compose.material3.Switch]'s own callback
 * ([PredictedStarOverlayControlsBody]'s `onShowLegacyOverlayChange`, already covered by
 * `CamDiagnosticHudLayoutTest.legacyOverlayToggleInvokesCallbackWithNewValue`).
 *
 * [FAKE_LEGACY_CONTENT_TEST_TAG] stands in for the real legacy visuals `ArScreen` composes inside
 * [LegacySkyOverlay] (`StarPointLayer`/`ConstellationLayer`/`ArObjectLabel`/`AsterismLabel`/
 * `ReticleTargetHighlight`): those all depend on the internal `OverlayData` type, which this
 * `androidTest` source set cannot construct (no friend-module access to `main`'s `internal`
 * declarations - the same reason `calculateOverlay`/`OverlayData` are only exercised from the `test`
 * source set, see `ArOverlayScenarioTest`). [LegacySkyOverlay] is deliberately generic (a plain content
 * lambda, no `OverlayData` in its own signature) precisely so the *gating mechanism itself* - the thing
 * this test proves - is directly testable here regardless.
 *
 * All layers share one root `Box(Modifier.fillMaxSize())`, exactly like `ArScreen`'s own root `Box` -
 * never several independently-sized top-level composables. [PredictedStarMarkersCanvas] and
 * [LegacySkyOverlay] are each given `Modifier.fillMaxSize()` (as `ArScreen` itself passes), since neither
 * assigns its own dimensions - an unsized `Canvas`/`Box` measures to 0x0 inside `ComponentActivity`'s
 * root and would fail `assertIsDisplayed` (a real, non-zero layout bounds check) even though the node
 * still exists in the semantics tree. [Reticle] is centered the same way `ArScreen` centers it.
 */
@Composable
private fun ArLegacyOverlayIsolationTestHost(
    showLegacyOverlay: Boolean,
    cam2bState: PredictedStarOverlayState,
) {
    Box(Modifier.fillMaxSize()) {
        if (showLegacyOverlay) {
            LegacySkyOverlay(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.testTag(FAKE_LEGACY_CONTENT_TEST_TAG).size(4.dp))
            }
        }

        if (cam2bState is PredictedStarOverlayState.Ready) {
            PredictedStarMarkersCanvas(
                points = cam2bState.points,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Reticle(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .testTag(AR_RETICLE_TEST_TAG),
        )

        CamDiagnosticTopPanels(
            cam1gSnapshot = null,
            cam1gSessionId = 1L,
            cam1gStatusTransitionCount = 0,
            cam1gObservedFrameCount = 0L,
            cam1gReadyBundleCount = 0L,
            cam2bState = cam2bState,
            detailsExpanded = false,
            onDetailsExpandedChange = {},
            controlsExpanded = false,
            onControlsExpandedChange = {},
            controlsContent = {},
        )
    }
}

/**
 * Compose UI tests proving full legacy-sky-overlay isolation (HUD-visibility follow-up §4): with the
 * toggle off, every legacy-tagged node disappears while the CAM-2b marker canvas, the CAM-1g/CAM-2b HUD,
 * and the central reticle all remain.
 */
@RunWith(AndroidJUnit4::class)
class ArLegacyOverlayIsolationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val readyState =
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

    @Test
    fun legacyOverlayOffHidesLegacyContentButKeepsCam2bMarkersHudAndReticle() {
        composeTestRule.setContent {
            ArLegacyOverlayIsolationTestHost(showLegacyOverlay = false, cam2bState = readyState)
        }

        // Every legacy-tagged node - the wrapper boundary itself, and the content composed inside it -
        // must be entirely absent from the semantics tree, not merely invisible/alpha-0.
        composeTestRule.onAllNodesWithTag(LEGACY_SKY_OVERLAY_TEST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(FAKE_LEGACY_CONTENT_TEST_TAG).assertCountEquals(0)

        // CAM-2b marker canvas remains present for a Ready state.
        composeTestRule.onNodeWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertIsDisplayed()
        // CAM-2b/CAM-1g HUD remains present.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG).assertIsDisplayed()
        // Documented reticle policy: the central aiming reticle is an independent aiming reference,
        // never part of the legacy sky overlay - always present regardless of the toggle.
        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun legacyOverlayOnShowsLegacyContentAlongsideCam2bMarkersHudAndReticle() {
        composeTestRule.setContent {
            ArLegacyOverlayIsolationTestHost(showLegacyOverlay = true, cam2bState = readyState)
        }

        composeTestRule.onNodeWithTag(LEGACY_SKY_OVERLAY_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(FAKE_LEGACY_CONTENT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_HUD_PANELS_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun legacyOverlayOffWithNoCam2bReadyStateStillHidesLegacyContent() {
        // A Waiting cam2bState (no marker canvas at all) must not change legacy-gating behavior -
        // proves the two gates (showLegacyOverlay, cam2bState is Ready) are independent of each other.
        composeTestRule.setContent {
            ArLegacyOverlayIsolationTestHost(
                showLegacyOverlay = false,
                cam2bState = PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable),
            )
        }

        composeTestRule.onAllNodesWithTag(LEGACY_SKY_OVERLAY_TEST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(PREDICTED_STAR_MARKERS_CANVAS_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM2B_SUMMARY_TEST_TAG)
            .assertIsDisplayed()
    }
}
