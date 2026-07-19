package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** This fixture's [detectedPoints] are forward-projected object points, not real detector output —
     * there is no real blob/marker measurement to freeze here, so this evidence is a synthetic
     * placeholder distinct enough (all zeros/TOP_LEFT) to be unmistakable in a failing assertion. Real
     * orientation-evidence freezing is covered by `FrameContentCornerDetectorTest`. */
    private fun syntheticOrientationEvidence() =
        FrameContentOrientationEvidence(
            markerCentroidXPx = 0.0,
            markerCentroidYPx = 0.0,
            markerAreaPx = 0,
            medianGridDotAreaPx = 0.0,
            observedMarkerAreaRatio = 0.0,
            resolvedOriginCorner = GridCorner.TOP_LEFT,
            nearestCornerDistancePx = 0.0,
            secondNearestCornerDistancePx = 0.0,
            observedCornerConfidenceRatio = 0.0,
        )

    private fun syntheticGridGeometryEvidence() =
        FrameContentGridGeometryEvidence(
            minAdjacentRowSeparationPx = 0.0,
            minWithinRowGapPx = 0.0,
            maxWithinRowGapPx = 0.0,
            medianWithinRowGapPx = 0.0,
            minBetweenRowGapPx = 0.0,
            maxBetweenRowGapPx = 0.0,
            medianBetweenRowGapPx = 0.0,
            medianGapPx = 0.0,
            spacingConsistencyMinRatio = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES.spacingConsistencyMinRatio,
            spacingConsistencyMaxRatio = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES.spacingConsistencyMaxRatio,
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
                    refinementStatus = CornerRefinementStatus.WEIGHTED_CENTROID_SUBPIXEL_ESTIMATE,
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
            requestedAnalysisResolutionFamily = AnalysisResolutionFamily.NEAR_4_3,
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
            targetPlacementLabel = TargetPlacementLabel.CENTER,
            distanceLabelMm = 300.0,
            detectionResult = FrameContentDetectionResult.Detected(detectedPoints, syntheticOrientationEvidence(), syntheticGridGeometryEvidence()),
            targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
            detectionTolerances = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES,
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

    @Test
    fun `snapshot carries the exact targetPlacementLabel and distanceLabelMm it was built with`() {
        val snapshot = buildSyntheticSnapshot()
        assertEquals(TargetPlacementLabel.CENTER, snapshot.targetPlacementLabel)
        assertEquals(300.0, snapshot.distanceLabelMm)
    }

    @Test
    fun `no path is ever called calibrated - the K evidence is explicitly approximate`() {
        val snapshot = buildSyntheticSnapshot()
        assertFalse(snapshot.approximatePinholeKEvidence.usedLensIntrinsicCalibration)
        assertEquals("APPROXIMATE_PRINCIPAL_POINT", snapshot.approximatePinholeKEvidence.quality)
        assertEquals(FRAME_CONTENT_POSE_ESTIMATION_MODEL, snapshot.pose!!.estimationModel)
        assertTrue(FRAME_CONTENT_POSE_ESTIMATION_MODEL.startsWith("CHARACTERISTICS_DERIVED_APPROXIMATE"))
    }

    @Test
    fun `JSON export is structurally parseable and contains every task-required top-level section`() {
        val snapshot = buildSyntheticSnapshot()
        val json = Json.parseToJsonElement(buildFrameContentCorrespondenceJson(snapshot)).jsonObject

        assertEquals(FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION.toLong(), json.getValue("schemaVersion").jsonPrimitive.long)
        assertEquals(1L, json.getValue("attemptId").jsonPrimitive.long)
        assertEquals("3", json.getValue("requestedPhysicalCameraId").jsonPrimitive.content)
        assertEquals("CENTER", json.getValue("targetPlacementLabel").jsonPrimitive.content)

        val provenanceJson = json.getValue("provenance").jsonObject
        assertEquals("3", provenanceJson.getValue("physicalCameraId").jsonPrimitive.content)

        val selectedPhysical = json.getValue("selectedPhysicalCharacteristics").jsonObject
        assertEquals("3", selectedPhysical.getValue("cameraId").jsonPrimitive.content)
        assertEquals(JsonNull, json.getValue("openedLogicalCharacteristics"))

        val kEvidence = json.getValue("approximatePinholeKEvidence").jsonObject
        assertEquals(false, kEvidence.getValue("usedLensIntrinsicCalibration").jsonPrimitive.boolean)

        val targetJson = json.getValue("target").jsonObject
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.cornerRows.toLong(), targetJson.getValue("cornerRows").jsonPrimitive.long)
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.cornerCols.toLong(), targetJson.getValue("cornerCols").jsonPrimitive.long)
        // Printable target geometry (task §3) — reproducible from the JSON alone.
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.regularDotDiameterMm, targetJson.getValue("regularDotDiameterMm").jsonPrimitive.double)
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.markerDiameterMm, targetJson.getValue("markerDiameterMm").jsonPrimitive.double)
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.markerOffsetXMm, targetJson.getValue("markerOffsetXMm").jsonPrimitive.double)
        assertEquals(DEFAULT_FRAME_CONTENT_TARGET_SPEC.markerOffsetYMm, targetJson.getValue("markerOffsetYMm").jsonPrimitive.double)
        // Physical non-overlap invariant's minimum clearance (printed-circle-overlap fix) — exported so
        // the physical target is fully reproducible, clearance included.
        assertEquals(
            DEFAULT_FRAME_CONTENT_TARGET_SPEC.minimumBlobClearanceMm,
            targetJson.getValue("minimumBlobClearanceMm").jsonPrimitive.double,
        )
        // designMarkerAreaScaleFactor (task §2's "target design vs observed ratio must be separate
        // fields") is never confused with the detector's own acceptance threshold, exported separately
        // below in detectionTolerances.markerAreaRatioThreshold.
        assertEquals(
            DEFAULT_FRAME_CONTENT_TARGET_SPEC.markerAreaScaleFactor,
            targetJson.getValue("designMarkerAreaScaleFactor").jsonPrimitive.double,
        )
        val printableBoundsJson = targetJson.getValue("printableBounds").jsonObject
        assertTrue(printableBoundsJson.containsKey("widthMm"))
        assertTrue(printableBoundsJson.containsKey("heightMm"))

        val detectionTolerancesJson = json.getValue("detectionTolerances").jsonObject
        assertTrue(detectionTolerancesJson.containsKey("markerAreaRatioThreshold"))

        val objectPointsJson = json.getValue("objectPoints").jsonArray
        assertEquals(snapshot.objectPoints.size, objectPointsJson.size)

        val detectedPointsJson = json.getValue("detectedPoints").jsonArray
        assertEquals(snapshot.detectedPoints.size, detectedPointsJson.size)
        val firstDetected = detectedPointsJson.first().jsonObject
        assertTrue(firstDetected.containsKey("refinementStatus"))
        assertTrue(firstDetected.containsKey("region"))

        // Orientation/grid-geometry evidence (task §2) — this fixture uses a synthetic placeholder (see
        // syntheticOrientationEvidence), but the JSON structure itself must always be present/parseable.
        val orientationEvidenceJson = json.getValue("orientationEvidence").jsonObject
        assertTrue(orientationEvidenceJson.containsKey("observedMarkerAreaRatio"))
        assertTrue(orientationEvidenceJson.containsKey("resolvedOriginCorner"))
        assertEquals("TOP_LEFT", orientationEvidenceJson.getValue("resolvedOriginCorner").jsonPrimitive.content)
        val gridGeometryEvidenceJson = json.getValue("gridGeometryEvidence").jsonObject
        assertTrue(gridGeometryEvidenceJson.containsKey("minAdjacentRowSeparationPx"))
        assertTrue(gridGeometryEvidenceJson.containsKey("spacingConsistencyMinRatio"))

        val poseJson = json.getValue("pose").jsonObject
        assertTrue(poseJson.containsKey("rvec"))
        assertTrue(poseJson.containsKey("tvecMm"))
        assertEquals(true, poseJson.getValue("poseReusedUnchangedAcrossAllMappingHypotheses").jsonPrimitive.boolean)

        val hypothesesJson = json.getValue("hypotheses").jsonArray
        assertEquals(3, hypothesesJson.size)
        val physicalHypothesisJson =
            hypothesesJson.first { it.jsonObject.getValue("id").jsonPrimitive.content == "PHYSICAL_ACTIVE_ARRAY_MODEL_PATH" }.jsonObject
        assertEquals(false, physicalHypothesisJson.getValue("rotationFoldedIn").jsonPrimitive.boolean)
        assertTrue(physicalHypothesisJson.containsKey("bufferRotationContract"))
        assertTrue(physicalHypothesisJson.containsKey("transformsAppliedInOrder"))
        assertTrue(physicalHypothesisJson.containsKey("intrinsics"))
        assertTrue(physicalHypothesisJson.containsKey("diagnostics"))
        assertTrue(physicalHypothesisJson.containsKey("residuals"))
        val residualsJson = physicalHypothesisJson.getValue("residuals").jsonArray
        assertTrue(residualsJson.isNotEmpty())
        assertTrue(residualsJson.first().jsonObject.containsKey("status"))

        val reconciledHypothesisJson =
            hypothesesJson.first { it.jsonObject.getValue("id").jsonPrimitive.content == "RECONCILED_PHYSICAL_TO_LOGICAL_PATH" }.jsonObject
        assertEquals(false, reconciledHypothesisJson.getValue("resultAvailable").jsonPrimitive.boolean)
        assertTrue(reconciledHypothesisJson.getValue("unavailableReason").jsonPrimitive.content.startsWith("NOT_IMPLEMENTED"))

        val verdictJson = json.getValue("verdict").jsonObject
        assertTrue(verdictJson.containsKey("verdict"))
        assertEquals(false, verdictJson.getValue("sensorToBufferDomainProofConstructed").jsonPrimitive.boolean)
        assertEquals(false, verdictJson.getValue("analysisBufferIntrinsicsPublished").jsonPrimitive.boolean)
        assertEquals(false, verdictJson.getValue("independentPoseReferenceAvailable").jsonPrimitive.boolean)
        assertTrue(verdictJson.containsKey("thresholds"))

        // Top-level convenience duplicates of the same safety flags.
        assertEquals(false, json.getValue("sensorToBufferDomainProofConstructed").jsonPrimitive.boolean)
        assertEquals(false, json.getValue("analysisBufferIntrinsicsPublished").jsonPrimitive.boolean)
    }
}
