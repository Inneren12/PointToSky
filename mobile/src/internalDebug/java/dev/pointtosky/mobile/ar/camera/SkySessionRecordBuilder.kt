package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyCalibrationRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockAlignment
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPinholeRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPoseSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPredictedStar
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.toSkyIntrinsicsRecord

/**
 * SKY-1 (`internalDebug`-only): assembles the pure log records from the runtime pieces one analyzed
 * frame produces. Deliberately free of Android types so it is unit-testable on the JVM — everything
 * platform-specific (reading the plane, reading `CaptureResult`, writing files) lives in
 * [SkySessionLogWriter], [SkyExposureSampleStore] and [SkySessionCameraPreview] instead.
 *
 * The one clock assumption this file makes, and makes explicitly rather than implicitly: this app
 * already pairs `SensorEvent.timestamp` against `ImageProxy.imageInfo.timestamp` directly (CAM-1d's
 * `pairFrameToNearestRotation`, which has a `ClockMismatchSuspected` outcome for exactly the devices
 * where that fails). [SKY_SESSION_CLOCK_ALIGNMENT] records that the two are being treated as the same
 * time base, with a measured offset of zero, so an offline reader knows it was a decision rather than
 * an oversight.
 */

/**
 * The frame/pose clock relationship the on-device capture path operates under. `poseToFrameOffsetNanos = 0`
 * is written explicitly — not left absent — so the log states "these were treated as one clock"
 * rather than leaving a reader to infer it. A session that ever measures a real offset should record
 * it here instead.
 */
internal val SKY_SESSION_CLOCK_ALIGNMENT: SkyClockAlignment =
    SkyClockAlignment(
        frameClock = SkyClock.CAMERA_SENSOR_NANOS,
        poseClock = SkyClock.SENSOR_EVENT_NANOS,
        poseToFrameOffsetNanos = 0L,
    )

/**
 * Builds the once-per-session header.
 *
 * [pinhole] is derived here rather than inside the pure module because deriving it needs a
 * [CameraSessionGeometry] (`PinholeProjectionModel.forGeometry`), and the geometry is only available
 * once the first frame has been analyzed. It is `null` until then, and — being a derived-on-write
 * field — its absence costs an offline consumer only the convenience of not re-deriving it from the
 * recorded FOV.
 */
internal fun buildSkySessionHeader(
    sessionId: String,
    startedAtEpochMillis: Long,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
    intrinsics: CameraIntrinsicsResolution,
    maxPairDeltaNanos: Long,
    clockMismatchThresholdNanos: Long,
    deviceModel: String?,
    cameraId: String?,
    physicalCameraIds: List<String>,
    calibration: SkyCalibrationRecord?,
    pinhole: SkyPinholeRecord?,
    notes: String?,
): SkySessionLogHeader =
    SkySessionLogHeader(
        sessionId = sessionId,
        startedAtEpochMillis = startedAtEpochMillis,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        intrinsics = intrinsics.toSkyIntrinsicsRecord(pinhole = pinhole),
        clockAlignment = SKY_SESSION_CLOCK_ALIGNMENT,
        maxPairDeltaNanos = maxPairDeltaNanos,
        clockMismatchThresholdNanos = clockMismatchThresholdNanos,
        deviceModel = deviceModel,
        cameraId = cameraId,
        physicalCameraIds = physicalCameraIds,
        calibration = calibration,
        notes = notes,
    )

/**
 * The pixel-space pinhole coefficients for [geometry], or `null` when the intrinsics are not
 * analysis-buffer referenced (in which case `PinholeProjectionModel.forGeometry` would throw — its
 * own contract is that callers gate on `reference` first, which this does).
 */
internal fun skyPinholeRecordOrNull(geometry: CameraSessionGeometry): SkyPinholeRecord? =
    runCatching { PinholeProjectionModel.forGeometry(geometry) }
        .getOrNull()
        ?.let {
            SkyPinholeRecord(
                fxPx = it.focalLengthXPx,
                fyPx = it.focalLengthYPx,
                cxPx = it.principalPointXPx,
                cyPx = it.principalPointYPx,
            )
        }

/**
 * Assembles one frame's log record.
 *
 * [stars] and [prediction] must be the exact batch input and output for *this* [geometry] — they are
 * zipped positionally, which is safe precisely because `projectStars` documents that it preserves
 * input order and never sorts or filters. A batch the projector refused
 * ([StarPredictionBatchResult.IntrinsicsMappingUnavailable]) records an empty star list rather than a
 * fabricated one: the frame's pixels, pose and exposure are still worth keeping even when no
 * prediction could be made for them.
 *
 * The viewport comes from [geometry] rather than from the caller so it can never disagree with the
 * `CropScaleTransform` the recorded display coordinates were produced through.
 */
internal fun buildSkyFrameRecord(
    sequence: Long,
    capturedAtEpochMillis: Long,
    geometry: CameraSessionGeometry,
    luma: SkyLumaReference,
    observer: SkyObserverContext?,
    exposure: SkyExposureSample?,
    stars: List<EquatorialStarDirection>,
    prediction: StarPredictionBatchResult,
): SkyFrameRecord {
    val rotation = geometry.pairedRotation
    return SkyFrameRecord(
        sequence = sequence,
        capturedAtEpochMillis = capturedAtEpochMillis,
        frame = geometry.frame,
        viewportWidthPx = geometry.viewportSize.width.toInt(),
        viewportHeightPx = geometry.viewportSize.height.toInt(),
        luma = luma,
        pose =
            SkyPoseSample(
                timestampNanos = rotation.timestampNanos,
                rotationMatrix = rotation.rotationMatrix.map { it.toDouble() },
                frameToPoseDeltaNanos = geometry.frameRotationDeltaNanos,
            ),
        observer = observer,
        exposure = exposure,
        predictedStars = skyPredictedStars(stars, prediction),
    )
}

private fun skyPredictedStars(
    stars: List<EquatorialStarDirection>,
    prediction: StarPredictionBatchResult,
): List<SkyPredictedStar> {
    val projections = (prediction as? StarPredictionBatchResult.Ready)?.projections ?: return emptyList()
    if (projections.size != stars.size) {
        // projectStars returns exactly one projection per input star; a mismatch means the caller
        // paired a batch with someone else's input, and recording a positionally-zipped result would
        // silently attribute one star's pixels to another's celestial coordinates.
        return emptyList()
    }
    return stars.zip(projections) { star, projection ->
        SkyPredictedStar(
            catalogIndex = projection.catalogIndex,
            rightAscensionRad = star.rightAscensionRad,
            declinationRad = star.declinationRad,
            magnitude = projection.magnitude,
            classification = projection.classification,
            imageXPx = projection.imagePoint?.x,
            imageYPx = projection.imagePoint?.y,
            displayXPx = projection.displayPoint?.x,
            displayYPx = projection.displayPoint?.y,
        )
    }
}
