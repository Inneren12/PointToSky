package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §7/§9). Deterministic
 * text/JSON export for one [FrameContentCorrespondenceSnapshot] — Copy report and Share JSON both read
 * the exact same frozen snapshot the caller passes in (never a live/current value re-read at export
 * time), so the two exports can never disagree about which frame/generation they describe.
 *
 * Every export explicitly states the safety facts task §6/§9 require: this experiment constructs no
 * [SensorToBufferDomainProof], publishes no `AnalysisBuffer` intrinsics, and does not unblock CAM-2c
 * calibrated projection — a strong hypothesis match is evidence for this one session only.
 */
internal const val FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION: Int = 1

private fun matrixValuesOrNull(snapshot: FrameContentCorrespondenceSnapshot): List<Double>? =
    snapshot.sensorToBufferTransformMatrix?.let {
        listOf(it.m00, it.m01, it.m02, it.m10, it.m11, it.m12, it.m20, it.m21, it.m22)
    }

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
                "CAM-2c calibrated projection NOT unblocked by this experiment. This verdict is evidence " +
                "for this session only.",
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

        appendLine("CHARACTERISTICS")
        appendLine(
            "  openedLogical=" +
                (
                    snapshot.openedLogicalCharacteristics?.let {
                        "cameraId=${it.cameraId}, activeArray=[${it.activeArrayLeftPx},${it.activeArrayTopPx} — " +
                            "${it.activeArrayRightPx},${it.activeArrayBottomPx}]"
                    } ?: "unavailable"
                ),
        )
        val physical = snapshot.selectedPhysicalCharacteristics
        appendLine(
            "  selectedPhysical=cameraId=${physical.cameraId}, activeArray=[${physical.activeArrayLeftPx}," +
                "${physical.activeArrayTopPx} — ${physical.activeArrayRightPx},${physical.activeArrayBottomPx}]",
        )
        appendLine()

        appendLine("RESOLUTION/GEOMETRY")
        appendLine(
            "  requestedAnalysisResolution=" +
                (snapshot.requestedAnalysisResolutionWidthPx?.let { "${it}x${snapshot.requestedAnalysisResolutionHeightPx}" } ?: "cameraXDefault"),
        )
        appendLine("  actualAnalysisResolution=${snapshot.bufferWidthPx}x${snapshot.bufferHeightPx}")
        appendLine(
            "  cropRect=[${snapshot.cropRectLeftPx},${snapshot.cropRectTopPx} — " +
                "${snapshot.cropRectRightPx},${snapshot.cropRectBottomPx}]",
        )
        appendLine("  rotationDegrees=${snapshot.rotationDegrees}")
        appendLine("  sensorToBufferTransformMatrix=" + (matrixValuesOrNull(snapshot)?.toString() ?: "unavailable"))
        appendLine("  zoomTargetRatio=${snapshot.zoomTargetRatio}, observedZoomRatio=${snapshot.observedZoomRatio}")
        appendLine()

        appendLine("K / DISTORTION")
        appendLine("  physicalCameraKSource=${snapshot.physicalCameraKSourceDescription}")
        appendLine("  physicalCameraKNativeBasis=${snapshot.physicalCameraKNativeBasisDescription}")
        appendLine("  distortionCoefficients=${snapshot.distortion.rawCoefficients ?: "unavailable"}")
        appendLine("  distortionNote=${snapshot.distortion.note}")
        appendLine()

        appendLine("TARGET (${snapshot.targetSpec.cornerRows}x${snapshot.targetSpec.cornerCols}, spacing=${snapshot.targetSpec.dotSpacingMm}mm)")
        appendLine("  detectionOutcome=${snapshot.detectionOutcomeDescription}")
        appendLine("  objectPointCount=${snapshot.objectPoints.size}")
        appendLine("  detectedPointCount=${snapshot.detectedPoints.size}")
        for (point in snapshot.detectedPoints) {
            appendLine(
                "    ${point.pointId.label}: bufferX=${point.bufferXPx}, bufferY=${point.bufferYPx}, " +
                    "confidence=${point.confidence}, refinement=${point.refinementStatus}, region=${point.region}",
            )
        }
        appendLine()

        appendLine("POSE")
        val pose = snapshot.pose
        if (pose == null) {
            appendLine("  pose=UNAVAILABLE")
        } else {
            appendLine("  estimationModel=${pose.estimationModel}")
            appendLine("  POSE_REUSED_UNCHANGED_ACROSS_ALL_MAPPING_HYPOTHESES=true")
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
            appendLine("  whyOutputIsBufferCoordinates=${doc.whyOutputIsBufferCoordinates}")
            appendLine(
                "  result=" +
                    when (val result = hypothesis.result) {
                        is FrameContentHypothesisIntrinsicsResult.Available ->
                            "AVAILABLE(bufferFxPx=${result.intrinsics.bufferFxPx}, bufferFyPx=${result.intrinsics.bufferFyPx}, " +
                                "bufferCxPx=${result.intrinsics.bufferCxPx}, bufferCyPx=${result.intrinsics.bufferCyPx})"
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

        appendLine("VERDICT")
        appendLine("  verdict=${snapshot.verdict.verdict}")
        appendLine("  reason=${snapshot.verdict.reason}")
        val thresholds = snapshot.verdict.thresholds
        appendLine(
            "  thresholds: minWellDistributedPoints=${thresholds.minWellDistributedPoints}, " +
                "minDistinctRegions=${thresholds.minDistinctRegions}, " +
                "minAbsolutePixelMarginPx=${thresholds.minAbsolutePixelMarginPx}, " +
                "detectionNoiseMarginPx=${thresholds.detectionNoiseMarginPx}, " +
                "effectiveMarginPx=${thresholds.effectiveMarginPx}",
        )
    }

/** Builds the deterministic JSON export for one [snapshot] (task §7's "Share JSON") — the exact same
 * [snapshot] instance a caller passed to [buildFrameContentCorrespondenceReportText], so Copy report and
 * Share JSON can never describe two different frames/generations. */
internal fun buildFrameContentCorrespondenceJson(snapshot: FrameContentCorrespondenceSnapshot): String {
    val root =
        buildJsonObject {
            put("schemaVersion", FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION)
            put("attemptId", snapshot.attemptId)
            put("generation", snapshot.generation)
            put("capturedAtEpochMillis", snapshot.capturedAtEpochMillis)
            put("sensorToBufferDomainProofConstructed", false)
            put("analysisBufferIntrinsicsPublished", false)
            put("requestedPhysicalCameraId", snapshot.requestedPhysicalCameraId)
            put("bufferWidthPx", snapshot.bufferWidthPx)
            put("bufferHeightPx", snapshot.bufferHeightPx)
            put("rotationDegrees", snapshot.rotationDegrees)
            put(
                "sensorToBufferTransformMatrix",
                matrixValuesOrNull(snapshot)?.let { values -> buildJsonArray { values.forEach { add(it) } } } ?: JsonNull,
            )
            put("physicalCameraKSourceDescription", snapshot.physicalCameraKSourceDescription)
            put("physicalCameraKNativeBasisDescription", snapshot.physicalCameraKNativeBasisDescription)
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
            put("detectionOutcome", snapshot.detectionOutcomeDescription)
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
            put(
                "pose",
                snapshot.pose?.let { pose ->
                    buildJsonObject {
                        put("estimationModel", pose.estimationModel)
                        put("poseReusedUnchangedAcrossAllMappingHypotheses", true)
                        put("rvec", buildJsonArray { add(pose.rodriguesRvec.x); add(pose.rodriguesRvec.y); add(pose.rodriguesRvec.z) })
                        put("tvecMm", buildJsonArray { add(pose.translationMm.x); add(pose.translationMm.y); add(pose.translationMm.z) })
                        put("poseSolverRmsResidualPx", pose.poseSolverRmsResidualPx)
                    }
                } ?: JsonNull,
            )
            put(
                "hypotheses",
                buildJsonArray {
                    snapshot.hypotheses.forEach { hypothesis ->
                        val summary = snapshot.summariesByHypothesis[hypothesis.id]
                        add(
                            buildJsonObject {
                                put("id", hypothesis.id.name)
                                put("inputKBasis", hypothesis.documentation.inputKBasis)
                                put("distortionBasis", hypothesis.documentation.distortionBasis)
                                put("sourceCoordinateRect", hypothesis.documentation.sourceCoordinateRect)
                                put("rotationFoldedIn", hypothesis.documentation.rotationFoldedIn)
                                put("cropRectFoldedIn", hypothesis.documentation.cropRectFoldedIn)
                                put(
                                    "resultAvailable",
                                    hypothesis.result is FrameContentHypothesisIntrinsicsResult.Available,
                                )
                                put(
                                    "unavailableReason",
                                    (hypothesis.result as? FrameContentHypothesisIntrinsicsResult.Unavailable)?.reason,
                                )
                                if (summary != null) {
                                    put("acceptedCount", summary.acceptedCount)
                                    put("rmsPx", summary.rmsPx)
                                    put("medianPx", summary.medianPx)
                                    put("p95Px", summary.p95Px)
                                    put("maxPx", summary.maxPx)
                                    put("centerRmsPx", summary.centerRmsPx)
                                    put("edgeRmsPx", summary.edgeRmsPx)
                                    put("cornerRmsPx", summary.cornerRmsPx)
                                }
                            },
                        )
                    }
                },
            )
            put("verdict", snapshot.verdict.verdict.name)
            put("verdictReason", snapshot.verdict.reason)
        }
    return root.toString()
}
