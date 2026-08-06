package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.FrameRotationPair
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext

/**
 * Shared synthetic fixtures for the SKY-1 `:mobile` capture tests. Nothing here touches a camera, a
 * sensor, or an Android framework class — the whole capture path below the CameraX bind is plain
 * Kotlin, and these tests exercise it that way.
 */
internal object SkySessionCaptureFixtures {
    const val BUFFER_WIDTH_PX = 64
    const val BUFFER_HEIGHT_PX = 48
    const val VIEWPORT_WIDTH_PX = 1080
    const val VIEWPORT_HEIGHT_PX = 2400
    const val FRAME_TIMESTAMP_NANOS = 1_000_000_000L

    fun intrinsics(): CameraIntrinsicsResolution =
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
                reference =
                    CameraIntrinsicsReference.AnalysisBuffer(
                        widthPx = BUFFER_WIDTH_PX,
                        heightPx = BUFFER_HEIGHT_PX,
                    ),
            ),
        )

    fun frameMetadata(timestampNanos: Long = FRAME_TIMESTAMP_NANOS): CameraFrameMetadata =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            rotationDegrees = 90,
        )

    /** A 30° rotation about the world Z axis, so the recorded pose is not a degenerate identity. */
    fun rotationMatrix(): FloatArray =
        floatArrayOf(
            0.8660254f,
            -0.5f,
            0f,
            0.5f,
            0.8660254f,
            0f,
            0f,
            0f,
            1f,
        )

    fun geometry(
        timestampNanos: Long = FRAME_TIMESTAMP_NANOS,
        poseTimestampNanos: Long = FRAME_TIMESTAMP_NANOS,
    ): CameraSessionGeometry {
        val frame = frameMetadata(timestampNanos)
        val rotation = TimedRotationSample(timestampNanos = poseTimestampNanos, rotationMatrix = rotationMatrix())
        val result =
            createCameraSessionGeometry(
                frame = frame,
                pairingResult =
                    FrameRotationPairingResult.Paired(
                        FrameRotationPair(
                            frame = frame,
                            rotation = rotation,
                            deltaNanos =
                                timestampNanos - poseTimestampNanos,
                        ),
                    ),
                intrinsicsResolution = intrinsics(),
                viewportWidthPx = VIEWPORT_WIDTH_PX,
                viewportHeightPx = VIEWPORT_HEIGHT_PX,
            )
        return (result as CameraSessionGeometryResult.Ready).geometry
    }

    /** A gradient luma plane, so a byte-for-byte disk comparison can actually detect a wrong offset. */
    fun lumaData(
        rowStridePx: Int = BUFFER_WIDTH_PX,
        heightPx: Int = BUFFER_HEIGHT_PX,
        seed: Int = 0,
    ): ByteArray = ByteArray(rowStridePx * heightPx) { index -> ((index + seed) % 251).toByte() }

    fun analyzedFrame(
        timestampNanos: Long = FRAME_TIMESTAMP_NANOS,
        rowStridePx: Int = BUFFER_WIDTH_PX,
        seed: Int = 0,
        exposure: SkyExposureSample? = exposureSample(timestampNanos),
    ): SkyAnalyzedFrame =
        SkyAnalyzedFrame(
            metadata = frameMetadata(timestampNanos),
            lumaData = lumaData(rowStridePx = rowStridePx, seed = seed),
            lumaWidthPx = BUFFER_WIDTH_PX,
            lumaHeightPx = BUFFER_HEIGHT_PX,
            lumaRowStridePx = rowStridePx,
            exposure = exposure,
        )

    fun exposureSample(sensorTimestampNanos: Long = FRAME_TIMESTAMP_NANOS): SkyExposureSample =
        SkyExposureSample(
            exposureTimeNanos = 500_000_000L,
            sensitivityIso = 1600,
            frameDurationNanos = 500_000_000L,
            aeMode = "OFF",
            awbMode = "AUTO",
            sensorTimestampNanos = sensorTimestampNanos,
        )

    fun observer(): SkyObserverContext =
        SkyObserverContext(
            latitudeDeg = 50.4501,
            longitudeDeg = 30.5234,
            utcEpochMillis = 1_767_225_600_000L,
            horizontalAccuracyM = 6.0,
            magneticDeclinationDeg = 11.5,
        )

    fun starDirections(): List<EquatorialStarDirection> =
        listOf(
            EquatorialStarDirection.of(
                catalogIndex = 101,
                rightAscensionRad = 0.1,
                declinationRad = 0.4,
                magnitude = 1.25,
            ),
            EquatorialStarDirection.of(
                catalogIndex = 202,
                rightAscensionRad = 1.9,
                declinationRad = -0.2,
                magnitude = 2.5,
            ),
            EquatorialStarDirection.of(
                catalogIndex = 303,
                rightAscensionRad = 3.4,
                declinationRad = 1.1,
                magnitude = 3.75,
            ),
        )
}
