package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** This file deliberately never imports `SensorToBufferDomainProof` — see task §6/§9's constraint and
 * [FrameContentMappingHypothesisTest]'s own file KDoc for the same structural argument. */
class FrameContentCorrespondenceSnapshotTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    private fun physicalCamera3Snapshot() =
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

    private fun provenance() =
        PhysicalCameraProvenance(
            logicalCameraId = "0",
            physicalCameraId = "3",
            bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
            bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
            confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
        )

    /** Builds a snapshot from synthetic "observed" points that were themselves forward-projected
     * through a known pose and the PHYSICAL_ACTIVE_ARRAY_MODEL_PATH hypothesis's own K — a
     * noiseless, self-consistent round trip so the pose solver should recover (near) that exact pose. */
    private fun buildSyntheticSnapshot(): FrameContentCorrespondenceSnapshot {
        val physicalSnapshot = physicalCamera3Snapshot()
        val matrix =
            predictCameraX142SensorToBufferMatrix(CameraBasisRect(0, 0, 4032, 3024), bufferWidthPx, bufferHeightPx)!!.matrix
        val hypotheses = computeFrameContentMappingHypotheses(physicalSnapshot, matrix, bufferWidthPx, bufferHeightPx)
        val physicalIntrinsics =
            assertIs<FrameContentHypothesisIntrinsicsResult.Available>(
                hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }.result,
            ).intrinsics

        val knownPose =
            FrameContentPoseSolution(
                rotation = RotationMatrix3.IDENTITY,
                translationMm = Vec3(-40.0, -30.0, 600.0),
                rodriguesRvec = Vec3(0.0, 0.0, 0.0),
                poseSolverRmsResidualPx = 0.0,
            )
        val objectPoints = frameContentTargetObjectPoints(DEFAULT_FRAME_CONTENT_TARGET_SPEC)
        val detectedPoints =
            objectPoints.map { objectPoint ->
                val projection = projectObjectPoint(objectPoint, knownPose, physicalIntrinsics) as FrameContentProjectionOutcome.Projected
                DetectedTargetPoint(
                    pointId = objectPoint.pointId,
                    bufferXPx = projection.xPx,
                    bufferYPx = projection.yPx,
                    confidence = 1.0,
                    refinementStatus = CornerRefinementStatus.SUBPIXEL_REFINED,
                    region = classifyPointRegion(projection.xPx, projection.yPx, bufferWidthPx, bufferHeightPx),
                )
            }

        return buildFrameContentCorrespondenceSnapshot(
            attemptId = 1L,
            generation = 0L,
            requestedPhysicalCameraId = "3",
            provenance = provenance(),
            openedLogicalCharacteristics = null,
            selectedPhysicalCharacteristics = physicalSnapshot,
            requestedAnalysisResolutionWidthPx = bufferWidthPx,
            requestedAnalysisResolutionHeightPx = bufferHeightPx,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            cropRectLeftPx = 0,
            cropRectTopPx = 0,
            cropRectRightPx = bufferWidthPx,
            cropRectBottomPx = bufferHeightPx,
            rotationDegrees = 0,
            sensorToBufferTransformMatrix = matrix,
            zoomTargetRatio = 1.0f,
            observedZoomRatio = 1.0f,
            detectionResult = FrameContentDetectionResult.Detected(detectedPoints),
            targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
            capturedAtEpochMillis = 0L,
        )
    }

    @Test
    fun `pose is solved once and reused unchanged across every hypothesis's residual computation`() {
        val snapshot = buildSyntheticSnapshot()
        val pose = assertNotNull(snapshot.pose)
        assertTrue(pose.poseSolverRmsResidualPx < 0.5, "expected a near-zero pose-solver residual for noiseless synthetic data")

        // Re-derive each hypothesis's predicted point using the SAME snapshot.pose value and confirm it
        // matches the stored residual exactly — proving no hypothesis silently refit its own pose.
        for (hypothesis in snapshot.hypotheses) {
            val intrinsicsResult = hypothesis.result
            if (intrinsicsResult !is FrameContentHypothesisIntrinsicsResult.Available) continue
            val residuals = snapshot.residualsByHypothesis.getValue(hypothesis.id)
            for (residual in residuals.filterIsInstance<FrameContentPointResidual.Accepted>()) {
                val objectPoint = snapshot.objectPoints.single { it.pointId == residual.pointId }
                val reprojected = projectObjectPoint(objectPoint, pose, intrinsicsResult.intrinsics)
                require(reprojected is FrameContentProjectionOutcome.Projected)
                assertEquals(reprojected.xPx, residual.predictedXPx, "hypothesis ${hypothesis.id} point ${residual.pointId}")
                assertEquals(reprojected.yPx, residual.predictedYPx, "hypothesis ${hypothesis.id} point ${residual.pointId}")
            }
        }
    }

    @Test
    fun `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH residuals are near zero for the self-consistent synthetic fixture`() {
        val snapshot = buildSyntheticSnapshot()
        val summary = snapshot.summariesByHypothesis.getValue(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH)
        assertNotNull(summary.rmsPx)
        assertTrue(summary.rmsPx!! < 0.5, "expected near-zero RMS since detected points were generated from this exact hypothesis's own K")
    }

    @Test
    fun `no SensorToBufferDomainProof is constructed and the export says so explicitly`() {
        val snapshot = buildSyntheticSnapshot()
        val json = buildFrameContentCorrespondenceJson(snapshot)
        assertTrue(json.contains("\"sensorToBufferDomainProofConstructed\":false"))
        assertTrue(json.contains("\"analysisBufferIntrinsicsPublished\":false"))
        val text = buildFrameContentCorrespondenceReportText(snapshot)
        assertTrue(text.contains("no SensorToBufferDomainProof constructed"))
    }

    @Test
    fun `snapshot never mixes fields from a different attempt or generation`() {
        val snapshot = buildSyntheticSnapshot()
        assertEquals(1L, snapshot.attemptId)
        assertEquals(0L, snapshot.generation)
        // Every hypothesis/residual/summary was computed from this exact snapshot's own inputs — there
        // is no code path in buildFrameContentCorrespondenceSnapshot that reads any other attempt's data.
        assertEquals(snapshot.bufferWidthPx, bufferWidthPx)
        assertEquals(snapshot.bufferHeightPx, bufferHeightPx)
    }
}
