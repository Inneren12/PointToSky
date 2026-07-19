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
 * residual difference here — even a large one — is matrix-and-content-construction evidence for *this*
 * session only, **conditional on the physical-anchored pose** (see [CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION]),
 * never a proof this codebase's calibrated CAM-2c projection may be unblocked, and never an independent
 * verdict that one mapping hypothesis is the better real-world model.
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

/**
 * Evidence for the characteristics-derived approximate physical pinhole K this experiment's pose solver
 * used (task §5's "stop calling the derived K 'calibrated'" fix): every input that produced it, plus an
 * explicit `usedLensIntrinsicCalibration=false` flag and a stated principal-point assumption, so a
 * reader never has to infer these from field absence or from an overstated label.
 */
internal data class FrameContentApproximatePinholeKEvidence(
    val sourceDescription: String,
    val nativeBasisDescription: String,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    val activeArrayWidthPx: Int?,
    val activeArrayHeightPx: Int?,
    val quality: String,
    val usedLensIntrinsicCalibration: Boolean,
    val principalPointAssumption: String,
)

private const val FRAME_CONTENT_PRINCIPAL_POINT_ASSUMPTION: String =
    "Assumed geometric centre of the physical camera's own active array (activeArrayWidthPx/2, " +
        "activeArrayHeightPx/2) — not a measured principal point; LENS_INTRINSIC_CALIBRATION is never " +
        "consulted by this experiment's own K builder (resolveHypothesisIntrinsics always uses " +
        "activeArrayIntrinsicsFromFocalLength)."

/** One immutable correspondence snapshot (task §2's full field list, plus task §3's frozen
 * targetPlacementLabel/distanceLabelMm evidence metadata). */
internal data class FrameContentCorrespondenceSnapshot(
    val attemptId: Long,
    val generation: Long,
    val requestedPhysicalCameraId: String,
    val provenance: PhysicalCameraProvenance,
    val openedLogicalCharacteristics: CameraCharacteristicsSnapshot?,
    val selectedPhysicalCharacteristics: CameraCharacteristicsSnapshot,
    val requestedAnalysisResolutionWidthPx: Int?,
    val requestedAnalysisResolutionHeightPx: Int?,
    val requestedAnalysisResolutionFamily: AnalysisResolutionFamily?,
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
    val targetPlacementLabel: TargetPlacementLabel,
    val distanceLabelMm: Double?,
    val approximatePinholeKEvidence: FrameContentApproximatePinholeKEvidence,
    val distortion: FrameContentDistortionCapture,
    val targetSpec: FrameContentTargetSpec,
    val detectionTolerances: FrameContentDetectionTolerances,
    val objectPoints: List<FrameContentObjectPoint>,
    val detectedPoints: List<DetectedTargetPoint>,
    val detectionOutcomeDescription: String,
    val pose: FrameContentPoseSolution?,
    val poseReferenceHypothesis: FrameContentMappingHypothesisId,
    val crossHypothesisResidualInterpretation: String,
    val hypotheses: List<FrameContentHypothesis>,
    val residualsByHypothesis: Map<FrameContentMappingHypothesisId, List<FrameContentPointResidual>>,
    val summariesByHypothesis: Map<FrameContentMappingHypothesisId, FrameContentResidualSummary>,
    val verdict: FrameContentVerdictResult,
    val capturedAtEpochMillis: Long,
) {
    companion object {
        /** The one hypothesis this experiment's pose solver anchors to (task §4) — see
         * [FrameContentPoseSolution.estimationModel]'s KDoc for why. Same value as
         * [FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS]; kept as a member for call-site convenience. */
        val POSE_REFERENCE_HYPOTHESIS: FrameContentMappingHypothesisId = FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS
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
    requestedAnalysisResolutionFamily: AnalysisResolutionFamily?,
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
    targetPlacementLabel: TargetPlacementLabel,
    distanceLabelMm: Double?,
    detectionResult: FrameContentDetectionResult,
    targetSpec: FrameContentTargetSpec,
    detectionTolerances: FrameContentDetectionTolerances,
    capturedAtEpochMillis: Long,
    verdictThresholds: FrameContentVerdictThresholds = FrameContentVerdictThresholds(),
): FrameContentCorrespondenceSnapshot {
    val objectPoints = frameContentTargetObjectPoints(targetSpec)
    val detectedPoints =
        when (detectionResult) {
            is FrameContentDetectionResult.Detected -> detectionResult.points
            is FrameContentDetectionResult.InsufficientOrAmbiguousGrid -> emptyList()
            is FrameContentDetectionResult.OrientationAmbiguous -> emptyList()
        }
    val detectionOutcomeDescription =
        when (detectionResult) {
            is FrameContentDetectionResult.Detected -> "DETECTED(${detectionResult.points.size} points)"
            is FrameContentDetectionResult.InsufficientOrAmbiguousGrid ->
                "INSUFFICIENT_OR_AMBIGUOUS_GRID(rawBlobCount=${detectionResult.rawBlobCount}, " +
                    "reason=${detectionResult.reason})"
            is FrameContentDetectionResult.OrientationAmbiguous ->
                "ORIENTATION_AMBIGUOUS(rawBlobCount=${detectionResult.rawBlobCount}, " +
                    "markerCandidateCount=${detectionResult.markerCandidateCount}, reason=${detectionResult.reason})"
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

    val approximatePinholeKSourceDescription =
        when (val result = poseReferenceHypothesis?.result) {
            is FrameContentHypothesisIntrinsicsResult.Available ->
                "source=${result.diagnostics.focalDerivationBasis}, quality=${result.diagnostics.quality}, " +
                    "activeFxPx=${result.diagnostics.activeFxPx}, activeFyPx=${result.diagnostics.activeFyPx}, " +
                    "activeCxPx=${result.diagnostics.activeCxPx}, activeCyPx=${result.diagnostics.activeCyPx}"
            is FrameContentHypothesisIntrinsicsResult.Unavailable -> "UNAVAILABLE(${result.reason})"
            null -> "UNAVAILABLE(hypothesis not computed)"
        }
    val approximatePinholeKNativeBasisDescription =
        "ACTIVE_ARRAY_LOCAL basis of the SELECTED PHYSICAL camera " +
            "(cameraId=${selectedPhysicalCharacteristics.cameraId}), principal point basis matches " +
            "CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL"
    val availableDiagnostics = (poseReferenceHypothesis?.result as? FrameContentHypothesisIntrinsicsResult.Available)?.diagnostics
    val approximatePinholeKEvidence =
        FrameContentApproximatePinholeKEvidence(
            sourceDescription = approximatePinholeKSourceDescription,
            nativeBasisDescription = approximatePinholeKNativeBasisDescription,
            focalLengthMm = availableDiagnostics?.focalLengthMm,
            sensorWidthMm = availableDiagnostics?.sensorWidthMm,
            sensorHeightMm = availableDiagnostics?.sensorHeightMm,
            pixelArrayWidthPx = availableDiagnostics?.pixelArrayWidthPx,
            pixelArrayHeightPx = availableDiagnostics?.pixelArrayHeightPx,
            activeArrayWidthPx = availableDiagnostics?.activeArrayWidthPx,
            activeArrayHeightPx = availableDiagnostics?.activeArrayHeightPx,
            quality = availableDiagnostics?.quality?.name ?: "UNAVAILABLE",
            usedLensIntrinsicCalibration = false,
            principalPointAssumption = FRAME_CONTENT_PRINCIPAL_POINT_ASSUMPTION,
        )

    return FrameContentCorrespondenceSnapshot(
        attemptId = attemptId,
        generation = generation,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        provenance = provenance,
        openedLogicalCharacteristics = openedLogicalCharacteristics,
        selectedPhysicalCharacteristics = selectedPhysicalCharacteristics,
        requestedAnalysisResolutionWidthPx = requestedAnalysisResolutionWidthPx,
        requestedAnalysisResolutionHeightPx = requestedAnalysisResolutionHeightPx,
        requestedAnalysisResolutionFamily = requestedAnalysisResolutionFamily,
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
        targetPlacementLabel = targetPlacementLabel,
        distanceLabelMm = distanceLabelMm,
        approximatePinholeKEvidence = approximatePinholeKEvidence,
        distortion = distortionCaptureFrom(selectedPhysicalCharacteristics),
        targetSpec = targetSpec,
        detectionTolerances = detectionTolerances,
        objectPoints = objectPoints,
        detectedPoints = detectedPoints,
        detectionOutcomeDescription = detectionOutcomeDescription,
        pose = pose,
        poseReferenceHypothesis = FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS,
        crossHypothesisResidualInterpretation = CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION,
        hypotheses = hypotheses,
        residualsByHypothesis = residualsByHypothesis,
        summariesByHypothesis = summariesByHypothesis,
        verdict = verdict,
        capturedAtEpochMillis = capturedAtEpochMillis,
    )
}
