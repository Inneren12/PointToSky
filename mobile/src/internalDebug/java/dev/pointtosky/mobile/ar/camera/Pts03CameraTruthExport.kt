package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * PTS-03 (`internalDebug`-only): the deterministic, machine-readable camera-truth export for one bind.
 *
 * Determinism: field order is fixed by construction (`buildJsonObject` keeps insertion order), every map is
 * emitted sorted by key, and nothing reads a clock or a live value — the caller passes the frozen
 * [Pts03TruthSessionState] and the export time. `null` is emitted as JSON `null` (never `0`, never omitted)
 * so "not reported" stays distinguishable from "zero".
 *
 * The file written to device storage is the authoritative evidence (`includeAllRetainedFrameRecords =
 * true`); the share-sheet variant omits raw frame records to stay well under the binder transaction limit.
 * Screenshots are never the record.
 *
 * ## Schema history
 * - `1`: initial PTS-03 export.
 */
internal const val PTS03_CAMERA_TRUTH_JSON_SCHEMA_VERSION: Int = 1

/**
 * The CameraX version this build declares (`gradle/libs.versions.toml` `camerax`). Pinned to the catalog by
 * `Pts03CameraTruthExportTest`, so it cannot silently go stale. Device evidence is attributed to it.
 */
internal const val PTS03_DECLARED_CAMERAX_VERSION: String = "1.4.2"

internal data class Pts03Environment(
    val deviceManufacturer: String?,
    val deviceModel: String?,
    val deviceName: String?,
    val buildFingerprint: String?,
    val androidRelease: String?,
    val sdkInt: Int,
    val securityPatch: String?,
    val appVersionName: String?,
    val appVersionCode: Int?,
    val cameraXVersion: String = PTS03_DECLARED_CAMERAX_VERSION,
)

internal fun pts03CurrentEnvironment(): Pts03Environment =
    Pts03Environment(
        deviceManufacturer = android.os.Build.MANUFACTURER,
        deviceModel = android.os.Build.MODEL,
        deviceName = android.os.Build.DEVICE,
        buildFingerprint = android.os.Build.FINGERPRINT,
        androidRelease = android.os.Build.VERSION.RELEASE,
        sdkInt = android.os.Build.VERSION.SDK_INT,
        securityPatch = android.os.Build.VERSION.SECURITY_PATCH,
        appVersionName = dev.pointtosky.mobile.BuildConfig.VERSION_NAME,
        appVersionCode = dev.pointtosky.mobile.BuildConfig.VERSION_CODE,
    )

// -------------------------------------------------------------------------------------------------
// Small JSON helpers
// -------------------------------------------------------------------------------------------------

private fun floats(values: List<Float>?): JsonElement =
    values?.let { v -> buildJsonArray { v.forEach { add(it.toDouble()) } } } ?: JsonNull

private fun doubles(values: List<Double>?): JsonElement =
    values?.let { v -> buildJsonArray { v.forEach { add(it) } } } ?: JsonNull

private fun strings(values: List<String>?): JsonElement =
    values?.let { v -> buildJsonArray { v.forEach { add(it) } } } ?: JsonNull

internal fun pts03RectJson(rect: Pts03IntRect?): JsonElement =
    rect?.let {
        buildJsonObject {
            put("left", it.left)
            put("top", it.top)
            put("right", it.right)
            put("bottom", it.bottom)
            put("width", it.width)
            put("height", it.height)
        }
    } ?: JsonNull

internal fun pts03EnumJson(value: Pts03EnumValue?): JsonElement =
    value?.let {
        buildJsonObject {
            put("raw", it.raw)
            put("name", it.name)
        }
    } ?: JsonNull

private fun countsJson(counts: Map<String, Long>): JsonObject =
    buildJsonObject { counts.toSortedMap().forEach { (k, v) -> put(k, v) } }

// -------------------------------------------------------------------------------------------------
// Per-frame CaptureResult
// -------------------------------------------------------------------------------------------------

/** The PTS-03 fields of one result object (top-level or physical) plus its exposure fields. */
private fun resultTruthJson(
    e: dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample,
    t: Pts03CaptureTruth,
): JsonObject {
    val effective = t.effectiveDistortionMode()
    return buildJsonObject {
        put("sensorTimestampNanos", t.sensorTimestampNanos)
        put("activePhysicalCameraId", t.activePhysicalCameraId)
        put("activePhysicalCameraIdAvailability", t.activePhysicalCameraIdAvailability.name)
        put("distortionCorrectionMode", pts03EnumJson(t.distortionCorrectionMode))
        put("distortionCorrectionModeAvailability", t.distortionCorrectionModeAvailability.name)
        put("effectiveDistortionMode", effective.label)
        put("metadataCoordinateBasis", effective.metadataCoordinateBasis().name)
        put("lensFocalLengthMm", t.lensFocalLengthMm?.toDouble())
        put("lensIntrinsicCalibration", floats(t.lensIntrinsicCalibration))
        put("lensFocusDistanceDiopters", t.lensFocusDistanceDiopters?.toDouble())
        put("lensState", pts03EnumJson(t.lensState))
        put("controlAfMode", pts03EnumJson(t.controlAfMode))
        put("controlAfState", pts03EnumJson(t.controlAfState))
        put("scalerCropRegion", pts03RectJson(t.scalerCropRegion))
        put("controlZoomRatio", t.controlZoomRatio?.toDouble())
        put("controlZoomRatioAvailability", t.controlZoomRatioAvailability.name)
        put("activePhysicalSensorCropRegion", pts03RectJson(t.activePhysicalSensorCropRegion))
        put("activePhysicalSensorCropRegionAvailability", t.activePhysicalSensorCropRegionAvailability.name)
        put("sensorExposureTimeNanos", e.exposureTimeNanos)
        put("sensorSensitivityIso", e.sensitivityIso)
        put("sensorFrameDurationNanos", e.frameDurationNanos)
        put("controlAeMode", e.aeMode)
        put("controlAwbMode", e.awbMode)
        put("lensOpticalStabilizationMode", pts03EnumJson(t.lensOpticalStabilizationMode))
        put("controlVideoStabilizationMode", pts03EnumJson(t.controlVideoStabilizationMode))
        put("hotPixelMode", pts03EnumJson(t.hotPixelMode))
        put("noiseReductionMode", pts03EnumJson(t.noiseReductionMode))
        put("edgeMode", pts03EnumJson(t.edgeMode))
    }
}

/**
 * One joined `TotalCaptureResult`: the top-level (logical) result and every per-physical-camera result,
 * kept apart, physical IDs in sorted order, nulls kept as JSON null.
 */
internal fun pts03CaptureResultJson(captureResult: SkyCaptureResultSnapshot?): JsonElement {
    if (captureResult == null) return JsonNull
    return buildJsonObject {
        put("sensorTimestampNanos", captureResult.sensorTimestampNanos)
        put("logicalTopLevelResult", resultTruthJson(captureResult.exposure, captureResult.logicalTruth))
        put(
            "physicalResultsByCameraId",
            buildJsonObject {
                captureResult.physicalResultsByCameraId.toSortedMap().forEach { (id, r) ->
                    put(id, resultTruthJson(r.exposure, r.truth))
                }
            },
        )
    }
}

/** One frame's camera attribution (A1/A2 semantics; see [attributePts03Frame]). */
internal fun pts03AttributionJson(a: Pts03FrameCameraAttribution): JsonObject =
    buildJsonObject {
        put("sessionClass", a.sessionClass.name)
        put("requestedPhysicalCameraId", a.requestedPhysicalCameraId)
        put("logicalActivePhysicalCameraId", a.logicalActivePhysicalCameraId)
        put(
            "logicalActivePhysicalCameraIdRole",
            if (a.sessionClass ==
                Pts03SessionClass.EXPLICIT_PHYSICAL
            ) {
                "DIAGNOSTIC_ONLY"
            } else {
                "PRODUCER"
            },
        )
        put("producingPhysicalCameraId", a.producingPhysicalCameraId)
        put("dynamicMetadataSource", a.dynamicMetadataSource.name)
        put("physicalResultStatus", a.physicalResultStatus?.name)
        put("physicalResultSensorTimestampNanos", a.physicalResultSensorTimestampNanos)
        put(
            "physicalResultTimestampMatched",
            a.physicalResultStatus?.let {
                it ==
                    Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED
            },
        )
        put("physicalDynamicMetadataResolved", a.physicalDynamicMetadataResolved)
        put("effectiveDistortionMode", a.effectiveDistortionMode().label)
    }

internal fun pts03GeometryJson(geometry: Pts03AnalysisGeometry): JsonObject =
    buildJsonObject {
        put("bufferWidthPx", geometry.bufferWidthPx)
        put("bufferHeightPx", geometry.bufferHeightPx)
        put("cropRect", pts03RectJson(geometry.cropRect))
        put("rotationDegrees", geometry.rotationDegrees)
        put("sensorToBufferTransformMatrix", doubles(geometry.matrixRowMajor))
    }

internal fun pts03JoinStatisticsJson(stats: SkyJoinStatistics): JsonObject =
    buildJsonObject {
        put("joinKey", "CaptureResult.SENSOR_TIMESTAMP == ImageProxy.imageInfo.timestamp (exact equality)")
        put("analysisFrameCount", stats.analysisFrameCount)
        put("captureResultCount", stats.captureResultCount)
        put("matchedCount", stats.matchedCount)
        put("matchedFraction", stats.matchedFraction)
        put("frameTimedOutCount", stats.frameTimedOutCount)
        put("captureResultTimedOutCount", stats.captureResultTimedOutCount)
        put("frameEvictedCount", stats.frameEvictedCount)
        put("captureResultEvictedCount", stats.captureResultEvictedCount)
        put("duplicateFrameTimestampCount", stats.duplicateFrameTimestampCount)
        put("duplicateCaptureTimestampCount", stats.duplicateCaptureTimestampCount)
        put("unkeyedCaptureResultCount", stats.unkeyedCaptureResultCount)
        put("framesPendingAtStopCount", stats.framesPendingAtStopCount)
        put("captureResultsPendingAtStopCount", stats.captureResultsPendingAtStopCount)
        put("offersIgnoredAfterFinalizeCount", stats.offersIgnoredAfterFinalizeCount)
    }

// -------------------------------------------------------------------------------------------------
// Static characteristics and extrinsics
// -------------------------------------------------------------------------------------------------

internal fun pts03StaticCharacteristicsJson(c: Pts03CameraStaticCharacteristics): JsonObject =
    buildJsonObject {
        val s = c.snapshot
        put("cameraId", c.cameraId)
        put("role", c.role.name)
        put("readFailure", c.readFailure)
        put("isLogicalMultiCamera", s?.isLogicalMultiCamera)
        put("physicalCameraIds", strings(s?.physicalCameraIds?.sorted()))
        put("lensInfoAvailableFocalLengthsMm", floats(s?.availableFocalLengthsMm?.toList()))
        val physicalWidth = s?.sensorPhysicalWidthMm
        val physicalHeight = s?.sensorPhysicalHeightMm
        put(
            "sensorInfoPhysicalSizeMm",
            if (physicalWidth != null &&
                physicalHeight != null
            ) {
                doubles(listOf(physicalWidth.toDouble(), physicalHeight.toDouble()))
            } else {
                JsonNull
            },
        )
        val pixelWidth = s?.pixelArrayWidthPx
        val pixelHeight = s?.pixelArrayHeightPx
        put(
            "sensorInfoPixelArraySize",
            if (pixelWidth != null &&
                pixelHeight != null
            ) {
                buildJsonArray {
                    add(pixelWidth)
                    add(pixelHeight)
                }
            } else {
                JsonNull
            },
        )
        val arrays = s?.arrayRelationship()
        put("sensorInfoActiveArraySize", pts03RectJson(arrays?.activeArray))
        put("sensorInfoPreCorrectionActiveArraySize", pts03RectJson(arrays?.preCorrectionActiveArray))
        put("activeEqualsPreCorrection", arrays?.arraysIdentical)
        put("lensIntrinsicCalibration", floats(s?.lensIntrinsicCalibration?.toList()))
        put("lensIntrinsicCalibrationBasis", Pts03MetadataCoordinateBasis.PRE_CORRECTION_ACTIVE_ARRAY.name)
        put("lensDistortion", floats(s?.lensDistortion?.toList()))
        put("lensDistortionApplied", false)
        put(
            "distortionCorrectionAvailableModes",
            c.distortionCorrectionAvailableModes?.let { m -> JsonArray(m.map { pts03EnumJson(it) }) } ?: JsonNull,
        )
        put("lensPoseRotationXyzw", floats(c.lensPoseRotationXyzw))
        put("lensPoseTranslationMeters", floats(c.lensPoseTranslationMeters))
        put("lensPoseReference", pts03EnumJson(c.lensPoseReference))
        put("sensorOrientationDegrees", c.sensorOrientationDegrees)
        put("lensFacing", pts03EnumJson(c.lensFacing))
        put(
            "requestAvailableCapabilities",
            c.availableCapabilities?.let { m -> JsonArray(m.map { pts03EnumJson(it) }) } ?: JsonNull,
        )
        put("infoSupportedHardwareLevel", pts03EnumJson(c.supportedHardwareLevel))
        put("sensorInfoTimestampSource", pts03EnumJson(c.timestampSource))
    }

internal fun pts03ExtrinsicJson(e: Pts03ExtrinsicEvidence): JsonObject =
    buildJsonObject {
        put("cameraId", e.cameraId)
        put("lensPoseRotationXyzw", floats(e.rawRotationXyzw))
        put("quaternionInputOrder", "[x, y, z, w] (Camera2), rotation S -> Cphys")
        put("lensPoseTranslationMeters", floats(e.rawTranslationMeters))
        put("lensPoseReference", pts03EnumJson(e.reference))
        put("rotationDefect", e.rotationDefect?.name)
        put("rCphysFromSRowMajor", doubles(e.rotation?.rCphysFromS?.toRowMajorList()))
        put("cameraXAxisInS", doubles(e.rotation?.cameraXAxisInS?.toList()))
        put("cameraYAxisInS", doubles(e.rotation?.cameraYAxisInS?.toList()))
        put("cameraZAxisInS", doubles(e.rotation?.cameraZAxisInS?.toList()))
        put("angleCameraZToNominalRearOpticalAxisDeg", e.angleCameraZToNominalRearOpticalAxisDeg)
        put("platformPoseStatus", e.platformPoseStatus.name)
        put("extrinsicInUseByPointToSky", e.extrinsicInUseByPointToSky.name)
        put("reasons", strings(e.reasons))
    }

// -------------------------------------------------------------------------------------------------
// Group B / C
// -------------------------------------------------------------------------------------------------

internal fun pts03DomainEvidenceJson(d: Pts03ProjectionDomainEvidence): JsonObject =
    buildJsonObject {
        put("requestedPhysicalCameraId", d.requestedPhysicalCameraId)
        put("producingPhysicalCameraId", d.producingPhysicalCameraId)
        put("logicalActivePhysicalCameraIdDiagnostic", d.logicalActivePhysicalCameraId)
        put("bufferWidthPx", d.bufferWidthPx)
        put("bufferHeightPx", d.bufferHeightPx)
        put("sensorToBufferTransformMatrix", doubles(d.matrixRowMajor))
        put("logicalWholeActiveArrayHypothesis", d.logicalWholeActiveArrayVerdict?.name)
        put("physicalWholeActiveArrayHypothesis", d.physicalWholeActiveArrayVerdict?.name)
        put("physicalWholePreCorrectionArrayHypothesis", d.physicalWholePreCorrectionArrayVerdict?.name)
        put("sensorToBufferDomainProof", d.domainProof.toString())
        put("outcome", d.outcome.name)
        put("unlocksCalibratedProjection", d.unlocksCalibratedProjection)
        put("frameContentVerdict", d.frameContentVerdict)
        put("note", d.note)
    }

internal fun pts03ResidualMeasurementJson(m: Pts03ResidualDistortionMeasurement): JsonObject =
    buildJsonObject {
        put("verdict", m.verdict.name)
        put("reason", m.reason)
        put("pointCount", m.pointCount)
        put("placements", strings(m.placements.map { it.name }))
        put("maxNormalizedRadius", m.maxNormalizedRadius)
        put("rmsResidualPx", m.rmsResidualPx)
        put("maxResidualPx", m.maxResidualPx)
        put(
            "radialModel",
            "d_radial = a*rho + b*rho^3, rho = r / (buffer half-diagonal), r from pose-reference principal point",
        )
        put("linearCoefficientPx", m.linearCoefficientPx)
        put("cubicCoefficientPx", m.cubicCoefficientPx)
        put("cubicStandardErrorPx", m.cubicStandardErrorPx)
        put("cubicDisplacementAtMaxRadiusPx", m.cubicDisplacementAtMaxRadiusPx)
        put("fitResidualRmsPx", m.fitResidualRmsPx)
        put(
            "meanRadialResidualByRadiusBinPx",
            m.meanRadialResidualByRadiusBinPx.let { bins ->
                JsonArray(bins.map { JsonPrimitive(it) })
            },
        )
        put(
            "thresholds",
            buildJsonObject {
                put("minPoints", m.thresholds.minPoints)
                put("minPlacements", m.thresholds.minPlacements)
                put("minCoverageNormalizedRadius", m.thresholds.minCoverageNormalizedRadius)
                put("minDisplacementPx", m.thresholds.minDisplacementPx)
                put("minSignificance", m.thresholds.minSignificance)
            },
        )
    }

internal fun pts03DistortionChainJson(c: Pts03DistortionChain): JsonObject =
    buildJsonObject {
        put("cameraId", c.cameraId)
        put("requestedMode", c.requested.name)
        put("effectiveModes", strings(c.effectiveModes))
        put("metadataCoordinateBasis", c.metadataCoordinateBasis.name)
        put("activeArray", pts03RectJson(c.arrays?.activeArray))
        put("preCorrectionActiveArray", pts03RectJson(c.arrays?.preCorrectionActiveArray))
        put("activeEqualsPreCorrection", c.arrays?.arraysIdentical)
        put("intrinsicCalibrationSourceBasis", c.intrinsicCalibrationSourceBasis.name)
        put("availableModes", strings(c.availableModes))
        put("residualDistortion", pts03ResidualMeasurementJson(c.residual))
        put("applicationDistortionModelGuidance", c.guidance.name)
        put("sensorToBufferMatrixDomainOutcome", c.matrixDomainOutcome.name)
        put("finalPixelDomainEstablished", c.finalPixelDomainEstablished)
        put("finalPixelDomainNote", c.finalPixelDomainNote)
        put("lensDistortionAppliedByPointToSky", false)
    }

private fun evidenceCaptureJson(capture: Pts03TargetEvidenceCapture): JsonObject {
    val snapshot = capture.snapshot
    val referenceSummary = snapshot.summariesByHypothesis[snapshot.poseReferenceHypothesis]
    return buildJsonObject {
        put("captureIndex", capture.captureIndex)
        put("generation", snapshot.generation)
        put("lighting", capture.lighting.name)
        put("lightingSource", "FRAME_AT_CAPTURE")
        put("targetPlacementLabel", snapshot.targetPlacementLabel.name)
        put("distanceLabelMm", snapshot.distanceLabelMm)
        put("requestedDistortionMode", capture.requestedDistortionMode.name)
        put("effectiveDistortionMode", capture.effectiveDistortionMode.label)
        put("attribution", pts03AttributionJson(capture.attribution))
        put("captureResult", pts03CaptureResultJson(snapshot.captureResult))
        put(
            "geometry",
            pts03GeometryJson(
                Pts03AnalysisGeometry(
                    snapshot.bufferWidthPx,
                    snapshot.bufferHeightPx,
                    if (snapshot.cropRectLeftPx != null && snapshot.cropRectTopPx != null &&
                        snapshot.cropRectRightPx != null &&
                        snapshot.cropRectBottomPx != null
                    ) {
                        Pts03IntRect(
                            snapshot.cropRectLeftPx,
                            snapshot.cropRectTopPx,
                            snapshot.cropRectRightPx,
                            snapshot.cropRectBottomPx,
                        )
                    } else {
                        null
                    },
                    snapshot.rotationDegrees,
                    snapshot.sensorToBufferTransformMatrix?.rowMajor(),
                ),
            ),
        )
        put("detectedPointCount", snapshot.detectedPoints.size)
        put("poseReferenceHypothesis", snapshot.poseReferenceHypothesis.name)
        put("poseSolverRmsResidualPx", snapshot.pose?.poseSolverRmsResidualPx)
        put(
            "hypothesisRmsPx",
            buildJsonObject {
                snapshot.summariesByHypothesis.toSortedMap().forEach { (id, s) ->
                    put(id.name, s.rmsPx)
                }
            },
        )
        put("poseReferenceMaxResidualPx", referenceSummary?.maxPx)
        put("frameContentVerdict", snapshot.verdict.verdict.name)
        put("projectionDomain", pts03DomainEvidenceJson(capture.domainEvidence))
        put(
            "residualPoints",
            buildJsonArray {
                capture.residualPoints.forEach { p ->
                    add(
                        buildJsonObject {
                            put("observedXPx", p.observedXPx)
                            put("observedYPx", p.observedYPx)
                            put("predictedXPx", p.predictedXPx)
                            put("predictedYPx", p.predictedYPx)
                            put("centerXPx", p.centerXPx)
                            put("centerYPx", p.centerYPx)
                        },
                    )
                }
            },
        )
        put(
            "residualDistortionThisPlacement",
            pts03ResidualMeasurementJson(measurePts03ResidualDistortion(capture.residualPoints)),
        )
    }
}

private fun frameRecordJson(r: Pts03FrameTruthRecord): JsonObject =
    buildJsonObject {
        put("frameIndex", r.frameIndex)
        put("sensorTimestampNanos", r.sensorTimestampNanos)
        put("lighting", r.lighting.name)
        put("geometry", pts03GeometryJson(r.geometry))
        put("attribution", pts03AttributionJson(r.attribution))
        put("captureResult", pts03CaptureResultJson(r.captureResult))
    }

private fun nestedCountsJson(m: Map<String, Map<String, Long>>): JsonObject =
    buildJsonObject { m.toSortedMap().forEach { (k, v) -> put(k, countsJson(v)) } }

private fun <V> listsJson(
    m: Map<String, List<V>>,
    element: (V) -> JsonElement,
): JsonObject = buildJsonObject { m.toSortedMap().forEach { (k, v) -> put(k, JsonArray(v.map(element))) } }

private fun identityJson(
    summary: Pts03IdentitySummary,
    session: Pts03TruthSessionState,
): JsonObject =
    buildJsonObject {
        put("matchedFrameCount", summary.matchedFrameCount)
        put(
            "logicalTopLevel",
            buildJsonObject {
                put(
                    "role",
                    if (session.sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL) {
                        "DIAGNOSTIC_ONLY: describes the logical camera's backing sensor, " +
                            "not the explicit physical output"
                    } else {
                        "PRODUCER: the A1 logical stream's producing physical camera"
                    },
                )
                put("framesByLogicalActivePhysicalId", countsJson(summary.framesByLogicalActivePhysicalId))
                put("framesWithNullActivePhysicalId", summary.framesWithNullActivePhysicalId)
                put(
                    "activePhysicalIdAvailabilityCounts",
                    countsJson(
                        summary.activePhysicalIdAvailabilityCounts.mapKeys {
                            it.key.name
                        },
                    ),
                )
                put(
                    "framesByLightingAndLogicalActivePhysicalId",
                    buildJsonObject {
                        summary.framesByLightingAndLogicalActivePhysicalId
                            .toSortedMap(
                                compareBy { it.ordinal },
                            ).forEach { (l, m) ->
                                put(l.name, countsJson(m))
                            }
                    },
                )
                put(
                    "transitionRule",
                    "between two REPORTED active IDs only; frames without the key are gaps, not identities",
                )
                put("transitionCount", summary.transitionCount)
                put(
                    "transitions",
                    buildJsonArray {
                        summary.transitions.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("frameIndex", t.frameIndex)
                                    put("sensorTimestampNanos", t.sensorTimestampNanos)
                                    put("from", t.fromActivePhysicalCameraId)
                                    put("to", t.toActivePhysicalCameraId)
                                    put("lighting", t.lighting.name)
                                },
                            )
                        }
                    },
                )
            },
        )
        put(
            "explicitPhysicalOutput",
            buildJsonObject {
                put("requestedPhysicalOutputId", session.requestedPhysicalCameraId)
                put(
                    "physicalResultStatus",
                    summarizePts03ExplicitPhysicalResults(
                        session.sessionClass,
                        session.requestedPhysicalCameraId,
                        summary,
                    ).name,
                )
                put(
                    "physicalResultStatusCounts",
                    countsJson(summary.physicalResultStatusCounts.mapKeys { it.key.name }),
                )
            },
        )
        put("framesByPhysicalResultCameraId", countsJson(summary.framesByPhysicalResultCameraId))
        put("dynamicMetadataSourceCounts", countsJson(summary.dynamicMetadataSourceCounts.mapKeys { it.key.name }))
        put(
            "producingCamera",
            buildJsonObject {
                put(
                    "rule",
                    "A1: reported logical active ID; A2: requested physical output ID; " +
                        "dynamic values only from the attributed result",
                )
                put("framesByProducingPhysicalId", countsJson(summary.framesByProducingPhysicalId))
                put(
                    "focalLengthsMmByProducingPhysicalId",
                    listsJson(summary.focalLengthsMmByProducingPhysicalId) {
                        JsonPrimitive(it.toDouble())
                    },
                )
                put(
                    "intrinsicsByProducingPhysicalId",
                    listsJson(summary.intrinsicsByProducingPhysicalId) { floats(it) },
                )
                put(
                    "cropRegionsByProducingPhysicalId",
                    listsJson(summary.cropRegionsByProducingPhysicalId) { pts03RectJson(it) },
                )
                put(
                    "zoomRatiosByProducingPhysicalId",
                    listsJson(summary.zoomRatiosByProducingPhysicalId) {
                        JsonPrimitive(it.toDouble())
                    },
                )
                put(
                    "activePhysicalSensorCropRegionsByProducingPhysicalId",
                    listsJson(summary.activePhysicalSensorCropRegionsByProducingPhysicalId) { pts03RectJson(it) },
                )
                put(
                    "effectiveDistortionModeCountsByProducingPhysicalId",
                    nestedCountsJson(summary.effectiveDistortionModeCountsByProducingPhysicalId),
                )
                put("afStateCountsByProducingPhysicalId", nestedCountsJson(summary.afStateCountsByProducingPhysicalId))
            },
        )
    }

/**
 * Builds the export. [includeAllRetainedFrameRecords] `true` writes every retained raw frame record (the
 * on-device evidence file); `false` writes summaries only (share sheet).
 */
internal fun buildPts03CameraTruthJson(
    session: Pts03TruthSessionState,
    environment: Pts03Environment,
    exportedAtEpochMillis: Long,
    includeAllRetainedFrameRecords: Boolean,
): String {
    val root =
        buildJsonObject {
            put("schema", "pointtosky.pts03.camera_truth")
            put("schemaVersion", PTS03_CAMERA_TRUTH_JSON_SCHEMA_VERSION)
            put("sessionId", session.sessionId)
            put("exportedAtEpochMillis", exportedAtEpochMillis)
            put(
                "environment",
                buildJsonObject {
                    put("deviceManufacturer", environment.deviceManufacturer)
                    put("deviceModel", environment.deviceModel)
                    put("deviceName", environment.deviceName)
                    put("buildFingerprint", environment.buildFingerprint)
                    put("androidRelease", environment.androidRelease)
                    put("sdkInt", environment.sdkInt)
                    put("securityPatch", environment.securityPatch)
                    put("appVersionName", environment.appVersionName)
                    put("appVersionCode", environment.appVersionCode)
                    put("cameraXVersion", environment.cameraXVersion)
                },
            )
            put(
                "session",
                buildJsonObject {
                    put("sessionClass", session.sessionClass.name)
                    put("logicalCameraId", session.logicalCameraId)
                    put("requestedPhysicalCameraId", session.requestedPhysicalCameraId)
                    put("requestedDistortionMode", session.requestedDistortionMode.name)
                    put("lightingAtExport", session.lighting.name)
                    put("finalized", session.finalized)
                    put("zoomPinned", session.sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL)
                },
            )
            put(
                "safety",
                buildJsonObject {
                    put("sensorToBufferDomainProofProvenConstructed", false)
                    put("analysisBufferIntrinsicsPublished", false)
                    put("lensDistortionApplied", false)
                    put("cameraStrategySelected", false)
                    put(
                        "note",
                        "PTS-03 evidence only. A known physical ID, a matching hypothesis, a small residual " +
                            "or a present platform pose is not proof beyond its own class.",
                    )
                },
            )
            put(
                "streamConfiguration",
                buildJsonObject {
                    put("requestedAnalysisWidthPx", session.requestedAnalysisWidthPx)
                    put("requestedAnalysisHeightPx", session.requestedAnalysisHeightPx)
                    put(
                        "requestedAnalysisResolution",
                        if (session.requestedAnalysisWidthPx ==
                            null
                        ) {
                            "CAMERAX_DEFAULT"
                        } else {
                            "EXPLICIT"
                        },
                    )
                    put(
                        "analysisResolutionInfo",
                        resolutionInfoJson(session.streamConfiguration?.analysisResolutionInfo),
                    )
                    put("previewResolutionInfo", resolutionInfoJson(session.streamConfiguration?.previewResolutionInfo))
                    put(
                        "observedAnalysisGeometries",
                        buildJsonArray {
                            session.analysisGeometryCounts.entries
                                .sortedWith(
                                    compareByDescending<Map.Entry<Pts03AnalysisGeometry, Long>> {
                                        it.value
                                    }.thenBy {
                                        it.key
                                            .toString()
                                    },
                                ).forEach { (g, n) ->
                                    add(
                                        buildJsonObject {
                                            put("frameCount", n)
                                            put("geometry", pts03GeometryJson(g))
                                        },
                                    )
                                }
                        },
                    )
                },
            )
            put("joinStatistics", pts03JoinStatisticsJson(session.joinStatistics))
            put("groupA_identity", identityJson(session.identity, session))
            val set = session.characteristics
            put(
                "staticCharacteristics",
                set?.let { s ->
                    buildJsonObject {
                        put("logicalCameraId", s.logicalCameraId)
                        put("declaredPhysicalCameraIds", strings(s.declaredPhysicalCameraIds))
                        put("logical", pts03StaticCharacteristicsJson(s.logical))
                        put(
                            "physicalChildren",
                            JsonArray(s.physicalChildren.map { pts03StaticCharacteristicsJson(it) }),
                        )
                    }
                } ?: JsonNull,
            )
            put(
                "groupD_extrinsics",
                set?.let { s -> JsonArray(s.physicalChildren.map { pts03ExtrinsicJson(classifyPts03Extrinsics(it)) }) }
                    ?: JsonNull,
            )
            put("groupB_and_C_targetEvidence", JsonArray(session.evidenceCaptures.map { evidenceCaptureJson(it) }))
            put("groupC_distortionChains", JsonArray(session.distortionChains().map { pts03DistortionChainJson(it) }))
            put("totalMatchedFrameRecords", session.totalFrameRecords)
            put(
                "omittedFrameRecords",
                if (includeAllRetainedFrameRecords) session.omittedFrameRecords else session.totalFrameRecords,
            )
            put("rawFrameRecordsIncluded", includeAllRetainedFrameRecords)
            put(
                "frameRecords",
                if (includeAllRetainedFrameRecords) {
                    JsonArray((session.headRecords + session.tailRecords).map { frameRecordJson(it) })
                } else {
                    JsonArray(emptyList())
                },
            )
        }
    return root.toString()
}

private fun resolutionInfoJson(info: Pts03ResolutionInfo?): JsonElement =
    info?.let {
        buildJsonObject {
            put("widthPx", it.widthPx)
            put("heightPx", it.heightPx)
            put("cropRect", pts03RectJson(it.cropRect))
            put("rotationDegrees", it.rotationDegrees)
        }
    } ?: JsonPrimitive("UNKNOWN")

/** A short, human-readable HUD summary of the same frozen session state. */
internal fun buildPts03CameraTruthSummaryText(session: Pts03TruthSessionState): String =
    buildString {
        val stats = session.joinStatistics
        appendLine("PTS-03 ${session.sessionId} class=${session.sessionClass} lighting=${session.lighting}")
        appendLine(
            "join: frames=${stats.analysisFrameCount} results=${stats.captureResultCount} " +
                "matched=${stats.matchedCount} " +
                "fraction=${stats.matchedFraction?.let { "%.3f".format(it) } ?: "n/a"}",
        )
        appendLine("finalized=${session.finalized}")
        appendLine(
            "logicalActiveIds=${session.identity.framesByLogicalActivePhysicalId.toSortedMap()} " +
                "transitions(reported-only)=${session.identity.transitionCount}",
        )
        if (session.sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL) {
            appendLine(
                "A2 output=${session.requestedPhysicalCameraId} physicalResult=" +
                    summarizePts03ExplicitPhysicalResults(
                        session.sessionClass,
                        session.requestedPhysicalCameraId,
                        session.identity,
                    ) +
                    " (logical active ID is diagnostic only)",
            )
        }
        appendLine(
            "distortion requested=${session.requestedDistortionMode} effective(by producer)=" +
                session.identity.effectiveDistortionModeCountsByProducingPhysicalId.toSortedMap(),
        )
        appendLine("evidenceCaptures=${session.evidenceCaptures.size}")
        session.distortionChains().forEach {
            appendLine(
                "  C cam=${it.cameraId} modes=${it.effectiveModes} residual=${it.residual.verdict} " +
                    "B=${it.matrixDomainOutcome}",
            )
        }
    }
