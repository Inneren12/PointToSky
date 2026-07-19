package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Task §8: "a new attempt clears the old snapshot"; "Copy and Share use the same frozen snapshot" is
 * proven at the pure-function level here (both export builders take the exact same
 * [FrameContentCorrespondenceSnapshot] argument, never re-reading session state) — the Compose-level
 * freeze-then-both-read-frozen-value behavior mirrors [PhysicalCameraExperimentLiveOverlayUiTest]'s own
 * instrumented coverage and is out of scope for this pure-JVM file. */
class FrameContentCorrespondenceUiModelTest {
    @Test
    fun `startAttempt always mints a new attemptId and never reuses one`() {
        val model = FrameContentCorrespondenceUiModel()
        val first = model.startAttempt("3")
        val second = first.startAttempt("3")
        assertNotEquals(first.session!!.attemptId, second.session!!.attemptId)
        assertEquals(0L, first.session!!.attemptId)
        assertEquals(1L, second.session!!.attemptId)
    }

    @Test
    fun `a new attempt clears the previous attempt's snapshot`() {
        var model = FrameContentCorrespondenceUiModel().startAttempt("3")
        val attemptId = model.session!!.attemptId
        model =
            model.updateSession(attemptId) {
                it.copy(
                    bindingResolution =
                        PhysicalCameraBindingResolution.PhysicalCameraBindingUnavailable("fixture"),
                )
            }
        assertNull(model.session!!.latestSnapshot)

        val nextModel = model.startAttempt("3")
        assertNotEquals(attemptId, nextModel.session!!.attemptId)
        assertNull(nextModel.session!!.latestSnapshot)
        assertNull(nextModel.session!!.bindingResolution)
    }

    @Test
    fun `updateSession is a no-op for a stale attemptId`() {
        val model = FrameContentCorrespondenceUiModel().startAttempt("3")
        val staleAttemptId = model.session!!.attemptId - 1
        val updated = model.updateSession(staleAttemptId) { it.reduceExplicitBindFailure(staleAttemptId, "should never apply") }
        assertSame(model, updated)
    }

    @Test
    fun `backToCandidates clears the session entirely`() {
        val model = FrameContentCorrespondenceUiModel().startAttempt("3")
        assertNotNull(model.session)
        val backToCandidates = model.backToCandidates()
        assertNull(backToCandidates.session)
    }

    @Test
    fun `retry preserves the requested resolution but starts a fresh attemptId`() {
        val resolution = AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3)
        var model = FrameContentCorrespondenceUiModel().startAttempt("3", resolution)
        val originalAttemptId = model.session!!.attemptId
        model = model.retry()
        assertNotEquals(originalAttemptId, model.session!!.attemptId)
        assertEquals(640, model.session!!.requestedAnalysisResolutionWidthPx)
        assertEquals(480, model.session!!.requestedAnalysisResolutionHeightPx)
    }

    @Test
    fun `Copy report and Share JSON builders describe the same attemptId and generation`() {
        // Both export builders are pure functions of one FrameContentCorrespondenceSnapshot argument —
        // no live/re-derived state — so calling them with the same snapshot instance can never disagree
        // about which frame/generation is being described (task §8's "Copy and Share use the same
        // frozen snapshot").
        val snapshot =
            buildFrameContentCorrespondenceSnapshot(
                attemptId = 42L,
                generation = 7L,
                requestedPhysicalCameraId = "3",
                provenance =
                    PhysicalCameraProvenance(
                        logicalCameraId = "0",
                        physicalCameraId = "3",
                        bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                        bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
                        confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                    ),
                openedLogicalCharacteristics = null,
                selectedPhysicalCharacteristics =
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
                    ),
                requestedAnalysisResolutionWidthPx = 640,
                requestedAnalysisResolutionHeightPx = 480,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 640,
                cropRectBottomPx = 480,
                rotationDegrees = 0,
                sensorToBufferTransformMatrix = null,
                zoomTargetRatio = 1.0f,
                observedZoomRatio = 1.0f,
                detectionResult = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("no target in this fixture", 0),
                targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
                capturedAtEpochMillis = 123456L,
            )

        val reportText = buildFrameContentCorrespondenceReportText(snapshot)
        val json = buildFrameContentCorrespondenceJson(snapshot)
        assertNotNull(reportText.lineSequence().firstOrNull { it == "attemptId=42" })
        assertNotNull(reportText.lineSequence().firstOrNull { it == "generation=7" })
        assertNotNull(json.let { if (it.contains("\"attemptId\":42")) it else null })
        assertNotNull(json.let { if (it.contains("\"generation\":7")) it else null })
    }
}
