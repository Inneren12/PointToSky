package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `internalDebug`-only. CAM-2c dual-basis experiment export (task §12): deterministic text lines and
 * JSON for one [ExperimentSessionState], covering session identity, the opened-logical and
 * selected-physical bases (strictly separate — task §4), the observed frame at full available
 * precision, per-session matrix stability, and the safety state.
 *
 * ## Precision honesty (task §9)
 * All nine matrix values are emitted as unrounded Kotlin `Double`s (`Double.toString` in text,
 * kotlinx-serialization JSON numbers in JSON — both locale-independent, no `%f` formatting anywhere).
 * These doubles are **widened `android.graphics.Matrix` float32 values**, not native double-precision
 * measurements — the export preserves every bit the platform reported, and nothing more.
 *
 * ## Safety (task §1/§13)
 * The export states explicitly, in both formats, that geometry/model classification is evidence-only,
 * that frame-content correspondence is unmeasured, and that no `SensorToBufferDomainProof.Proven*`
 * variant was constructed. Nothing in this file feeds any resolution path.
 */

/** The CameraX version this experiment's model/diagnostics are pinned against — the resolved
 * `androidx.camera:*` version from `gradle/libs.versions.toml` (`camerax = "1.4.2"`), recorded as a
 * build-time constant because CameraX exposes no runtime version API this codebase has verified.
 * Update alongside any CameraX dependency bump (which also invalidates
 * [predictCameraX142SensorToBufferMatrix] until re-traced). */
internal const val EXPERIMENT_PINNED_CAMERAX_VERSION: String = "1.4.2"

private fun matrixValues(m: dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3): List<Double> =
    listOf(m.m00, m.m01, m.m02, m.m10, m.m11, m.m12, m.m20, m.m21, m.m22)

/** Deterministic text lines for the dual-basis evidence sections, appended to the experiment report
 * by [buildPhysicalCameraExperimentReportText]. `Double.toString`/`Float.toString` only — both are
 * locale-independent (always a dot decimal separator), so no explicit `Locale.ROOT` formatting calls
 * are needed anywhere in this file. */
internal fun formatDualBasisReportLines(session: ExperimentSessionState): String =
    buildString {
        appendLine("requestedAnalysisResolution=" + (session.requestedAnalysisResolutionWidthPx?.let { w -> "${w}x${session.requestedAnalysisResolutionHeightPx}" } ?: "cameraXDefault"))
        appendLine("actualAnalysisResolution=" + (session.latestFrame?.let { "${it.bufferWidthPx}x${it.bufferHeightPx}" } ?: "none yet"))
        appendLine("zoomTargetRatio=" + (session.zoomTargetRatio?.toString() ?: "unavailable"))
        appendLine("observedZoomRatio=" + (session.observedZoomRatio?.toString() ?: "unavailable"))

        val stability = session.matrixStability
        appendLine("MATRIX STABILITY (generation attemptId=${session.attemptId}):")
        appendLine("  framesObserved=${stability.framesObserved}")
        appendLine("  framesWithNullTransform=${stability.framesWithNullTransform}")
        appendLine("  changesBeyondFloatNoise=${stability.changesBeyondFloatNoise}")
        appendLine("  maxCoefficientDeltaFromFirst=${stability.maxCoefficientDeltaFromFirst}")
        appendLine("  dimensionsCropOrRotationChanged=${stability.dimensionsCropOrRotationChanged}")
        appendLine("  firstMatrix=" + (stability.firstMatrix?.let { matrixValues(it).toString() } ?: "none"))
        appendLine("  latestMatrix=" + (stability.latestMatrix?.let { matrixValues(it).toString() } ?: "none"))

        appendLine(
            "OPENED LOGICAL CAMERA: " +
                when (val logical = session.openedLogicalCamera) {
                    null -> "not yet resolved"
                    is OpenedLogicalCameraSnapshotResolution.Captured ->
                        "captured(cameraId=${logical.snapshot.cameraId}, provenance=${logical.provenance}, " +
                            "activeArray=[${logical.snapshot.activeArrayLeftPx},${logical.snapshot.activeArrayTopPx} — " +
                            "${logical.snapshot.activeArrayRightPx},${logical.snapshot.activeArrayBottomPx}], " +
                            "preCorrection=[${logical.snapshot.preCorrectionActiveArrayLeftPx},${logical.snapshot.preCorrectionActiveArrayTopPx} — " +
                            "${logical.snapshot.preCorrectionActiveArrayRightPx},${logical.snapshot.preCorrectionActiveArrayBottomPx}], " +
                            "pixelArray=${logical.snapshot.pixelArrayWidthPx}x${logical.snapshot.pixelArrayHeightPx}, " +
                            "logicalMultiCamera=${logical.snapshot.isLogicalMultiCamera}, " +
                            "declaredPhysicalIds=${logical.snapshot.physicalCameraIds?.toList()?.sorted() ?: "unavailable"})"
                    is OpenedLogicalCameraSnapshotResolution.Unavailable -> "unavailable(${logical.reason})"
                },
        )

        val evidence = session.dualBasisEvidence
        appendLine("DUAL-BASIS MATRIX ASSESSMENT (evidence-only; never a SensorToBufferDomainProof):")
        if (evidence == null) {
            appendLine("  none yet (no frame observed)")
        } else {
            appendLine("  observedMatrix=" + (evidence.observedMatrix?.let { matrixValues(it).toString() } ?: "none"))
            appendLine("  observedMatrixValueOrder=[m00,m01,m02,m10,m11,m12,m20,m21,m22] (android.graphics.Matrix float32 widened to double)")
            appendLine("  observedTransformClass=${evidence.observedTransformClass?.name ?: "none"}")
            appendLine("  comparisonVerdict=${evidence.comparisonVerdict.name}")
            appendLine("  basesNumericallyIndistinguishable=${evidence.basesNumericallyIndistinguishable}")
            appendLine("  betterPredictingBasis=${evidence.betterPredictingBasis?.name ?: "none"}")
            appendLine("  evidenceLevels=${evidence.evidenceLevels.joinToString(",") { it.name }}")
            append(formatBasisAssessmentLines(evidence.logical, MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS))
            append(formatBasisAssessmentLines(evidence.physical, MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS))
        }

        appendLine("SAFETY STATE:")
        appendLine("  pinnedCameraXVersion=$EXPERIMENT_PINNED_CAMERAX_VERSION")
        appendLine("  sensorToBufferDomainProof=" + describeDomainProof(session.cam2cResult))
        appendLine("  geometryClassificationIsEvidenceOnly=true")
        appendLine("  frameContentCorrespondenceMeasured=false")
        appendLine("  provenDomainVariantConstructed=false")
    }

private fun formatBasisAssessmentLines(
    assessment: BasisMatrixAssessment?,
    label: MatrixBasisLabel,
): String =
    buildString {
        appendLine("  ${label.name}:")
        if (assessment == null) {
            appendLine("    unavailable (basis not captured — no substitution attempted)")
            return@buildString
        }
        appendLine("    basis=${assessment.basis.label()}")
        val g = assessment.geometry
        appendLine("    geometryClass=${g.geometryClass.name}")
        appendLine("    scaleX=${g.scaleX} scaleY=${g.scaleY} translationX=${g.translationX} translationY=${g.translationY}")
        appendLine(
            "    mappedBounds=" +
                (g.mappedBoundsPx?.let { "[${it.leftPx},${it.topPx} — ${it.rightPx},${it.bottomPx}]" } ?: "none"),
        )
        appendLine("    mappedCenter=(${g.mappedCenterXPx},${g.mappedCenterYPx})")
        appendLine(
            "    overflow(l,t,r,b)=(${g.overflowLeftPx},${g.overflowTopPx},${g.overflowRightPx},${g.overflowBottomPx})",
        )
        appendLine("    isotropyResidual=${g.isotropyResidual} centerResidual=(${g.centerResidualXPx},${g.centerResidualYPx})")
        appendLine("    symmetryResidual(h,v)=(${g.horizontalSymmetryResidualPx},${g.verticalSymmetryResidualPx})")
        appendLine("    predictedCameraX142Matrix=" + (assessment.predicted?.matrix?.let { matrixValues(it).toString() } ?: "none"))
        appendLine("    predictedFitAxis=${assessment.predicted?.fitAxis?.name ?: "none"}")
        appendLine("    predictedOverflowDirection=${assessment.predicted?.overflowDirection?.name ?: "none"}")
        appendLine("    predictedOverflowPerSidePx=${assessment.predicted?.expectedOverflowPerSidePx ?: "none"}")
        appendLine("    coefficientResiduals=" + (assessment.coefficientResiduals?.toString() ?: "none"))
        appendLine("    maxAbsCoefficientResidual=${assessment.maxAbsCoefficientResidual}")
        appendLine("    maxMappedPointResidualPx=${assessment.maxMappedPointResidualPx}")
        appendLine("    matchesCameraX142ImplementationModel=${assessment.matchesCameraX142Model}")
    }

private fun describeDomainProof(cam2cResult: Cam2cPhysicalCameraResolution?): String =
    when (cam2cResult) {
        null -> "not yet computed"
        is Cam2cPhysicalCameraResolution.DomainNotProven -> cam2cResult.proof::class.simpleName ?: "DomainNotProven"
        is Cam2cPhysicalCameraResolution.BindingFailure -> "not computed (binding failure)"
        is Cam2cPhysicalCameraResolution.IntrinsicsFailure -> "Proven (test-only path; never automatic)"
        is Cam2cPhysicalCameraResolution.Resolved -> "Proven (test-only path; never automatic)"
    }

/**
 * `internalDebug`-only. Schema version for [buildPhysicalCameraExperimentJson]. `1`: initial
 * dual-basis experiment export (this slice). Bump on any rename/removal/reinterpretation.
 */
const val PHYSICAL_CAMERA_EXPERIMENT_JSON_SCHEMA_VERSION: Int = 1

private fun matrixJson(m: dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3?): JsonElement =
    m?.let { values -> buildJsonArray { matrixValues(values).forEach { add(it) } } } ?: JsonNull

private fun basisJson(assessment: BasisMatrixAssessment?): JsonElement {
    if (assessment == null) return JsonNull
    val g = assessment.geometry
    return buildJsonObject {
        put("basisLabel", assessment.basisLabel.name)
        put("cameraId", assessment.basis.cameraId)
        put("cameraRole", assessment.basis.cameraRole.name)
        put("coordinateSpace", assessment.basis.coordinateSpace.name)
        put("metadataSource", assessment.basis.metadataSource.name)
        put("rectLeftPx", assessment.basis.rect.leftPx)
        put("rectTopPx", assessment.basis.rect.topPx)
        put("rectRightPx", assessment.basis.rect.rightPx)
        put("rectBottomPx", assessment.basis.rect.bottomPx)
        put("rectWidthPx", assessment.basis.rect.widthPx)
        put("rectHeightPx", assessment.basis.rect.heightPx)
        put("geometryClass", g.geometryClass.name)
        put("scaleX", g.scaleX)
        put("scaleY", g.scaleY)
        put("translationX", g.translationX)
        put("translationY", g.translationY)
        put("mappedLeftPx", g.mappedBoundsPx?.leftPx)
        put("mappedTopPx", g.mappedBoundsPx?.topPx)
        put("mappedRightPx", g.mappedBoundsPx?.rightPx)
        put("mappedBottomPx", g.mappedBoundsPx?.bottomPx)
        put("mappedCenterXPx", g.mappedCenterXPx)
        put("mappedCenterYPx", g.mappedCenterYPx)
        put("overflowLeftPx", g.overflowLeftPx)
        put("overflowTopPx", g.overflowTopPx)
        put("overflowRightPx", g.overflowRightPx)
        put("overflowBottomPx", g.overflowBottomPx)
        put("isotropyResidual", g.isotropyResidual)
        put("centerResidualXPx", g.centerResidualXPx)
        put("centerResidualYPx", g.centerResidualYPx)
        put("horizontalSymmetryResidualPx", g.horizontalSymmetryResidualPx)
        put("verticalSymmetryResidualPx", g.verticalSymmetryResidualPx)
        put("geometryReason", g.reason)
        put("predictedCameraX142Matrix", matrixJson(assessment.predicted?.matrix))
        put("predictedFitAxis", assessment.predicted?.fitAxis?.name)
        put("predictedOverflowDirection", assessment.predicted?.overflowDirection?.name)
        put("predictedOverflowPerSidePx", assessment.predicted?.expectedOverflowPerSidePx)
        put(
            "coefficientResiduals",
            assessment.coefficientResiduals?.let { list -> buildJsonArray { list.forEach { add(it) } } } ?: JsonNull,
        )
        put("maxAbsCoefficientResidual", assessment.maxAbsCoefficientResidual)
        put("maxMappedPointResidualPx", assessment.maxMappedPointResidualPx)
        put("matchesCameraX142ImplementationModel", assessment.matchesCameraX142Model)
    }
}

private fun characteristicsJson(snapshot: CameraCharacteristicsSnapshot?): JsonElement {
    if (snapshot == null) return JsonNull
    return buildJsonObject {
        put("cameraId", snapshot.cameraId)
        put("isLogicalMultiCamera", snapshot.isLogicalMultiCamera)
        put(
            "physicalCameraIds",
            snapshot.physicalCameraIds?.let { ids -> buildJsonArray { ids.toList().sorted().forEach { add(it) } } } ?: JsonNull,
        )
        put("activeArrayLeftPx", snapshot.activeArrayLeftPx)
        put("activeArrayTopPx", snapshot.activeArrayTopPx)
        put("activeArrayRightPx", snapshot.activeArrayRightPx)
        put("activeArrayBottomPx", snapshot.activeArrayBottomPx)
        put("preCorrectionActiveArrayLeftPx", snapshot.preCorrectionActiveArrayLeftPx)
        put("preCorrectionActiveArrayTopPx", snapshot.preCorrectionActiveArrayTopPx)
        put("preCorrectionActiveArrayRightPx", snapshot.preCorrectionActiveArrayRightPx)
        put("preCorrectionActiveArrayBottomPx", snapshot.preCorrectionActiveArrayBottomPx)
        put("pixelArrayWidthPx", snapshot.pixelArrayWidthPx)
        put("pixelArrayHeightPx", snapshot.pixelArrayHeightPx)
        put("sensorPhysicalWidthMm", snapshot.sensorPhysicalWidthMm?.toDouble())
        put("sensorPhysicalHeightMm", snapshot.sensorPhysicalHeightMm?.toDouble())
        put(
            "availableFocalLengthsMm",
            snapshot.availableFocalLengthsMm?.let { list -> buildJsonArray { list.map { it.toDouble() }.forEach { add(it) } } }
                ?: JsonNull,
        )
    }
}

/**
 * `internalDebug`-only. Deterministic JSON export for one experiment session (task §12) —
 * [camDiagnosticJson]'s configuration (explicit nulls, locale-independent numbers), field order fixed
 * by construction. [capturedAtEpochMillis] is a parameter (never a clock read here), keeping this a
 * pure, deterministic mapper.
 */
internal fun buildPhysicalCameraExperimentJson(
    session: ExperimentSessionState?,
    capturedAtEpochMillis: Long,
): String {
    val root =
        buildJsonObject {
            put("schemaVersion", PHYSICAL_CAMERA_EXPERIMENT_JSON_SCHEMA_VERSION)
            put("capturedAtEpochMillis", capturedAtEpochMillis)
            put("pinnedCameraXVersion", EXPERIMENT_PINNED_CAMERAX_VERSION)
            if (session == null) {
                put("session", JsonNull)
                return@buildJsonObject
            }
            put(
                "session",
                buildJsonObject {
                    put("attemptId", session.attemptId)
                    put("requestedPhysicalCameraId", session.physicalCameraId)
                    put("requestedAnalysisResolutionWidthPx", session.requestedAnalysisResolutionWidthPx)
                    put("requestedAnalysisResolutionHeightPx", session.requestedAnalysisResolutionHeightPx)
                    put("actualAnalysisResolutionWidthPx", session.latestFrame?.bufferWidthPx)
                    put("actualAnalysisResolutionHeightPx", session.latestFrame?.bufferHeightPx)
                    put("zoomTargetRatio", session.zoomTargetRatio?.toDouble())
                    put("observedZoomRatio", session.observedZoomRatio?.toDouble())
                    put("explicitBindFailureReason", session.explicitBindFailureReason)

                    val bound = session.bindingResolution as? PhysicalCameraBindingResolution.Bound
                    put("bindingResolutionType", session.bindingResolution?.let { it::class.simpleName })
                    put("bindingSource", bound?.provenance?.bindingSource?.name)
                    put("bindingConfidence", bound?.provenance?.confidence?.name)
                    put("bindingMethod", bound?.provenance?.bindingMethod?.name)
                    put("provenanceLogicalCameraId", bound?.provenance?.logicalCameraId)
                    put("selectedPhysicalCameraId", bound?.provenance?.physicalCameraId)

                    val logical = session.openedLogicalCamera
                    put(
                        "openedLogicalCamera",
                        buildJsonObject {
                            put("resolutionType", logical?.let { it::class.simpleName })
                            put(
                                "provenance",
                                (logical as? OpenedLogicalCameraSnapshotResolution.Captured)?.provenance?.name,
                            )
                            put(
                                "unavailableReason",
                                (logical as? OpenedLogicalCameraSnapshotResolution.Unavailable)?.reason,
                            )
                            put(
                                "characteristics",
                                characteristicsJson((logical as? OpenedLogicalCameraSnapshotResolution.Captured)?.snapshot),
                            )
                        },
                    )
                    put("physicalCharacteristics", characteristicsJson(bound?.physicalCharacteristicsSnapshot))

                    val stability = session.matrixStability
                    put(
                        "matrixStability",
                        buildJsonObject {
                            put("framesObserved", stability.framesObserved)
                            put("framesWithNullTransform", stability.framesWithNullTransform)
                            put("changesBeyondFloatNoise", stability.changesBeyondFloatNoise)
                            put("maxCoefficientDeltaFromFirst", stability.maxCoefficientDeltaFromFirst)
                            put("dimensionsCropOrRotationChanged", stability.dimensionsCropOrRotationChanged)
                            put("firstMatrix", matrixJson(stability.firstMatrix))
                            put("latestMatrix", matrixJson(stability.latestMatrix))
                        },
                    )

                    val frame = session.latestFrame
                    put(
                        "observedFrame",
                        buildJsonObject {
                            put("present", frame != null)
                            put("bufferWidthPx", frame?.bufferWidthPx)
                            put("bufferHeightPx", frame?.bufferHeightPx)
                            put("rotationDegrees", frame?.rotationDegrees)
                            put("cropRectLeftPx", frame?.cropRectLeftPx)
                            put("cropRectTopPx", frame?.cropRectTopPx)
                            put("cropRectRightPx", frame?.cropRectRightPx)
                            put("cropRectBottomPx", frame?.cropRectBottomPx)
                            // Full available precision (task §9): unrounded doubles, which are widened
                            // android.graphics.Matrix float32 values — never claimed to be native
                            // double-precision measurements.
                            put("matrix", matrixJson(frame?.sensorToBufferTransform))
                            put("matrixValueOrder", "m00,m01,m02,m10,m11,m12,m20,m21,m22")
                            put("matrixValueOrigin", "android.graphics.Matrix float32 storage widened to double")
                        },
                    )

                    val evidence = session.dualBasisEvidence
                    put(
                        "dualBasisEvidence",
                        if (evidence == null) {
                            JsonNull
                        } else {
                            buildJsonObject {
                                put("observedTransformClass", evidence.observedTransformClass?.name)
                                put("comparisonVerdict", evidence.comparisonVerdict.name)
                                put("basesNumericallyIndistinguishable", evidence.basesNumericallyIndistinguishable)
                                put("betterPredictingBasis", evidence.betterPredictingBasis?.name)
                                put(
                                    "evidenceLevels",
                                    buildJsonArray { evidence.evidenceLevels.forEach { add(it.name) } },
                                )
                                put("reason", evidence.reason)
                                put("logicalBasisAssessment", basisJson(evidence.logical))
                                put("physicalBasisAssessment", basisJson(evidence.physical))
                            }
                        },
                    )

                    put(
                        "safetyState",
                        buildJsonObject {
                            put("cam2cResultType", session.cam2cResult?.let { it::class.simpleName })
                            put("sensorToBufferDomainProof", describeDomainProof(session.cam2cResult))
                            put("geometryClassificationIsEvidenceOnly", true)
                            put("frameContentCorrespondenceMeasured", false)
                            put("provenDomainVariantConstructed", false)
                        },
                    )
                },
            )
        }
    return camDiagnosticJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
}
