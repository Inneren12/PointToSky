package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification

/**
 * Shared synthetic fixtures for the SKY-1 session-log tests. Deliberately plain values: these tests
 * exist to prove the log format and the replay path work without a device, so nothing here reads a
 * file, a camera, or a sensor.
 */
internal object SkySessionLogFixtures {
    const val BUFFER_WIDTH_PX = 640
    const val BUFFER_HEIGHT_PX = 480
    const val VIEWPORT_WIDTH_PX = 1080
    const val VIEWPORT_HEIGHT_PX = 2400
    const val FRAME_TIMESTAMP_NANOS = 1_000_000_000L

    /** Identity device→world rotation: the device looks along -Z with world axes aligned to device axes. */
    val identityRotationMatrix: List<Double> = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** A 30° rotation about the world Z axis, so tests are not all run on a degenerate identity pose. */
    val tiltedRotationMatrix: List<Double> =
        listOf(
            0.8660254037844387, -0.5, 0.0,
            0.5, 0.8660254037844387, 0.0,
            0.0, 0.0, 1.0,
        )

    fun intrinsics(
        widthPx: Int = BUFFER_WIDTH_PX,
        heightPx: Int = BUFFER_HEIGHT_PX,
    ): CameraIntrinsicsResolution =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = 66.0,
                verticalFovDeg = 52.0,
                focalLengthMm = 4.38,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.AnalysisBuffer(widthPx = widthPx, heightPx = heightPx),
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            ),
        )

    fun frameMetadata(
        timestampNanos: Long = FRAME_TIMESTAMP_NANOS,
        rotationDegrees: Int = 0,
        withCropRect: Boolean = false,
        withSensorToBufferTransform: Boolean = false,
    ): CameraFrameMetadata =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            rotationDegrees = rotationDegrees,
            cropRectLeftPx = if (withCropRect) 0 else null,
            cropRectTopPx = if (withCropRect) 0 else null,
            cropRectRightPx = if (withCropRect) BUFFER_WIDTH_PX else null,
            cropRectBottomPx = if (withCropRect) BUFFER_HEIGHT_PX else null,
            sensorToBufferTransform =
                if (withSensorToBufferTransform) {
                    SensorToBufferMatrix3(0.25, 0.0, -8.0, 0.0, 0.25, -6.0, 0.0, 0.0, 1.0)
                } else {
                    null
                },
        )

    fun lumaReference(
        path: String = "frames/frame_000000.y",
        rowStridePx: Int = BUFFER_WIDTH_PX,
    ): SkyLumaReference =
        SkyLumaReference(
            path = path,
            format = SkyLumaFormat.RAW_Y8,
            widthPx = BUFFER_WIDTH_PX,
            heightPx = BUFFER_HEIGHT_PX,
            rowStridePx = rowStridePx,
            byteLength = rowStridePx.toLong() * BUFFER_HEIGHT_PX,
        )

    fun pose(
        timestampNanos: Long = FRAME_TIMESTAMP_NANOS,
        rotationMatrix: List<Double> = tiltedRotationMatrix,
        frameTimestampNanos: Long = FRAME_TIMESTAMP_NANOS,
    ): SkyPoseSample =
        SkyPoseSample(
            timestampNanos = timestampNanos,
            rotationMatrix = rotationMatrix,
            frameToPoseDeltaNanos = frameTimestampNanos - timestampNanos,
        )

    fun observer(
        magneticDeclinationDeg: Double? = 11.5,
        utcEpochMillis: Long = 1_767_225_600_000L,
    ): SkyObserverContext =
        SkyObserverContext(
            latitudeDeg = 50.4501,
            longitudeDeg = 30.5234,
            utcEpochMillis = utcEpochMillis,
            horizontalAccuracyM = 8.5,
            magneticDeclinationDeg = magneticDeclinationDeg,
        )

    fun exposure(): SkyExposureSample =
        SkyExposureSample(
            exposureTimeNanos = 500_000_000L,
            sensitivityIso = 1600,
            frameDurationNanos = 520_000_000L,
            aeMode = "OFF",
            awbMode = "AUTO",
            sensorTimestampNanos = FRAME_TIMESTAMP_NANOS,
        )

    fun header(
        clockAlignment: SkyClockAlignment =
            SkyClockAlignment(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                poseToFrameOffsetNanos = 0L,
            ),
        intrinsics: CameraIntrinsicsResolution = intrinsics(),
        maxPairDeltaNanos: Long = 25_000_000L,
        calibration: SkyCalibrationRecord? = null,
    ): SkySessionLogHeader =
        SkySessionLogHeader(
            sessionId = "sky-20260806-201500",
            startedAtEpochMillis = 1_767_225_600_000L,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            intrinsics =
                intrinsics.toSkyIntrinsicsRecord(
                    pinhole = SkyPinholeRecord(fxPx = 489.0, fyPx = 491.5, cxPx = 320.0, cyPx = 240.0),
                ),
            clockAlignment = clockAlignment,
            maxPairDeltaNanos = maxPairDeltaNanos,
            clockMismatchThresholdNanos = 250_000_000L,
            deviceModel = "Pixel 9",
            cameraId = "0",
            physicalCameraIds = listOf("2", "3"),
            calibration = calibration,
            notes = "synthetic fixture",
        )

    fun calibration(): SkyCalibrationRecord =
        SkyCalibrationRecord(
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3072,
            activeArrayLeftPx = 0.0,
            activeArrayTopPx = 0.0,
            activeArrayRightPx = 4080.0,
            activeArrayBottomPx = 3072.0,
            sensorWidthMm = 5.76,
            sensorHeightMm = 4.29,
            focalLengthMm = 4.38,
            activeFxPx = 3102.5,
            activeFyPx = 3137.0,
            activeCxPx = 2040.0,
            activeCyPx = 1536.0,
            bufferFxPx = 489.0,
            bufferFyPx = 491.5,
            bufferCxPx = 320.0,
            bufferCyPx = 240.0,
            quality = "APPROXIMATE_PRINCIPAL_POINT",
            sensorToBufferMappingSource = "SENSOR_TO_BUFFER_MATRIX",
            transformClass = "AXIS_ALIGNED_SCALE_TRANSLATE",
        )

    /**
     * Stars spread across the sky so the batch exercises both in-front and behind-camera outcomes,
     * with the pixel coordinates left `null` — [predictedStarsFor] fills them in from a real
     * projection run so a fixture can never claim a coordinate the math does not produce.
     */
    fun starDirections(): List<Triple<Int, Double, Double>> =
        listOf(
            Triple(101, 0.1, 0.4),
            Triple(202, 1.9, -0.2),
            Triple(303, 3.4, 1.1),
            Triple(404, 5.6, -0.9),
        )

    fun frameRecord(
        sequence: Long = 0L,
        frame: CameraFrameMetadata = frameMetadata(),
        pose: SkyPoseSample = pose(),
        observer: SkyObserverContext? = observer(),
        exposure: SkyExposureSample? = exposure(),
        predictedStars: List<SkyPredictedStar> = emptyList(),
        luma: SkyLumaReference = lumaReference(),
    ): SkyFrameRecord =
        SkyFrameRecord(
            sequence = sequence,
            capturedAtEpochMillis = 1_767_225_600_000L + sequence,
            frame = frame,
            viewportWidthPx = VIEWPORT_WIDTH_PX,
            viewportHeightPx = VIEWPORT_HEIGHT_PX,
            luma = luma,
            pose = pose,
            observer = observer,
            exposure = exposure,
            predictedStars = predictedStars,
        )

    /** A predicted-star entry whose coordinates are plain fixture values (round-trip tests only). */
    fun predictedStar(
        catalogIndex: Int = 101,
        classification: PredictedStarClassification = PredictedStarClassification.VISIBLE_IN_VIEWPORT,
        imageXPx: Double? = 311.25,
        imageYPx: Double? = 198.5,
        displayXPx: Double? = 525.0,
        displayYPx: Double? = 993.75,
    ): SkyPredictedStar =
        SkyPredictedStar(
            catalogIndex = catalogIndex,
            rightAscensionRad = 0.1,
            declinationRad = 0.4,
            magnitude = 1.25,
            classification = classification,
            imageXPx = imageXPx,
            imageYPx = imageYPx,
            displayXPx = displayXPx,
            displayYPx = displayYPx,
        )
}
