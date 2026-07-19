package dev.pointtosky.mobile.ar.camera

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [PhysicalCameraExperimentLiveOverlay] - the live (non-terminal) attempt's fixed
 * action header + scrollable report body (mobile-usability fix, task §16's device evidence workflow). A
 * prior revision put Freeze/Resume, Copy report, and Share JSON inside the same `verticalScroll`ed
 * `Column` as the report text itself, so a long report could push those controls off-screen - these
 * tests exercise the fixed two-region layout that replaced it, using a synthetic, deliberately long
 * report/session state and a deliberately small viewport (well under a typical device screen) so the
 * prior defect, if reintroduced, would fail these tests.
 *
 * [PhysicalCameraExperimentLiveOverlay] is exercised directly (never through
 * [PhysicalCameraBindingSession]/[PhysicalCameraBindingExperimentScreen]) specifically so these tests
 * never need to bind a real [dev.pointtosky.mobile.ar.CameraPreview], which requires camera hardware this
 * container does not have (see `docs/validation/cam_2c_pixel9_evidence.md`) - see that composable's own
 * KDoc for why it was extracted.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalCameraExperimentLiveOverlayUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = 640.0 / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 480.0 / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun frame(timestampNanos: Long) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            rotationDegrees = 0,
            sensorToBufferTransform = fullFrameTransform,
        )

    private fun boundResolution(physicalCameraId: String) =
        PhysicalCameraBindingResolution.Bound(
            provenance =
                PhysicalCameraProvenance(
                    logicalCameraId = "0",
                    physicalCameraId = physicalCameraId,
                    bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                    bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                    confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                ),
            physicalCharacteristicsSnapshot =
                CameraCharacteristicsSnapshot(
                    availableFocalLengthsMm = floatArrayOf(3.6f),
                    sensorPhysicalWidthMm = 6.4f,
                    sensorPhysicalHeightMm = 4.8f,
                    activeArrayLeftPx = 0,
                    activeArrayTopPx = 0,
                    activeArrayRightPx = 4032,
                    activeArrayBottomPx = 3024,
                    pixelArrayWidthPx = 4032,
                    pixelArrayHeightPx = 3024,
                    isLogicalMultiCamera = false,
                    cameraId = physicalCameraId,
                ),
        )

    /** A session with a resolved binding and many observed frames - the dual-basis matrix assessment
     * and matrix stability sections this produces make [buildPhysicalCameraExperimentReportText]'s
     * output long enough to overflow the small viewport [SmallScreenHost] constrains tests to, exactly
     * the shape the mobile-usability defect this layout fixes needed to reproduce. */
    private fun longReportState(
        attemptId: Long = 1L,
        physicalCameraId: String = "2",
        frameCount: Int = 50,
    ): ExperimentSessionState {
        var state = initialExperimentSessionState(attemptId = attemptId, physicalCameraId = physicalCameraId)
        state = state.reduceCameraInfoResolved(attemptId, boundResolution(physicalCameraId))
        repeat(frameCount) { i -> state = state.reduceFrame(attemptId, frame(timestampNanos = i.toLong())) }
        return state
    }

    /** Constrains the overlay to a small, fixed viewport - well under a typical device screen - so the
     * fixed header must genuinely stay fixed (not merely fit by accident on a tall test window). */
    @Composable
    private fun SmallScreenHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(320.dp, 400.dp)) {
            content()
        }
    }

    @Test
    fun freezeActionIsDisplayedOnSessionStart() {
        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = longReportState())
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze")
            .assertIsDisplayed()
            .assert(hasText("Freeze"))
    }

    @Test
    fun copyReportAndShareJsonAreDisplayedSimultaneously() {
        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = longReportState())
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_copy")
            .assertIsDisplayed()
            .assert(hasText("Copy report"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_share_json")
            .assertIsDisplayed()
            .assert(hasText("Share JSON"))
    }

    @Test
    fun aVeryLongReportLeavesAllThreeActionsVisibleWithoutScrollingOnASmallScreen() {
        // Task requirement: "After supplying a very long report/session state, all three action
        // controls still exist and are visible in the semantics tree without scrolling." No
        // performScrollToNode is used here at all - the prior single-shared-scroll layout would have
        // required one to reach these nodes; the fixed header no longer does.
        composeTestRule.setContent {
            SmallScreenHost {
                PhysicalCameraExperimentLiveOverlay(state = longReportState(frameCount = 200))
            }
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_action_header").assertIsDisplayed()
        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").assertIsDisplayed()
        composeTestRule.onNodeWithTag("physical_camera_experiment_copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("physical_camera_experiment_share_json").assertIsDisplayed()
    }

    @Test
    fun compactSummaryShowsPhysicalIdAttemptIdStatusAndFrameCount() {
        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = longReportState(attemptId = 7L, physicalCameraId = "3", frameCount = 12))
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_compact_summary")
            .assertIsDisplayed()
            .assert(hasText("physicalId=3", substring = true))
            .assert(hasText("attemptId=7", substring = true))
            .assert(hasText("status=BOUND", substring = true))
            .assert(hasText("frames=12", substring = true))
    }

    @Test
    fun theReportScrollContainerIsScrollableAndCarriesTheReportText() {
        composeTestRule.setContent {
            SmallScreenHost {
                PhysicalCameraExperimentLiveOverlay(state = longReportState(frameCount = 200))
            }
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_report_scroll").assert(hasScrollAction())
        composeTestRule.onNodeWithTag("physical_camera_experiment_report")
            .assert(hasText("CAM-2c PHYSICAL CAMERA BINDING EXPERIMENT", substring = true))
    }

    @Test
    fun tappingFreezeChangesTheLabelToResumeLiveAndBackAgain() {
        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = longReportState())
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze")
            .assert(hasText("Freeze"))
            .performClick()
        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze")
            .assertIsDisplayed()
            .assert(hasText("Resume live"))

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").performClick()
        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze")
            .assertIsDisplayed()
            .assert(hasText("Freeze"))
    }

    @Test
    fun copyReportStillWritesTheFrozenStateToTheClipboardAfterTheLiveStateAdvances() {
        // Freeze at the state with `frameCount = 50`, then advance the composable's own live `state`
        // input further (more frames observed) - Copy report must still write the *frozen* moment's
        // text to the clipboard, never the now-live value. Uses the real Android ClipboardManager -
        // exactly what `copyCamDiagnosticTextToClipboard` itself writes to, never a semantics-tree
        // scrape.
        var liveState by mutableStateOf(longReportState(frameCount = 50))

        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = liveState)
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").performClick()
        val frozenReportText = buildPhysicalCameraExperimentReportText(liveState)
        val laterReportText =
            buildPhysicalCameraExperimentReportText(liveState.reduceFrame(liveState.attemptId, frame(999L)))
        assertNotEquals(
            "the fixture must actually change between freeze and the later Copy tap, or this test " +
                "cannot distinguish frozen from live",
            frozenReportText,
            laterReportText,
        )

        composeTestRule.runOnUiThread {
            liveState = liveState.reduceFrame(liveState.attemptId, frame(999L))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("physical_camera_experiment_copy").performClick()
        composeTestRule.waitForIdle()

        val clipboardManager =
            composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clippedText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(frozenReportText, clippedText)

        // The rendered report node - the same `displayedState` Copy report/Share JSON both read - is
        // proof Share JSON would use the identical frozen value, without needing to launch a real share
        // chooser Activity from this test.
        composeTestRule.onNodeWithTag("physical_camera_experiment_report")
            .assert(hasText(frozenReportText, substring = false))
    }

    @Test
    fun aNewAttemptIdResetsFrozenStateBackToFreeze() {
        // Task: "a new attempt/generation cannot inherit a frozen snapshot." `frozenState` is
        // `remember(state.attemptId)`-keyed inside [PhysicalCameraExperimentLiveOverlay] itself, so
        // simply advancing `attemptId` on the composable's live input - exactly what
        // `PhysicalCameraBindingSession`'s own `key(session.attemptId)` wrapper guarantees a fresh
        // attempt always does - must reset it, with no extra `key(...)` wrapper needed in this test.
        var liveState by mutableStateOf(longReportState(attemptId = 1L))

        composeTestRule.setContent {
            PhysicalCameraExperimentLiveOverlay(state = liveState)
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").performClick()
        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").assert(hasText("Resume live"))

        composeTestRule.runOnUiThread {
            liveState = longReportState(attemptId = 2L)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("physical_camera_experiment_freeze").assert(hasText("Freeze"))
    }
}
