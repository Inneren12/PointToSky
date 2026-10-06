package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample

/** Shared PTS-03 test fixtures. Values are synthetic; nothing here is Pixel 9 evidence. */
internal object Pts03Fixtures {
    const val BUFFER_WIDTH_PX = 640
    const val BUFFER_HEIGHT_PX = 480

    /** A [Pts03CaptureResultReader] backed by a plain map; absent keys read as `null`. */
    fun reader(values: Map<Pts03CaptureResultField, Any?>): Pts03CaptureResultReader =
        Pts03CaptureResultReader { values[it] }

    /** Every per-result key present, with non-default values. */
    fun fullResultValues(
        timestampNanos: Long = 1_000L,
        activePhysicalId: String? = "3",
        distortionMode: Int? = 1,
    ): Map<Pts03CaptureResultField, Any?> =
        mapOf(
            Pts03CaptureResultField.SENSOR_TIMESTAMP to timestampNanos,
            Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID to activePhysicalId,
            Pts03CaptureResultField.DISTORTION_CORRECTION_MODE to distortionMode,
            Pts03CaptureResultField.LENS_FOCAL_LENGTH to 6.9f,
            Pts03CaptureResultField.LENS_INTRINSIC_CALIBRATION to floatArrayOf(5000f, 5001f, 2040f, 1530f, 0f),
            Pts03CaptureResultField.LENS_FOCUS_DISTANCE to 0.25f,
            Pts03CaptureResultField.LENS_STATE to 0,
            Pts03CaptureResultField.CONTROL_AF_MODE to 4,
            Pts03CaptureResultField.CONTROL_AF_STATE to 2,
            Pts03CaptureResultField.SCALER_CROP_REGION to Pts03IntRect(0, 0, 4080, 3072),
            Pts03CaptureResultField.CONTROL_ZOOM_RATIO to 1.0f,
            Pts03CaptureResultField.LENS_OPTICAL_STABILIZATION_MODE to 1,
            Pts03CaptureResultField.CONTROL_VIDEO_STABILIZATION_MODE to 0,
            Pts03CaptureResultField.HOT_PIXEL_MODE to 1,
            Pts03CaptureResultField.NOISE_REDUCTION_MODE to 2,
            Pts03CaptureResultField.EDGE_MODE to 1,
        )

    fun exposure(timestampNanos: Long?): SkyExposureSample =
        SkyExposureSample(
            exposureTimeNanos = 33_000_000L,
            sensitivityIso = 400,
            frameDurationNanos = 33_333_333L,
            aeMode = "ON",
            awbMode = "AUTO",
            sensorTimestampNanos = timestampNanos,
        )

    fun captureResult(
        timestampNanos: Long = 1_000L,
        activePhysicalId: String? = "3",
        distortionMode: Int? = 1,
        sdkInt: Int = 35,
        physicalIds: List<String> = emptyList(),
        physicalTimestampNanos: Long? = timestampNanos,
        physicalDistortionMode: Int? = distortionMode,
    ): SkyCaptureResultSnapshot =
        skyCaptureResultSnapshot(
            exposure = exposure(timestampNanos),
            logicalTruth =
                pts03CaptureTruthOf(
                    reader(fullResultValues(timestampNanos, activePhysicalId, distortionMode)),
                    sdkInt,
                ),
            physicalResults =
                physicalIds.map {
                    physicalResult(
                        it,
                        physicalTimestampNanos,
                        physicalDistortionMode,
                        sdkInt,
                    )
                },
        )

    /**
     * One physical result entry. Its values deliberately differ from the logical fixture (focal 2.2 mm,
     * crop 4032x3024, no active-ID key) so a test can tell which result a field came from.
     */
    fun physicalResult(
        id: String,
        timestampNanos: Long?,
        distortionMode: Int? = 1,
        sdkInt: Int = 35,
    ): Pts03PhysicalCaptureResult =
        pts03PhysicalCaptureResultOf(
            physicalCameraId = id,
            exposure = exposure(timestampNanos).copy(sensitivityIso = 800),
            reader =
                reader(
                    fullResultValues(timestampNanos ?: 0L, activePhysicalId = null, distortionMode = distortionMode) +
                        mapOf(
                            Pts03CaptureResultField.SENSOR_TIMESTAMP to timestampNanos,
                            Pts03CaptureResultField.LENS_FOCAL_LENGTH to 2.2f,
                            Pts03CaptureResultField.SCALER_CROP_REGION to Pts03IntRect(0, 0, 4032, 3024),
                        ),
                ),
            sdkInt = sdkInt,
        )

    fun frameMetadata(timestampNanos: Long): CameraFrameMetadata =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            rotationDegrees = 90,
            cropRectLeftPx = 0,
            cropRectTopPx = 0,
            cropRectRightPx = BUFFER_WIDTH_PX,
            cropRectBottomPx = BUFFER_HEIGHT_PX,
        )

    fun physicalSnapshot(cameraId: String = "3"): CameraCharacteristicsSnapshot =
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
            cameraId = cameraId,
        )

    fun logicalSnapshot(): CameraCharacteristicsSnapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(6.9f),
            sensorPhysicalWidthMm = 9.8f,
            sensorPhysicalHeightMm = 7.4f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4080,
            activeArrayBottomPx = 3072,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            isLogicalMultiCamera = true,
            cameraId = "0",
            physicalCameraIds = setOf("2", "3", "4"),
        )

    fun provenance(physicalId: String = "3"): PhysicalCameraProvenance =
        PhysicalCameraProvenance(
            logicalCameraId = "0",
            physicalCameraId = physicalId,
            bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
            bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
            confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
        )

    private fun orientationEvidence() =
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

    private fun gridGeometryEvidence() =
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

    /**
     * A noiseless, self-consistent frame-content snapshot (points forward-projected through a known pose and
     * the physical hypothesis's K), with an optional joined [captureResult].
     */
    fun snapshot(
        attemptId: Long = 1L,
        generation: Long = 0L,
        placement: TargetPlacementLabel = TargetPlacementLabel.CENTER,
        translationMm: Vec3 = Vec3(-40.0, -30.0, 600.0),
        captureResult: SkyCaptureResultSnapshot? = captureResult(physicalIds = listOf("3")),
        lightingAtCapture: Pts03LightingLabel = Pts03LightingLabel.UNSPECIFIED,
    ): FrameContentCorrespondenceSnapshot {
        val physical = physicalSnapshot()
        val matrix =
            predictCameraX142SensorToBufferMatrix(
                CameraBasisRect(0, 0, 4032, 3024),
                BUFFER_WIDTH_PX,
                BUFFER_HEIGHT_PX,
            )!!.matrix
        val hypotheses = computeFrameContentMappingHypotheses(physical, matrix, BUFFER_WIDTH_PX, BUFFER_HEIGHT_PX)
        val intrinsics =
            (
                hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }.result
                    as FrameContentHypothesisIntrinsicsResult.Available
            ).intrinsics
        val pose =
            FrameContentPoseSolution(
                rotation = RotationMatrix3.IDENTITY,
                translationMm = translationMm,
                rodriguesRvec = Vec3(0.0, 0.0, 0.0),
                poseSolverRmsResidualPx = 0.0,
            )
        val detected =
            frameContentTargetObjectPoints(DEFAULT_FRAME_CONTENT_TARGET_SPEC).map { op ->
                val p = projectObjectPoint(op, pose, intrinsics) as FrameContentProjectionOutcome.Projected
                DetectedTargetPoint(
                    pointId = op.pointId,
                    bufferXPx = p.xPx,
                    bufferYPx = p.yPx,
                    confidence = 1.0,
                    refinementStatus = CornerRefinementStatus.WEIGHTED_CENTROID_SUBPIXEL_ESTIMATE,
                    region = classifyPointRegion(p.xPx, p.yPx, BUFFER_WIDTH_PX, BUFFER_HEIGHT_PX),
                )
            }
        return buildFrameContentCorrespondenceSnapshot(
            attemptId = attemptId,
            generation = generation,
            requestedPhysicalCameraId = "3",
            provenance = provenance(),
            openedLogicalCharacteristics = logicalSnapshot(),
            selectedPhysicalCharacteristics = physical,
            requestedAnalysisResolutionWidthPx = BUFFER_WIDTH_PX,
            requestedAnalysisResolutionHeightPx = BUFFER_HEIGHT_PX,
            requestedAnalysisResolutionFamily = AnalysisResolutionFamily.NEAR_4_3,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            cropRectLeftPx = 0,
            cropRectTopPx = 0,
            cropRectRightPx = BUFFER_WIDTH_PX,
            cropRectBottomPx = BUFFER_HEIGHT_PX,
            rotationDegrees = 90,
            sensorToBufferTransformMatrix = matrix,
            zoomTargetRatio = 1.0f,
            observedZoomRatio = 1.0f,
            targetPlacementLabel = placement,
            distanceLabelMm = 600.0,
            detectionResult =
                FrameContentDetectionResult.Detected(
                    detected,
                    orientationEvidence(),
                    gridGeometryEvidence(),
                ),
            targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
            detectionTolerances = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES,
            capturedAtEpochMillis = 0L,
            captureResult = captureResult,
            lightingAtCapture = lightingAtCapture,
        )
    }

    /** A static characteristics reader backed by a map. */
    fun characteristicsReader(values: Map<Pts03CharacteristicsField, Any?>): Pts03CharacteristicsReader =
        Pts03CharacteristicsReader { values[it] }

    fun physicalCharacteristicsValues(
        rotationXyzw: FloatArray? = floatArrayOf(0f, 0f, 0f, 1f),
        reference: Int? = 0,
    ): Map<Pts03CharacteristicsField, Any?> =
        mapOf(
            Pts03CharacteristicsField.LENS_INFO_AVAILABLE_FOCAL_LENGTHS to floatArrayOf(6.9f),
            Pts03CharacteristicsField.SENSOR_INFO_PHYSICAL_SIZE to Pts03SizeF(9.8f, 7.4f),
            Pts03CharacteristicsField.SENSOR_INFO_PIXEL_ARRAY_SIZE to Pts03SizeF(4048f, 3040f),
            // 4032x3024 active array, consistent with [snapshot]'s physical camera; offset inside a larger
            // pre-correction array so the two bases genuinely differ.
            Pts03CharacteristicsField.SENSOR_INFO_ACTIVE_ARRAY_SIZE to Pts03IntRect(8, 8, 4040, 3032),
            Pts03CharacteristicsField.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE to Pts03IntRect(0, 0, 4048, 3040),
            Pts03CharacteristicsField.LENS_INTRINSIC_CALIBRATION to floatArrayOf(5000f, 5000f, 2048f, 1536f, 0f),
            Pts03CharacteristicsField.LENS_DISTORTION to floatArrayOf(0.01f, -0.02f, 0.003f, 0f, 0f),
            Pts03CharacteristicsField.DISTORTION_CORRECTION_AVAILABLE_MODES to intArrayOf(0, 1, 2),
            Pts03CharacteristicsField.LENS_POSE_ROTATION to rotationXyzw,
            Pts03CharacteristicsField.LENS_POSE_TRANSLATION to floatArrayOf(0.01f, 0f, 0f),
            Pts03CharacteristicsField.LENS_POSE_REFERENCE to reference,
            Pts03CharacteristicsField.SENSOR_ORIENTATION to 90,
            Pts03CharacteristicsField.LENS_FACING to 1,
            Pts03CharacteristicsField.REQUEST_AVAILABLE_CAPABILITIES to intArrayOf(0, 1),
            Pts03CharacteristicsField.INFO_SUPPORTED_HARDWARE_LEVEL to 3,
            Pts03CharacteristicsField.SENSOR_INFO_TIMESTAMP_SOURCE to 1,
        ).filterValues { it != null }

    fun logicalCharacteristicsValues(
        children: Set<String> = setOf("4", "2", "3"),
    ): Map<Pts03CharacteristicsField, Any?> =
        physicalCharacteristicsValues() +
            mapOf(
                Pts03CharacteristicsField.REQUEST_AVAILABLE_CAPABILITIES to
                    intArrayOf(0, 1, PTS03_CAPABILITY_LOGICAL_MULTI_CAMERA),
                Pts03CharacteristicsField.PHYSICAL_CAMERA_IDS to children,
            )

    fun characteristicsSet(): Pts03CameraCharacteristicsSet =
        buildPts03CameraCharacteristicsSet(
            logicalCameraId = "0",
            provider = { id ->
                characteristicsReader(
                    if (id ==
                        "0"
                    ) {
                        logicalCharacteristicsValues()
                    } else {
                        physicalCharacteristicsValues()
                    },
                )
            },
            sdkInt = 35,
        )
}
