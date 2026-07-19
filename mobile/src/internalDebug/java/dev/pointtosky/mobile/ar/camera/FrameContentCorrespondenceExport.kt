package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §4/§7/§9). Deterministic
 * text/JSON export for one [FrameContentCorrespondenceSnapshot] — Copy report and Share JSON both read
 * the exact same frozen snapshot the caller passes in (never a live/current value re-read at export
 * time), so the two exports can never disagree about which frame/generation, or which
 * targetPlacementLabel/distanceLabelMm, they describe.
 *
 * Every export explicitly states the safety facts task §1/§6/§9 require: this experiment constructs no
 * [SensorToBufferDomainProof], publishes no `AnalysisBuffer` intrinsics, does not unblock CAM-2c
 * calibrated projection, and its verdict is never an independent "path X is better" claim — a residual
 * difference is conditional on the physical-anchored pose (see [CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION]).
 *
 * ## Schema history
 * - `1`: initial export (superseded — permitted a semantic path-winner verdict, `rotationFoldedIn=true`
 *   for both hypotheses, no targetPlacementLabel/distanceLabelMm, and far less JSON detail than task §4
 *   requires).
 * - `2`: conditional-only verdict vocabulary, `rotationFoldedIn=false` for both implemented hypotheses
 *   with an explicit unrotated-buffer-contract statement, frozen targetPlacementLabel/distanceLabelMm,
 *   "characteristics-derived approximate physical pinhole K" wording (never "calibrated"), and a JSON
 *   schema that is a complete, self-contained evidence artifact.
 * - `3` (this revision): the orientation decision is fully auditable — `orientationEvidence`/
 *   `gridGeometryEvidence` export the exact marker/row/spacing measurements the detector froze for this
 *   frame, and `target` gains the printable target's full physical geometry
 *   (`regularDotDiameterMm`/`markerDiameterMm`/`markerOffsetXMm`/`markerOffsetYMm`/`printableBounds`) so
 *   the exact target a device report was captured against is reproducible from the report alone.
 */
internal const val FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION: Int = 3

private fun matrixValuesOrNull(snapshot: FrameContentCorrespondenceSnapshot): List<Double>? =
    snapshot.sensorToBufferTransformMatrix?.let {
        listOf(it.m00, it.m01, it.m02, it.m10, it.m11, it.m12, it.m20, it.m21, it.m22)
    }

private fun characteristicsSummary(characteristics: CameraCharacteristicsSnapshot?): String =
    characteristics?.let {
        "cameraId=${it.cameraId}, activeArray=[${it.activeArrayLeftPx},${it.activeArrayTopPx} — " +
            "${it.activeArrayRightPx},${it.activeArrayBottomPx}], pixelArray=${it.pixelArrayWidthPx}x" +
            "${it.pixelArrayHeightPx}, isLogicalMultiCamera=${it.isLogicalMultiCamera}"
    } ?: "unavailable"

/** Builds the deterministic plain-text report for one [snapshot] (task §7's "Copy report"). */
internal fun buildFrameContentCorrespondenceReportText(snapshot: FrameContentCorrespondenceSnapshot): String =
    buildString {
        appendLine("POINTTOSKY CAM-2c FRAME-CONTENT CORRESPONDENCE EXPERIMENT")
        appendLine("schemaVersion=$FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION")
        appendLine("attemptId=${snapshot.attemptId}")
        appendLine("generation=${snapshot.generation}")
        appendLine("capturedAtEpochMillis=${snapshot.capturedAtEpochMillis}")
        appendLine(
            "SAFETY: no SensorToBufferDomainProof constructed; no AnalysisBuffer intrinsics published; " +
                "CAM-2c calibrated projection NOT unblocked by this experiment. This experiment reports NO " +
                "independent path-winner verdict — see VERDICT section below.",
        )
        appendLine()

        appendLine("PROVENANCE")
        appendLine("  requestedPhysicalCameraId=${snapshot.requestedPhysicalCameraId}")
        appendLine(
            "  provenance=(physicalCameraId=${snapshot.provenance.physicalCameraId}, " +
                "logicalCameraId=${snapshot.provenance.logicalCameraId}, " +
                "bindingMethod=${snapshot.provenance.bindingMethod}, " +
                "bindingSource=${snapshot.provenance.bindingSource}, " +
                "confidence=${snapshot.provenance.confidence})",
        )
        appendLine()

        appendLine("EVIDENCE METADATA (frozen with this snapshot's own generation)")
        appendLine("  targetPlacementLabel=${snapshot.targetPlacementLabel}")
        appendLine("  distanceLabelMm=${snapshot.distanceLabelMm ?: "unset"}")
        appendLine()

        appendLine("CHARACTERISTICS")
        appendLine("  openedLogical=" + characteristicsSummary(snapshot.openedLogicalCharacteristics))
        appendLine("  selectedPhysical=" + characteristicsSummary(snapshot.selectedPhysicalCharacteristics))
        appendLine()

        appendLine("RESOLUTION/GEOMETRY")
        appendLine(
            "  requestedAnalysisResolution=" +
                (snapshot.requestedAnalysisResolutionWidthPx?.let { "${it}x${snapshot.requestedAnalysisResolutionHeightPx}" } ?: "cameraXDefault") +
                " family=${snapshot.requestedAnalysisResolutionFamily ?: "cameraXDefault"}",
        )
        appendLine("  actualAnalysisResolution=${snapshot.bufferWidthPx}x${snapshot.bufferHeightPx}")
        appendLine(
            "  cropRect=[${snapshot.cropRectLeftPx},${snapshot.cropRectTopPx} — " +
                "${snapshot.cropRectRightPx},${snapshot.cropRectBottomPx}]",
        )
        appendLine("  rotationDegrees=${snapshot.rotationDegrees} (metadata only — see BUFFER ROTATION CONTRACT in each hypothesis below)")
        appendLine("  sensorToBufferTransformMatrix=" + (matrixValuesOrNull(snapshot)?.toString() ?: "unavailable"))
        appendLine("  zoomTargetRatio=${snapshot.zoomTargetRatio}, observedZoomRatio=${snapshot.observedZoomRatio}")
        appendLine()

        appendLine("APPROXIMATE PHYSICAL PINHOLE K (never \"calibrated\" — see field names)")
        val k = snapshot.approximatePinholeKEvidence
        appendLine("  sourceDescription=${k.sourceDescription}")
        appendLine("  nativeBasisDescription=${k.nativeBasisDescription}")
        appendLine(
            "  focalDerivationInputs: focalLengthMm=${k.focalLengthMm}, sensorWidthMm=${k.sensorWidthMm}, " +
                "sensorHeightMm=${k.sensorHeightMm}, pixelArray=${k.pixelArrayWidthPx}x${k.pixelArrayHeightPx}, " +
                "activeArray=${k.activeArrayWidthPx}x${k.activeArrayHeightPx}",
        )
        appendLine("  quality=${k.quality}")
        appendLine("  usedLensIntrinsicCalibration=${k.usedLensIntrinsicCalibration}")
        appendLine("  principalPointAssumption=${k.principalPointAssumption}")
        appendLine("  distortionCoefficients=${snapshot.distortion.rawCoefficients ?: "unavailable"}")
        appendLine("  distortionNote=${snapshot.distortion.note}")
        appendLine()

        val targetBounds = frameContentTargetPrintableBounds(snapshot.targetSpec)
        appendLine(
            "TARGET (${snapshot.targetSpec.cornerRows}x${snapshot.targetSpec.cornerCols}, spacing=" +
                "${snapshot.targetSpec.dotSpacingMm}mm)",
        )
        appendLine(
            "  printableGeometry: regularDotDiameterMm=${snapshot.targetSpec.regularDotDiameterMm}, " +
                "designMarkerAreaScaleFactor=${snapshot.targetSpec.markerAreaScaleFactor} (printed target " +
                "design ratio — see ORIENTATION EVIDENCE below for what was actually observed), " +
                "markerDiameterMm=${snapshot.targetSpec.markerDiameterMm}, " +
                "markerOffsetMm=(${snapshot.targetSpec.markerOffsetXMm}, ${snapshot.targetSpec.markerOffsetYMm}) " +
                "relative to R0C0, printableBoundsMm=[${targetBounds.minXMm},${targetBounds.minYMm} — " +
                "${targetBounds.maxXMm},${targetBounds.maxYMm}] (${targetBounds.widthMm}x${targetBounds.heightMm})",
        )
        appendLine(
            "  detectionTolerances: darkThreshold=${snapshot.detectionTolerances.darkThreshold}, " +
                "minBlobAreaPx=${snapshot.detectionTolerances.minBlobAreaPx}, " +
                "maxBlobAreaFractionOfImage=${snapshot.detectionTolerances.maxBlobAreaFractionOfImage}, " +
                "subpixelMinBlobAreaPx=${snapshot.detectionTolerances.subpixelMinBlobAreaPx}, " +
                "markerAreaRatioThreshold=${snapshot.detectionTolerances.markerAreaRatioThreshold} " +
                "(detector acceptance threshold — NOT the design ratio above), " +
                "markerCornerConfidenceRatio=${snapshot.detectionTolerances.markerCornerConfidenceRatio}, " +
                "spacingConsistencyRatio=[${snapshot.detectionTolerances.spacingConsistencyMinRatio}," +
                "${snapshot.detectionTolerances.spacingConsistencyMaxRatio}]",
        )
        appendLine("  regionEdgeFraction=$DEFAULT_REGION_EDGE_FRACTION")
        appendLine("  detectionOutcome=${snapshot.detectionOutcomeDescription}")
        appendLine("  objectPointCount=${snapshot.objectPoints.size}")
        for (op in snapshot.objectPoints) {
            appendLine("    ${op.pointId.label}: xMm=${op.xMm}, yMm=${op.yMm}, zMm=${op.zMm}")
        }
        appendLine("  detectedPointCount=${snapshot.detectedPoints.size}")
        for (point in snapshot.detectedPoints) {
            appendLine(
                "    ${point.pointId.label}: bufferX=${point.bufferXPx}, bufferY=${point.bufferYPx}, " +
                    "confidence=${point.confidence}, refinement=${point.refinementStatus}, region=${point.region}",
            )
        }
        appendLine()

        appendLine("ORIENTATION EVIDENCE (task §2: the exact marker measurements that justified this frame's correspondence, frozen at detection time)")
        val oe = snapshot.orientationEvidence
        if (oe == null) {
            appendLine("  unavailable (no accepted detection this frame)")
        } else {
            appendLine("  markerCentroid=(${oe.markerCentroidXPx}, ${oe.markerCentroidYPx}), markerAreaPx=${oe.markerAreaPx}")
            appendLine("  medianGridDotAreaPx=${oe.medianGridDotAreaPx}")
            appendLine(
                "  observedMarkerAreaRatio=${oe.observedMarkerAreaRatio} (actual measured ratio this frame — " +
                    "compare against designMarkerAreaScaleFactor=${snapshot.targetSpec.markerAreaScaleFactor} " +
                    "above; only requirement enforced was clearing markerAreaRatioThreshold=" +
                    "${snapshot.detectionTolerances.markerAreaRatioThreshold} — do not assume the design ratio " +
                    "was what was actually observed)",
            )
            appendLine("  resolvedOriginCorner=${oe.resolvedOriginCorner}")
            appendLine(
                "  nearestCornerDistancePx=${oe.nearestCornerDistancePx}, " +
                    "secondNearestCornerDistancePx=${oe.secondNearestCornerDistancePx}, " +
                    "observedCornerConfidenceRatio=${oe.observedCornerConfidenceRatio} (required >= " +
                    "${snapshot.detectionTolerances.markerCornerConfidenceRatio})",
            )
        }
        appendLine()

        appendLine("GRID GEOMETRY EVIDENCE (task §2: row/spacing measurements that justified accepting this frame's grid)")
        val gge = snapshot.gridGeometryEvidence
        if (gge == null) {
            appendLine("  unavailable (no accepted detection this frame)")
        } else {
            appendLine("  minAdjacentRowSeparationPx=${gge.minAdjacentRowSeparationPx}")
            appendLine(
                "  withinRowGapPx: min=${gge.minWithinRowGapPx}, max=${gge.maxWithinRowGapPx}, " +
                    "median=${gge.medianWithinRowGapPx}",
            )
            appendLine(
                "  betweenRowGapPx: min=${gge.minBetweenRowGapPx}, max=${gge.maxBetweenRowGapPx}, " +
                    "median=${gge.medianBetweenRowGapPx}",
            )
            appendLine("  medianGapPx=${gge.medianGapPx}")
            appendLine(
                "  configuredSpacingConsistencyLimits=[${gge.spacingConsistencyMinRatio}x, " +
                    "${gge.spacingConsistencyMaxRatio}x] of medianGapPx",
            )
        }
        appendLine()

        appendLine("POSE")
        appendLine("  poseReferenceHypothesis=${snapshot.poseReferenceHypothesis}")
        appendLine("  crossHypothesisResidualInterpretation=${snapshot.crossHypothesisResidualInterpretation}")
        val pose = snapshot.pose
        if (pose == null) {
            appendLine("  pose=UNAVAILABLE")
        } else {
            appendLine("  estimationModel=${pose.estimationModel}")
            appendLine("  poseReusedUnchangedAcrossAllMappingHypotheses=true")
            appendLine(
                "  rvec=[${pose.rodriguesRvec.x}, ${pose.rodriguesRvec.y}, ${pose.rodriguesRvec.z}] " +
                    "(Rodrigues angle-axis, radians)",
            )
            appendLine(
                "  tvec=[${pose.translationMm.x}, ${pose.translationMm.y}, ${pose.translationMm.z}] " +
                    "(millimetres, target-plane-local object frame)",
            )
            appendLine("  poseSolverRmsResidualPx=${pose.poseSolverRmsResidualPx} (raw solver residual — kept separate from cross-hypothesis residuals below)")
        }
        appendLine()

        for (hypothesis in snapshot.hypotheses) {
            appendLine("HYPOTHESIS ${hypothesis.id}")
            val doc = hypothesis.documentation
            appendLine("  inputKBasis=${doc.inputKBasis}")
            appendLine("  distortionBasis=${doc.distortionBasis}")
            appendLine("  sourceCoordinateRect=${doc.sourceCoordinateRect}")
            appendLine("  transformsAppliedInOrder=${doc.transformsAppliedInOrder}")
            appendLine("  rotationFoldedIn=${doc.rotationFoldedIn}, cropRectFoldedIn=${doc.cropRectFoldedIn}")
            appendLine("  bufferRotationContract=${doc.bufferRotationContract}")
            appendLine("  whyOutputIsBufferCoordinates=${doc.whyOutputIsBufferCoordinates}")
            appendLine(
                "  result=" +
                    when (val result = hypothesis.result) {
                        is FrameContentHypothesisIntrinsicsResult.Available ->
                            "AVAILABLE(activeFxPx=${result.intrinsics.activeFxPx}, activeFyPx=${result.intrinsics.activeFyPx}, " +
                                "activeCxPx=${result.intrinsics.activeCxPx}, activeCyPx=${result.intrinsics.activeCyPx}, " +
                                "activeArray=${result.intrinsics.activeArrayWidthPx}x${result.intrinsics.activeArrayHeightPx}, " +
                                "bufferFxPx=${result.intrinsics.bufferFxPx}, bufferFyPx=${result.intrinsics.bufferFyPx}, " +
                                "bufferCxPx=${result.intrinsics.bufferCxPx}, bufferCyPx=${result.intrinsics.bufferCyPx}, " +
                                "quality=${result.diagnostics.quality}, transformClass=${result.diagnostics.transformClass})"
                        is FrameContentHypothesisIntrinsicsResult.Unavailable -> "UNAVAILABLE(${result.reason})"
                    },
            )
            val summary = snapshot.summariesByHypothesis[hypothesis.id]
            if (summary != null) {
                appendLine(
                    "  residualSummary: acceptedCount=${summary.acceptedCount}, rejectedCounts=${summary.rejectedCounts}, " +
                        "rmsPx=${summary.rmsPx}, medianPx=${summary.medianPx}, p95Px=${summary.p95Px}, maxPx=${summary.maxPx}, " +
                        "centerRmsPx=${summary.centerRmsPx}, edgeRmsPx=${summary.edgeRmsPx}, cornerRmsPx=${summary.cornerRmsPx}",
                )
            }
            for (residual in snapshot.residualsByHypothesis[hypothesis.id].orEmpty()) {
                when (residual) {
                    is FrameContentPointResidual.Accepted ->
                        appendLine(
                            "    ${residual.pointId.label}: observed=(${residual.observedXPx},${residual.observedYPx}) " +
                                "predicted=(${residual.predictedXPx},${residual.predictedYPx}) dx=${residual.dxPx} " +
                                "dy=${residual.dyPx} residualPx=${residual.euclideanResidualPx} " +
                                "normalized=${residual.normalizedResidual} region=${residual.region}",
                        )
                    is FrameContentPointResidual.Rejected ->
                        appendLine("    ${residual.pointId.label}: REJECTED(${residual.reason})")
                }
            }
            appendLine()
        }

        appendLine("VERDICT (evidence-only, conditional, never an independent path-winner claim)")
        appendLine("  verdict=${snapshot.verdict.verdict}")
        appendLine("  reason=${snapshot.verdict.reason}")
        appendLine("  lowerResidualHypothesisId=${snapshot.verdict.lowerResidualHypothesisId ?: "none"} (ranking/visualization only — see residualInterpretation)")
        appendLine("  residualInterpretation=${snapshot.verdict.residualInterpretation}")
        appendLine("  sensorToBufferDomainProofConstructed=false")
        appendLine("  analysisBufferIntrinsicsPublished=false")
        appendLine("  independentPoseReferenceAvailable=${snapshot.verdict.independentPoseReferenceAvailable}")
        appendLine("  strongerExperimentRequired=$FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED")
        val thresholds = snapshot.verdict.thresholds
        appendLine(
            "  thresholds: minWellDistributedPoints=${thresholds.minWellDistributedPoints}, " +
                "minDistinctRegions=${thresholds.minDistinctRegions}, " +
                "minAbsolutePixelMarginPx=${thresholds.minAbsolutePixelMarginPx}, " +
                "detectionNoiseMarginPx=${thresholds.detectionNoiseMarginPx}, " +
                "effectiveMarginPx=${thresholds.effectiveMarginPx}",
        )
    }

private fun JsonObjectBuilder.putCharacteristics(
    key: String,
    characteristics: CameraCharacteristicsSnapshot?,
) {
    put(
        key,
        characteristics?.let { c ->
            buildJsonObject {
                put("cameraId", c.cameraId)
                put("isLogicalMultiCamera", c.isLogicalMultiCamera)
                put("activeArrayLeftPx", c.activeArrayLeftPx)
                put("activeArrayTopPx", c.activeArrayTopPx)
                put("activeArrayRightPx", c.activeArrayRightPx)
                put("activeArrayBottomPx", c.activeArrayBottomPx)
                put("pixelArrayWidthPx", c.pixelArrayWidthPx)
                put("pixelArrayHeightPx", c.pixelArrayHeightPx)
                put("sensorPhysicalWidthMm", c.sensorPhysicalWidthMm?.toDouble())
                put("sensorPhysicalHeightMm", c.sensorPhysicalHeightMm?.toDouble())
                put(
                    "physicalCameraIds",
                    c.physicalCameraIds?.sorted()?.let { ids -> buildJsonArray { ids.forEach { add(it) } } } ?: JsonNull,
                )
            }
        } ?: JsonNull,
    )
}

/** Builds the deterministic JSON export for one [snapshot] (task §4/§7's "Share JSON") — the exact same
 * [snapshot] instance a caller passed to [buildFrameContentCorrespondenceReportText], so Copy report
 * and Share JSON can never describe two different frames/generations/placement-distance labels. Every
 * field task §4 lists is present and independently parseable — never substring-matched-only evidence. */
internal fun buildFrameContentCorrespondenceJson(snapshot: FrameContentCorrespondenceSnapshot): String {
    val root =
        buildJsonObject {
            // --- session / provenance ---
            put("schemaVersion", FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION)
            put("attemptId", snapshot.attemptId)
            put("generation", snapshot.generation)
            put("capturedAtEpochMillis", snapshot.capturedAtEpochMillis)
            put("requestedPhysicalCameraId", snapshot.requestedPhysicalCameraId)
            put(
                "provenance",
                buildJsonObject {
                    put("physicalCameraId", snapshot.provenance.physicalCameraId)
                    put("logicalCameraId", snapshot.provenance.logicalCameraId)
                    put("bindingMethod", snapshot.provenance.bindingMethod.name)
                    put("bindingSource", snapshot.provenance.bindingSource.name)
                    put("confidence", snapshot.provenance.confidence.name)
                },
            )
            putCharacteristics("openedLogicalCharacteristics", snapshot.openedLogicalCharacteristics)
            putCharacteristics("selectedPhysicalCharacteristics", snapshot.selectedPhysicalCharacteristics)
            put("requestedAnalysisResolutionWidthPx", snapshot.requestedAnalysisResolutionWidthPx)
            put("requestedAnalysisResolutionHeightPx", snapshot.requestedAnalysisResolutionHeightPx)
            put("requestedAnalysisResolutionFamily", snapshot.requestedAnalysisResolutionFamily?.name)
            put("bufferWidthPx", snapshot.bufferWidthPx)
            put("bufferHeightPx", snapshot.bufferHeightPx)
            put(
                "cropRect",
                buildJsonObject {
                    put("leftPx", snapshot.cropRectLeftPx)
                    put("topPx", snapshot.cropRectTopPx)
                    put("rightPx", snapshot.cropRectRightPx)
                    put("bottomPx", snapshot.cropRectBottomPx)
                },
            )
            put("rotationDegrees", snapshot.rotationDegrees)
            put(
                "sensorToBufferTransformMatrix",
                matrixValuesOrNull(snapshot)?.let { values -> buildJsonArray { values.forEach { add(it) } } } ?: JsonNull,
            )
            put("zoomTargetRatio", snapshot.zoomTargetRatio?.toDouble())
            put("observedZoomRatio", snapshot.observedZoomRatio?.toDouble())
            put("targetPlacementLabel", snapshot.targetPlacementLabel.name)
            put("distanceLabelMm", snapshot.distanceLabelMm)

            // --- approximate pinhole K evidence (never "calibrated") ---
            put(
                "approximatePinholeKEvidence",
                buildJsonObject {
                    val k = snapshot.approximatePinholeKEvidence
                    put("sourceDescription", k.sourceDescription)
                    put("nativeBasisDescription", k.nativeBasisDescription)
                    put("focalLengthMm", k.focalLengthMm)
                    put("sensorWidthMm", k.sensorWidthMm)
                    put("sensorHeightMm", k.sensorHeightMm)
                    put("pixelArrayWidthPx", k.pixelArrayWidthPx)
                    put("pixelArrayHeightPx", k.pixelArrayHeightPx)
                    put("activeArrayWidthPx", k.activeArrayWidthPx)
                    put("activeArrayHeightPx", k.activeArrayHeightPx)
                    put("quality", k.quality)
                    put("usedLensIntrinsicCalibration", k.usedLensIntrinsicCalibration)
                    put("principalPointAssumption", k.principalPointAssumption)
                },
            )
            put(
                "distortion",
                buildJsonObject {
                    put(
                        "rawCoefficients",
                        snapshot.distortion.rawCoefficients?.let { coeffs -> buildJsonArray { coeffs.forEach { add(it) } } } ?: JsonNull,
                    )
                    put("note", snapshot.distortion.note)
                },
            )

            // --- target / detection ---
            put(
                "target",
                buildJsonObject {
                    put("cornerRows", snapshot.targetSpec.cornerRows)
                    put("cornerCols", snapshot.targetSpec.cornerCols)
                    put("dotSpacingMm", snapshot.targetSpec.dotSpacingMm)
                    // "design" ratio the physical target was printed to — never conflate with the
                    // detector's own acceptance threshold (detectionTolerances.markerAreaRatioThreshold
                    // below) or the actual observed ratio (orientationEvidence.observedMarkerAreaRatio).
                    put("designMarkerAreaScaleFactor", snapshot.targetSpec.markerAreaScaleFactor)
                    put("regularDotDiameterMm", snapshot.targetSpec.regularDotDiameterMm)
                    put("markerDiameterMm", snapshot.targetSpec.markerDiameterMm)
                    put("markerOffsetXMm", snapshot.targetSpec.markerOffsetXMm)
                    put("markerOffsetYMm", snapshot.targetSpec.markerOffsetYMm)
                    val bounds = frameContentTargetPrintableBounds(snapshot.targetSpec)
                    put(
                        "printableBounds",
                        buildJsonObject {
                            put("minXMm", bounds.minXMm)
                            put("minYMm", bounds.minYMm)
                            put("maxXMm", bounds.maxXMm)
                            put("maxYMm", bounds.maxYMm)
                            put("widthMm", bounds.widthMm)
                            put("heightMm", bounds.heightMm)
                        },
                    )
                    put("regionEdgeFraction", DEFAULT_REGION_EDGE_FRACTION)
                },
            )
            put(
                "detectionTolerances",
                buildJsonObject {
                    val tol = snapshot.detectionTolerances
                    put("darkThreshold", tol.darkThreshold)
                    put("minBlobAreaPx", tol.minBlobAreaPx)
                    put("maxBlobAreaFractionOfImage", tol.maxBlobAreaFractionOfImage)
                    put("subpixelMinBlobAreaPx", tol.subpixelMinBlobAreaPx)
                    put("markerAreaRatioThreshold", tol.markerAreaRatioThreshold)
                    put("markerCornerConfidenceRatio", tol.markerCornerConfidenceRatio)
                    put("spacingConsistencyMinRatio", tol.spacingConsistencyMinRatio)
                    put("spacingConsistencyMaxRatio", tol.spacingConsistencyMaxRatio)
                },
            )
            put("detectionOutcome", snapshot.detectionOutcomeDescription)
            put(
                "objectPoints",
                buildJsonArray {
                    snapshot.objectPoints.forEach { op ->
                        add(
                            buildJsonObject {
                                put("pointId", op.pointId.label)
                                put("xMm", op.xMm)
                                put("yMm", op.yMm)
                                put("zMm", op.zMm)
                            },
                        )
                    }
                },
            )
            put(
                "detectedPoints",
                buildJsonArray {
                    snapshot.detectedPoints.forEach { point ->
                        add(
                            buildJsonObject {
                                put("pointId", point.pointId.label)
                                put("bufferXPx", point.bufferXPx)
                                put("bufferYPx", point.bufferYPx)
                                put("confidence", point.confidence)
                                put("refinementStatus", point.refinementStatus.name)
                                put("region", point.region.name)
                            },
                        )
                    }
                },
            )
            // Frozen exactly as the detector produced them for this frame (task §2) — never recomputed
            // from detectedPoints. null whenever there was no accepted detection this frame.
            put(
                "orientationEvidence",
                snapshot.orientationEvidence?.let { oe ->
                    buildJsonObject {
                        put("markerCentroidXPx", oe.markerCentroidXPx)
                        put("markerCentroidYPx", oe.markerCentroidYPx)
                        put("markerAreaPx", oe.markerAreaPx)
                        put("medianGridDotAreaPx", oe.medianGridDotAreaPx)
                        put("observedMarkerAreaRatio", oe.observedMarkerAreaRatio)
                        put("resolvedOriginCorner", oe.resolvedOriginCorner.name)
                        put("nearestCornerDistancePx", oe.nearestCornerDistancePx)
                        put("secondNearestCornerDistancePx", oe.secondNearestCornerDistancePx)
                        put("observedCornerConfidenceRatio", oe.observedCornerConfidenceRatio)
                    }
                } ?: JsonNull,
            )
            put(
                "gridGeometryEvidence",
                snapshot.gridGeometryEvidence?.let { gge ->
                    buildJsonObject {
                        put("minAdjacentRowSeparationPx", gge.minAdjacentRowSeparationPx)
                        put("minWithinRowGapPx", gge.minWithinRowGapPx)
                        put("maxWithinRowGapPx", gge.maxWithinRowGapPx)
                        put("medianWithinRowGapPx", gge.medianWithinRowGapPx)
                        put("minBetweenRowGapPx", gge.minBetweenRowGapPx)
                        put("maxBetweenRowGapPx", gge.maxBetweenRowGapPx)
                        put("medianBetweenRowGapPx", gge.medianBetweenRowGapPx)
                        put("medianGapPx", gge.medianGapPx)
                        put("spacingConsistencyMinRatio", gge.spacingConsistencyMinRatio)
                        put("spacingConsistencyMaxRatio", gge.spacingConsistencyMaxRatio)
                    }
                } ?: JsonNull,
            )

            // --- pose ---
            put("poseReferenceHypothesis", snapshot.poseReferenceHypothesis.name)
            put("crossHypothesisResidualInterpretation", snapshot.crossHypothesisResidualInterpretation)
            put(
                "pose",
                snapshot.pose?.let { pose ->
                    buildJsonObject {
                        put("estimationModel", pose.estimationModel)
                        put("referenceHypothesis", pose.referenceHypothesis.name)
                        put("poseReusedUnchangedAcrossAllMappingHypotheses", true)
                        put("rvec", buildJsonArray { add(pose.rodriguesRvec.x); add(pose.rodriguesRvec.y); add(pose.rodriguesRvec.z) })
                        put("tvecMm", buildJsonArray { add(pose.translationMm.x); add(pose.translationMm.y); add(pose.translationMm.z) })
                        put("poseSolverRmsResidualPx", pose.poseSolverRmsResidualPx)
                    }
                } ?: JsonNull,
            )

            // --- hypotheses ---
            put(
                "hypotheses",
                buildJsonArray {
                    snapshot.hypotheses.forEach { hypothesis ->
                        val summary = snapshot.summariesByHypothesis[hypothesis.id]
                        val residuals = snapshot.residualsByHypothesis[hypothesis.id].orEmpty()
                        add(
                            buildJsonObject {
                                put("id", hypothesis.id.name)
                                put("inputKBasis", hypothesis.documentation.inputKBasis)
                                put("distortionBasis", hypothesis.documentation.distortionBasis)
                                put("sourceCoordinateRect", hypothesis.documentation.sourceCoordinateRect)
                                put(
                                    "transformsAppliedInOrder",
                                    buildJsonArray { hypothesis.documentation.transformsAppliedInOrder.forEach { add(it) } },
                                )
                                put("rotationFoldedIn", hypothesis.documentation.rotationFoldedIn)
                                put("cropRectFoldedIn", hypothesis.documentation.cropRectFoldedIn)
                                put("bufferRotationContract", hypothesis.documentation.bufferRotationContract)
                                put("whyOutputIsBufferCoordinates", hypothesis.documentation.whyOutputIsBufferCoordinates)
                                put(
                                    "resultAvailable",
                                    hypothesis.result is FrameContentHypothesisIntrinsicsResult.Available,
                                )
                                put(
                                    "unavailableReason",
                                    (hypothesis.result as? FrameContentHypothesisIntrinsicsResult.Unavailable)?.reason,
                                )
                                val available = hypothesis.result as? FrameContentHypothesisIntrinsicsResult.Available
                                put(
                                    "intrinsics",
                                    available?.let { result ->
                                        buildJsonObject {
                                            put("activeFxPx", result.intrinsics.activeFxPx)
                                            put("activeFyPx", result.intrinsics.activeFyPx)
                                            put("activeCxPx", result.intrinsics.activeCxPx)
                                            put("activeCyPx", result.intrinsics.activeCyPx)
                                            put("activeArrayWidthPx", result.intrinsics.activeArrayWidthPx)
                                            put("activeArrayHeightPx", result.intrinsics.activeArrayHeightPx)
                                            put("bufferFxPx", result.intrinsics.bufferFxPx)
                                            put("bufferFyPx", result.intrinsics.bufferFyPx)
                                            put("bufferCxPx", result.intrinsics.bufferCxPx)
                                            put("bufferCyPx", result.intrinsics.bufferCyPx)
                                        }
                                    } ?: JsonNull,
                                )
                                put(
                                    "diagnostics",
                                    available?.let { result ->
                                        buildJsonObject {
                                            put("quality", result.diagnostics.quality.name)
                                            put("transformClass", result.diagnostics.transformClass.name)
                                            put("focalDerivationBasis", result.diagnostics.focalDerivationBasis)
                                            put("focalLengthMm", result.diagnostics.focalLengthMm)
                                            put("sensorWidthMm", result.diagnostics.sensorWidthMm)
                                            put("sensorHeightMm", result.diagnostics.sensorHeightMm)
                                            put("cropLeftPx", result.diagnostics.cropLeftPx)
                                            put("cropTopPx", result.diagnostics.cropTopPx)
                                            put("cropRightPx", result.diagnostics.cropRightPx)
                                            put("cropBottomPx", result.diagnostics.cropBottomPx)
                                        }
                                    } ?: JsonNull,
                                )
                                if (summary != null) {
                                    put("acceptedCount", summary.acceptedCount)
                                    put(
                                        "rejectedCounts",
                                        buildJsonObject { summary.rejectedCounts.forEach { (reason, count) -> put(reason.name, count) } },
                                    )
                                    put("rmsPx", summary.rmsPx)
                                    put("medianPx", summary.medianPx)
                                    put("p95Px", summary.p95Px)
                                    put("maxPx", summary.maxPx)
                                    put("centerRmsPx", summary.centerRmsPx)
                                    put("edgeRmsPx", summary.edgeRmsPx)
                                    put("cornerRmsPx", summary.cornerRmsPx)
                                }
                                put(
                                    "residuals",
                                    buildJsonArray {
                                        residuals.forEach { residual ->
                                            add(
                                                buildJsonObject {
                                                    put("pointId", residual.pointId.label)
                                                    when (residual) {
                                                        is FrameContentPointResidual.Accepted -> {
                                                            put("status", "ACCEPTED")
                                                            put("observedXPx", residual.observedXPx)
                                                            put("observedYPx", residual.observedYPx)
                                                            put("predictedXPx", residual.predictedXPx)
                                                            put("predictedYPx", residual.predictedYPx)
                                                            put("dxPx", residual.dxPx)
                                                            put("dyPx", residual.dyPx)
                                                            put("euclideanResidualPx", residual.euclideanResidualPx)
                                                            put("normalizedResidual", residual.normalizedResidual)
                                                            put("region", residual.region.name)
                                                        }
                                                        is FrameContentPointResidual.Rejected -> {
                                                            put("status", "REJECTED")
                                                            put("rejectionReason", residual.reason.name)
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )

            // --- verdict ---
            put(
                "verdict",
                buildJsonObject {
                    put("verdict", snapshot.verdict.verdict.name)
                    put("reason", snapshot.verdict.reason)
                    put("lowerResidualHypothesisId", snapshot.verdict.lowerResidualHypothesisId?.name)
                    put("residualInterpretation", snapshot.verdict.residualInterpretation)
                    put("sensorToBufferDomainProofConstructed", false)
                    put("analysisBufferIntrinsicsPublished", false)
                    put("independentPoseReferenceAvailable", snapshot.verdict.independentPoseReferenceAvailable)
                    put("strongerExperimentRequired", FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED)
                    put(
                        "thresholds",
                        buildJsonObject {
                            val thresholds = snapshot.verdict.thresholds
                            put("minWellDistributedPoints", thresholds.minWellDistributedPoints)
                            put("minDistinctRegions", thresholds.minDistinctRegions)
                            put("minAbsolutePixelMarginPx", thresholds.minAbsolutePixelMarginPx)
                            put("detectionNoiseMarginPx", thresholds.detectionNoiseMarginPx)
                            put("effectiveMarginPx", thresholds.effectiveMarginPx)
                        },
                    )
                },
            )
            // Top-level convenience flags (task §4) — duplicated from the verdict object above so a
            // consumer that only reads top-level keys still sees the safety facts.
            put("sensorToBufferDomainProofConstructed", false)
            put("analysisBufferIntrinsicsPublished", false)
            put("independentPoseReferenceAvailable", snapshot.verdict.independentPoseReferenceAvailable)
        }
    return root.toString()
}
