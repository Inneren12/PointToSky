package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** State-machine tests (task §8): freeze/generation atomicity, late-callback rejection, and clean
 * new-attempt resets — mirroring [ExperimentSessionStateTest]'s own conventions for the sibling
 * physical-camera experiment. */
class FrameContentCorrespondenceSessionStateTest {
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

    private fun boundBinding() =
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
        )

    private fun dualBinding() =
        DualBasisBindingResolution(
            binding = boundBinding(),
            openedLogicalCamera = OpenedLogicalCameraSnapshotResolution.Unavailable("not captured in this fixture"),
        )

    private fun frame(bufferWidthPx: Int = 640, bufferHeightPx: Int = 480) =
        CameraFrameMetadata(
            timestampNanos = 0L,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            rotationDegrees = 0,
            cropRectLeftPx = 0,
            cropRectTopPx = 0,
            cropRectRightPx = bufferWidthPx,
            cropRectBottomPx = bufferHeightPx,
            sensorToBufferTransform = predictCameraX142SensorToBufferMatrix(CameraBasisRect(0, 0, 4032, 3024), bufferWidthPx, bufferHeightPx)!!.matrix,
        )

    private fun emptyDetection() = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("no target in this fixture", 0)

    @Test
    fun `a late binding callback from a superseded attemptId is a true no-op`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
        val afterStaleCallback = state.reduceBindingResolved(attemptId = 0L, dualBinding = dualBinding(), zoomTargetRatio = 1.0f, observedZoomRatio = 1.0f, capturedAtEpochMillis = 0L)
        assertSame(state, afterStaleCallback)
    }

    @Test
    fun `a late frame callback from a superseded attemptId is a true no-op`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 5L, physicalCameraId = "3")
        val afterStaleFrame = state.reduceFrame(attemptId = 4L, frame = frame(), detection = emptyDetection(), capturedAtEpochMillis = 0L)
        assertSame(state, afterStaleFrame)
    }

    @Test
    fun `a terminally failed session ignores every further reducer call`() {
        val state =
            initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
                .reduceExplicitBindFailure(1L, "explicit_selector_illegal_state")
        val afterBinding = state.reduceBindingResolved(1L, dualBinding(), 1.0f, 1.0f, 0L)
        val afterFrame = state.reduceFrame(1L, frame(), emptyDetection(), 0L)
        assertSame(state, afterBinding)
        assertSame(state, afterFrame)
    }

    @Test
    fun `snapshot generation advances once per analyzed frame within the same attempt`() {
        var state = initialFrameContentExperimentSessionState(attemptId = 2L, physicalCameraId = "3")
        state = state.reduceBindingResolved(2L, dualBinding(), 1.0f, 1.0f, 0L)
        assertEquals(0L, state.framesObserved)

        state = state.reduceFrame(2L, frame(), emptyDetection(), 100L)
        assertEquals(1L, state.framesObserved)
        assertNotNull(state.latestSnapshot)
        assertEquals(1L, state.latestSnapshot!!.generation)

        state = state.reduceFrame(2L, frame(), emptyDetection(), 200L)
        assertEquals(2L, state.framesObserved)
        assertEquals(2L, state.latestSnapshot!!.generation)
    }

    @Test
    fun `snapshot stays null until both binding and a frame are present, regardless of order`() {
        val bindingFirst =
            initialFrameContentExperimentSessionState(attemptId = 3L, physicalCameraId = "3")
                .reduceBindingResolved(3L, dualBinding(), 1.0f, 1.0f, 0L)
        assertNull(bindingFirst.latestSnapshot)
        val bindingThenFrame = bindingFirst.reduceFrame(3L, frame(), emptyDetection(), 0L)
        assertNotNull(bindingThenFrame.latestSnapshot)

        val frameFirst =
            initialFrameContentExperimentSessionState(attemptId = 4L, physicalCameraId = "3")
                .reduceFrame(4L, frame(), emptyDetection(), 0L)
        assertNull(frameFirst.latestSnapshot)
        val frameThenBinding = frameFirst.reduceBindingResolved(4L, dualBinding(), 1.0f, 1.0f, 0L)
        assertNotNull(frameThenBinding.latestSnapshot)
    }

    @Test
    fun `target placement label and distance updates are attemptId-scoped like every other reducer`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 6L, physicalCameraId = "3")
        val updated = state.reduceTargetPlacementLabel(6L, TargetPlacementLabel.TOP_LEFT).reduceDistanceLabel(6L, 300.0)
        assertEquals(TargetPlacementLabel.TOP_LEFT, updated.targetPlacementLabel)
        assertEquals(300.0, updated.distanceLabelMm)

        val ignored = updated.reduceTargetPlacementLabel(5L, TargetPlacementLabel.BOTTOM_RIGHT)
        assertSame(updated, ignored)
    }

    /** Task §3's chosen, documented behavior: editing placement/distance after a snapshot already
     * exists immediately patches that exact snapshot's metadata (same generation, same frame/
     * detection/pose/hypotheses/residuals) — never a stale live-UI value that would export
     * differently via Copy/Share. */
    @Test
    fun `editing placement or distance after a snapshot exists patches that snapshot immediately at the same generation`() {
        var state = initialFrameContentExperimentSessionState(attemptId = 7L, physicalCameraId = "3")
        state = state.reduceBindingResolved(7L, dualBinding(), 1.0f, 1.0f, 0L)
        state = state.reduceFrame(7L, frame(), emptyDetection(), 100L)
        val snapshotBefore = assertNotNull(state.latestSnapshot)
        assertEquals(TargetPlacementLabel.CENTER, snapshotBefore.targetPlacementLabel)
        assertNull(snapshotBefore.distanceLabelMm)

        state = state.reduceTargetPlacementLabel(7L, TargetPlacementLabel.BOTTOM_RIGHT)
        state = state.reduceDistanceLabel(7L, 425.0)

        val snapshotAfter = assertNotNull(state.latestSnapshot)
        assertEquals(TargetPlacementLabel.BOTTOM_RIGHT, snapshotAfter.targetPlacementLabel)
        assertEquals(425.0, snapshotAfter.distanceLabelMm)
        // Same generation — this is a metadata patch, not a new analyzed frame.
        assertEquals(snapshotBefore.generation, snapshotAfter.generation)
        assertEquals(snapshotBefore.attemptId, snapshotAfter.attemptId)
        // Every other field (hypotheses, pose, residuals, verdict) is untouched by the metadata edit.
        assertEquals(snapshotBefore.hypotheses, snapshotAfter.hypotheses)
        assertEquals(snapshotBefore.verdict, snapshotAfter.verdict)
        // Copy report and Share JSON exports reflect the edit immediately (never a stale label).
        assertTrue(buildFrameContentCorrespondenceReportText(snapshotAfter).contains("BOTTOM_RIGHT"))
        assertTrue(buildFrameContentCorrespondenceJson(snapshotAfter).contains("\"targetPlacementLabel\":\"BOTTOM_RIGHT\""))
    }

    @Test
    fun `editing placement before any snapshot exists never fabricates one`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 8L, physicalCameraId = "3")
        val updated = state.reduceTargetPlacementLabel(8L, TargetPlacementLabel.TOP_RIGHT)
        assertNull(updated.latestSnapshot)
        assertEquals(TargetPlacementLabel.TOP_RIGHT, updated.targetPlacementLabel)
    }

    @Test
    fun `a new attempt resets placement and distance to documented defaults`() {
        val fresh = initialFrameContentExperimentSessionState(attemptId = 9L, physicalCameraId = "3")
        assertEquals(TargetPlacementLabel.CENTER, fresh.targetPlacementLabel)
        assertNull(fresh.distanceLabelMm)
    }
}
