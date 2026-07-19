package dev.pointtosky.mobile.ar.camera

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [FrameContentExperimentLiveOverlay] - the Freeze semantics correctness fix (P1).
 * A prior revision stored a whole frozen [FrameContentExperimentSessionState] locally while the
 * placement/distance reducers kept patching the *live* session's own snapshot, so Copy/Share could export
 * a value that disagreed with what a device operator saw on screen after Freeze. These tests exercise the
 * fixed contract directly: Freeze pins one exact [FrameContentCorrespondenceSnapshot], placement/distance
 * controls disable while frozen, Copy/Share always read the displayed (frozen-or-live) snapshot, and
 * Resume immediately shows whatever is live now - mirroring
 * [PhysicalCameraExperimentLiveOverlayUiTest]'s own conventions for the sibling physical-camera
 * experiment, including exercising the overlay directly (never through a real camera bind, which this
 * container has no hardware for).
 */
@RunWith(AndroidJUnit4::class)
class FrameContentExperimentLiveOverlayUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    private fun physicalSnapshot() =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(2.55f),
            sensorPhysicalWidthMm = 6.4f,
            sensorPhysicalHeightMm = 4.8f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4032,
            activeArrayBottomPx = 3024,
            pixelArrayWidthPx = 4032,
            pixelArrayHeightPx = 3024,
            isLogicalMultiCamera = false,
            cameraId = "3",
        )

    private fun dualBinding() =
        DualBasisBindingResolution(
            binding =
                PhysicalCameraBindingResolution.Bound(
                    provenance =
                        PhysicalCameraProvenance(
                            logicalCameraId = "0",
                            physicalCameraId = "3",
                            bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                            bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
                            confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                        ),
                    physicalCharacteristicsSnapshot = physicalSnapshot(),
                ),
            openedLogicalCamera = OpenedLogicalCameraSnapshotResolution.Unavailable("not captured in this fixture"),
        )

    private fun frame() =
        CameraFrameMetadata(
            timestampNanos = 0L,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            rotationDegrees = 0,
            cropRectLeftPx = 0,
            cropRectTopPx = 0,
            cropRectRightPx = bufferWidthPx,
            cropRectBottomPx = bufferHeightPx,
            sensorToBufferTransform =
                predictCameraX142SensorToBufferMatrix(CameraBasisRect(0, 0, 4032, 3024), bufferWidthPx, bufferHeightPx)!!.matrix,
        )

    private fun emptyDetection() = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("no target in this fixture", 0)

    /** A session with a resolved binding and one analyzed frame - `latestSnapshot` is non-null (a real
     * [FrameContentCorrespondenceSnapshot], generation 1). */
    private fun sessionWithOneSnapshot(attemptId: Long = 1L): FrameContentExperimentSessionState {
        var state = initialFrameContentExperimentSessionState(attemptId = attemptId, physicalCameraId = "3")
        state = state.reduceBindingResolved(attemptId, dualBinding(), 1.0f, 1.0f, 0L)
        state = state.reduceFrame(attemptId, frame(), emptyDetection(), 100L)
        return state
    }

    @Test
    fun freezingPinsTheDisplayedSnapshotAndDisablesPlacementAndDistanceControls() {
        var liveState by mutableStateOf(sessionWithOneSnapshot())
        composeTestRule.setContent {
            FrameContentExperimentLiveOverlay(
                state = liveState,
                onUpdateSession = { attemptId, reducer -> if (liveState.attemptId == attemptId) liveState = reducer(liveState) },
            )
        }
        val snapshotA = liveState.latestSnapshot!!
        assertEquals(1L, snapshotA.generation)

        composeTestRule.onNodeWithTag(TAG_FREEZE).performClick()
        composeTestRule.onNodeWithTag(TAG_PLACEMENT_PREFIX + TargetPlacementLabel.BOTTOM_RIGHT.name).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_DISTANCE_INPUT).assertIsNotEnabled()

        // A live callback publishes a second, different snapshot (generation 2) while frozen.
        composeTestRule.runOnUiThread {
            liveState = liveState.reduceFrame(liveState.attemptId, frame(), emptyDetection(), 200L)
        }
        composeTestRule.waitForIdle()
        val snapshotB = liveState.latestSnapshot!!
        assertNotEquals(snapshotA.generation, snapshotB.generation)

        // The displayed report is still snapshot A's, never snapshot B's.
        composeTestRule.onNodeWithTag(TAG_REPORT).assert(hasText("generation=${snapshotA.generation}", substring = true))
        composeTestRule.onNodeWithTag(TAG_LIVE_SUMMARY)
            .assert(hasText("liveFramesObserved=2", substring = true))
            .assert(hasText("displayedGeneration=${snapshotA.generation}", substring = true))
            .assert(hasText("liveness=FROZEN", substring = true))

        // Copy report writes the frozen snapshot A's text, never the now-live snapshot B's.
        composeTestRule.onNodeWithTag(TAG_COPY).performClick()
        composeTestRule.waitForIdle()
        val clipboardManager = composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clippedText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(buildFrameContentCorrespondenceReportText(snapshotA), clippedText)
        assertNotEquals(buildFrameContentCorrespondenceReportText(snapshotB), clippedText)

        // The rendered report node is the same displayedSnapshot Copy report/Share JSON both read - proof
        // Share JSON would use the identical frozen value without needing to launch a real chooser.
        composeTestRule.onNodeWithTag(TAG_REPORT).assert(hasText(buildFrameContentCorrespondenceReportText(snapshotA), substring = false))
    }

    @Test
    fun resumeSwitchesBackToTheLatestLiveSnapshot() {
        var liveState by mutableStateOf(sessionWithOneSnapshot())
        composeTestRule.setContent {
            FrameContentExperimentLiveOverlay(
                state = liveState,
                onUpdateSession = { attemptId, reducer -> if (liveState.attemptId == attemptId) liveState = reducer(liveState) },
            )
        }

        composeTestRule.onNodeWithTag(TAG_FREEZE).performClick()
        composeTestRule.runOnUiThread {
            liveState = liveState.reduceFrame(liveState.attemptId, frame(), emptyDetection(), 200L)
        }
        composeTestRule.waitForIdle()
        val snapshotB = liveState.latestSnapshot!!

        composeTestRule.onNodeWithTag(TAG_FREEZE).assert(hasText("Resume live")).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PLACEMENT_PREFIX + TargetPlacementLabel.BOTTOM_RIGHT.name).assertIsEnabled()
        composeTestRule.onNodeWithTag(TAG_DISTANCE_INPUT).assertIsEnabled()
        composeTestRule.onNodeWithTag(TAG_LIVE_SUMMARY)
            .assert(hasText("displayedGeneration=${snapshotB.generation}", substring = true))
            .assert(hasText("liveness=LIVE", substring = true))
        composeTestRule.onNodeWithTag(TAG_REPORT).assert(hasText("generation=${snapshotB.generation}", substring = true))
    }

    @Test
    fun editingPlacementWhileLiveIsReflectedConsistentlyInTheReport() {
        var liveState by mutableStateOf(sessionWithOneSnapshot())
        composeTestRule.setContent {
            FrameContentExperimentLiveOverlay(
                state = liveState,
                onUpdateSession = { attemptId, reducer -> if (liveState.attemptId == attemptId) liveState = reducer(liveState) },
            )
        }

        composeTestRule.onNodeWithTag(TAG_PLACEMENT_PREFIX + TargetPlacementLabel.BOTTOM_RIGHT.name).performClick()
        composeTestRule.waitForIdle()

        assertEquals(TargetPlacementLabel.BOTTOM_RIGHT, liveState.targetPlacementLabel)
        assertEquals(TargetPlacementLabel.BOTTOM_RIGHT, liveState.latestSnapshot!!.targetPlacementLabel)
        composeTestRule.onNodeWithTag(TAG_REPORT).assert(hasText("targetPlacementLabel=BOTTOM_RIGHT", substring = true))
    }

    @Test
    fun freezeIsDisabledWhenThereIsNoSnapshotYetToFreeze() {
        val stateWithoutSnapshot = initialFrameContentExperimentSessionState(attemptId = 9L, physicalCameraId = "3")
        composeTestRule.setContent {
            FrameContentExperimentLiveOverlay(state = stateWithoutSnapshot, onUpdateSession = { _, _ -> })
        }
        composeTestRule.onNodeWithTag(TAG_FREEZE).assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_COPY).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(TAG_SHARE_JSON).assertIsNotEnabled()
    }
}
