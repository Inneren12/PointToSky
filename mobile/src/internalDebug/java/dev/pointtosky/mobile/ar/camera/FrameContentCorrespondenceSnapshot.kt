package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §2). Builds and holds one
 * **immutable, frozen** correspondence snapshot for exactly one analyzed frame of exactly one
 * attempt/generation — never mixes values across frames or generations (task §2's explicit constraint).
 *
 * **What this file explicitly does not do:** it never constructs a [SensorToBufferDomainProof], never
 * calls [resolveCam2cForExplicitPhysicalCamera], never publishes `AnalysisBuffer` intrinsics, and its
 * [FrameContentCorrespondenceSnapshot.verdict] is evidence only (see [FrameContentVerdict]'s KDoc). A
 * hypothesis match here — even a strong one — is matrix-and-content-construction evidence for *this*
 * session only, never a proof this codebase's calibrated CAM-2c projection may be unblocked.
 */

/** Raw distortion coefficients captured for evidence only (task §2 "distortion model and coefficients")
 * — never applied to any projection in this experiment (see [FRAME_CONTENT_DISTORTION_BASIS]). */
internal data class FrameContentDistortionCapture(
    val rawCoefficients: List<Double>?,
    val note: String =
        "Android LENS_DISTORTION captured verbatim for evidence only; coefficient semantics " +
            "(division model per API 29+) are not verified/implemented in this codebase, so no " +
            "distortion correction is applied to any projection here.",
)

internal fun distortionCaptureFrom(snapshot: CameraCharacteristicsSnapshot): FrameContentDistortionCapture =
    FrameContentDistortionCapture(rawCoefficients = snapshot.lensDistortion?.map { it.toDouble() })

/** One immutable correspondence snapshot (task §2's full field list). */
internal data class FrameContentCorrespondenceSnapshot(
    val attemptId: Long,
    val generation: Long,
    val requestedPhysicalCameraId: String,
    val provenance: PhysicalCameraProvenance,
    val openedLogicalCharacteristics: CameraCharacteristicsSnapshot?,
    val selectedPhysicalCharacteristics: CameraCharacteristicsSnapshot,
    val requestedAnalysisResolutionWidthPx: Int?,
    val requestedAnalysisResolutionHeightPx: Int?,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val cropRectLeftPx: Int?,
    val cropRectTopPx: Int?,
    val cropRectRightPx: Int?,
    val cropRectBottomPx: Int?,
    val rotationDegrees: Int,
    val sensorToBufferTransformMatrix: SensorToBufferMatrix3?,
    val zoomTargetRatio: Float?,
    val observedZoomRatio: Float?,
    val physicalCameraKSourceDescription: String,
    val physicalCameraKNativeBasisDescription: String,
    val distortion: FrameContentDistortionCapture,
    val targetSpec: FrameContentTargetSpec,
    val objectPoints: List<FrameContentObjectPoint>,
    val detectedPoints: List<DetectedTargetPoint>,
    val detectionOutcomeDescription: String,
    val pose: FrameContentPoseSolution?,
    val hypotheses: List<FrameContentHypothesis>,
    val residualsByHypothesis: Map<FrameContentMappingHypothesisId, List<FrameContentPointResidual>>,
    val summariesByHypothesis: Map<FrameContentMappingHypothesisId, FrameContentResidualSummary>,
    val verdict: FrameContentVerdictResult,
    val capturedAtEpochMillis: Long,
) {
    companion object {
        /** The one hypothesis this experiment's pose solver anchors to (task §4) — see
         * [FrameContentPoseSolution.estimationModel]'s KDoc for why. */
        val POSE_REFERENCE_HYPOTHESIS: FrameContentMappingHypothesisId =
            FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH
    }
}

/**
 * Builds one [FrameContentCorrespondenceSnapshot] deterministically from this frame's own inputs only
 * (task §2's "never mixes values from different frames or generations" constraint — every field here
 * comes from exactly one [attemptId]/[generation]'s one analyzed frame, never a running/rolling value).
 *
 * Pose is solved once ([FrameContentCorrespondenceSnapshot.POSE_REFERENCE_HYPOTHESIS]'s own resolved
 * buffer-space intrinsics) and reused, completely unchanged, when computing every hypothesis's own
 * residuals (task §4's contamination-control requirement) —
 * `POSE_REUSED_UNCHANGED_ACROSS_ALL_MAPPING_HYPOTHESES=true` always holds for a snapshot this function
 * builds; there is no code path here that refits pose per hypothesis.
 */
internal fun buildFrameContentCorrespondenceSnapshot(
    attemptId: Long,
    generation: Long,
    requestedPhysicalCameraId: String,
    provenance: PhysicalCameraProvenance,
    openedLogicalCharacteristics: CameraCharacteristicsSnapshot?,
    selectedPhysicalCharacteristics: CameraCharacteristicsSnapshot,
    requestedAnalysisResolutionWidthPx: Int?,
    requestedAnalysisResolutionHeightPx: Int?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
    cropRectLeftPx: Int?,
    cropRectTopPx: Int?,
    cropRectRightPx: Int?,
    cropRectBottomPx: Int?,
    rotationDegrees: Int,
    sensorToBufferTransformMatrix: SensorToBufferMatrix3?,
    zoomTargetRatio: Float?,
    observedZoomRatio: Float?,
    detectionResult: FrameContentDetectionResult,
    targetSpec: FrameContentTargetSpec,
    capturedAtEpochMillis: Long,
    verdictThresholds: FrameContentVerdictThresholds = FrameContentVerdictThresholds(),
): FrameContentCorrespondenceSnapshot {
    val objectPoints = frameContentTargetObjectPoints(targetSpec)
    val detectedPoints =
        when (detectionResult) {
            is FrameContentDetectionResult.Detected -> detectionResult.points
            is FrameContentDetectionResult.InsufficientOrAmbiguousGrid -> emptyList()
        }
    val detectionOutcomeDescription =
        when (detectionResult) {
            is FrameContentDetectionResult.Detected -> "DETECTED(${detectionResult.points.size} points)"
            is FrameContentDetectionResult.InsufficientOrAmbiguousGrid ->
                "INSUFFICIENT_OR_AMBIGUOUS_GRID(rawBlobCount=${detectionResult.rawBlobCount}, " +
                    "reason=${detectionResult.reason})"
        }

    val hypotheses =
        computeFrameContentMappingHypotheses(
            physicalSnapshot = selectedPhysicalCharacteristics,
            observedCameraXMatrix = sensorToBufferTransformMatrix,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    val hypothesesById = hypotheses.associateBy { it.id }
    val poseReferenceHypothesis = hypothesesById[FrameContentCorrespondenceSnapshot.POSE_REFERENCE_HYPOTHESIS]
    val poseReferenceIntrinsics =
        (poseReferenceHypothesis?.result as? FrameContentHypothesisIntrinsicsResult.Available)?.intrinsics

    val objectPointsById = objectPoints.associateBy { it.pointId }
    val correspondences =
        detectedPoints.mapNotNull { detected ->
            val objectPoint = objectPointsById[detected.pointId] ?: return@mapNotNull null
            PlanarCorrespondence(
                objectXMm = objectPoint.xMm,
                objectYMm = objectPoint.yMm,
                imageUPx = detected.bufferXPx,
                imageVPx = detected.bufferYPx,
            )
        }
    val pose =
        poseReferenceIntrinsics?.let { intrinsics ->
            solvePlanarPose(
                correspondences = correspondences,
                fxPx = intrinsics.bufferFxPx,
                fyPx = intrinsics.bufferFyPx,
                cxPx = intrinsics.bufferCxPx,
                cyPx = intrinsics.bufferCyPx,
            )
        }

    val residualsByHypothesis = LinkedHashMap<FrameContentMappingHypothesisId, List<FrameContentPointResidual>>()
    val summariesByHypothesis = LinkedHashMap<FrameContentMappingHypothesisId, FrameContentResidualSummary>()
    for (hypothesis in hypotheses) {
        val residuals =
            detectedPoints.mapNotNull { detected ->
                val objectPoint = objectPointsById[detected.pointId] ?: return@mapNotNull null
                val outcome =
                    when {
                        pose == null -> FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.HYPOTHESIS_UNAVAILABLE)
                        hypothesis.result !is FrameContentHypothesisIntrinsicsResult.Available ->
                            FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.HYPOTHESIS_UNAVAILABLE)
                        else ->
                            projectObjectPoint(
                                objectPoint = objectPoint,
                                pose = pose,
                                intrinsics = hypothesis.result.intrinsics,
                            )
                    }
                computePointResidual(detected, outcome, bufferWidthPx, bufferHeightPx)
            }
        residualsByHypothesis[hypothesis.id] = residuals
        summariesByHypothesis[hypothesis.id] = summarizeResiduals(hypothesis.id, residuals)
    }

    val verdict =
        computeFrameContentVerdict(
            summaries = summariesByHypothesis.values.toList(),
            wellDistributedResiduals =
                residualsByHypothesis[FrameContentCorrespondenceSnapshot.POSE_REFERENCE_HYPOTHESIS] ?: emptyList(),
            poseValid = pose != null,
            thresholds = verdictThresholds,
        )

    val physicalKDescription =
        when (val result = poseReferenceHypothesis?.result) {
            is FrameContentHypothesisIntrinsicsResult.Available ->
                "source=${result.diagnostics.focalDerivationBasis}, quality=${result.diagnostics.quality}, " +
                    "activeFxPx=${result.diagnostics.activeFxPx}, activeFyPx=${result.diagnostics.activeFyPx}, " +
                    "activeCxPx=${result.diagnostics.activeCxPx}, activeCyPx=${result.diagnostics.activeCyPx}"
            is FrameContentHypothesisIntrinsicsResult.Unavailable -> "UNAVAILABLE(${result.reason})"
            null -> "UNAVAILABLE(hypothesis not computed)"
        }
    val physicalKBasisDescription =
        "ACTIVE_ARRAY_LOCAL basis of the SELECTED PHYSICAL camera " +
            "(cameraId=${selectedPhysicalCharacteristics.cameraId}), principal point basis matches " +
            "CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL"

    return FrameContentCorrespondenceSnapshot(
        attemptId = attemptId,
        generation = generation,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        provenance = provenance,
        openedLogicalCharacteristics = openedLogicalCharacteristics,
        selectedPhysicalCharacteristics = selectedPhysicalCharacteristics,
        requestedAnalysisResolutionWidthPx = requestedAnalysisResolutionWidthPx,
        requestedAnalysisResolutionHeightPx = requestedAnalysisResolutionHeightPx,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
        rotationDegrees = rotationDegrees,
        sensorToBufferTransformMatrix = sensorToBufferTransformMatrix,
        zoomTargetRatio = zoomTargetRatio,
        observedZoomRatio = observedZoomRatio,
        physicalCameraKSourceDescription = physicalKDescription,
        physicalCameraKNativeBasisDescription = physicalKBasisDescription,
        distortion = distortionCaptureFrom(selectedPhysicalCharacteristics),
        targetSpec = targetSpec,
        objectPoints = objectPoints,
        detectedPoints = detectedPoints,
        detectionOutcomeDescription = detectionOutcomeDescription,
        pose = pose,
        hypotheses = hypotheses,
        residualsByHypothesis = residualsByHypothesis,
        summariesByHypothesis = summariesByHypothesis,
        verdict = verdict,
        capturedAtEpochMillis = capturedAtEpochMillis,
    )
}
