package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure JVM tests for the CAM-1f [createCameraSessionGeometry] factory and the
 * [CameraSessionGeometry]/[CameraSessionGeometryResult] invariants it enforces. No Android type is
 * involved; every rejection path is a categorized result, never a thrown exception.
 */
class CameraSessionGeometryTest {
    private fun frame(
        timestampNanos: Long = 1_000L,
        bufferWidthPx: Int = 1920,
        bufferHeightPx: Int = 1080,
        rotationDegrees: Int = 0,
        cropRectLeftPx: Int? = null,
        cropRectTopPx: Int? = null,
        cropRectRightPx: Int? = null,
        cropRectBottomPx: Int? = null,
    ) = CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        rotationDegrees = rotationDegrees,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
    )

    private fun rotationSample(
        timestampNanos: Long,
        fill: Float = 0f,
    ) = TimedRotationSample(timestampNanos = timestampNanos, rotationMatrix = FloatArray(9) { fill })

    /** Pairs [frame] against a single rotation sample using the production default thresholds. */
    private fun paired(
        frame: CameraFrameMetadata,
        rotationTimestampNanos: Long,
    ): FrameRotationPairingResult.Paired {
        val result =
            pairFrameToNearestRotation(
                frame = frame,
                samples = listOf(rotationSample(rotationTimestampNanos)),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        return assertIs<FrameRotationPairingResult.Paired>(result)
    }

    private val resolvedIntrinsics =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = 60.0,
                verticalFovDeg = 45.0,
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            ),
        )

    private val fallbackIntrinsics =
        CameraIntrinsicsResolution.LegacyFallback(
            legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080),
            reason = "no_valid_focal_length",
        )

    @Test
    fun `valid paired frame with resolved intrinsics builds a ready, calibrated bundle`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 990L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, viewportWidthPx = 1080, viewportHeightPx = 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        assertEquals(CameraGeometryQuality.CALIBRATED, ready.quality)
        assertEquals(f, ready.geometry.frame)
        assertEquals(resolvedIntrinsics, ready.geometry.intrinsics)
    }

    @Test
    fun `valid paired frame with explicit legacy fallback builds a ready, fallback-quality bundle`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 990L)

        val result = createCameraSessionGeometry(f, pairing, fallbackIntrinsics, viewportWidthPx = 1080, viewportHeightPx = 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        assertEquals(CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK, ready.quality)
        assertEquals(fallbackIntrinsics, ready.geometry.intrinsics)
    }

    @Test
    fun `no rotation samples yields a categorized RotationUnavailable, not a thrown exception`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing =
            pairFrameToNearestRotation(
                frame = f,
                samples = emptyList(),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val unavailable = assertIs<CameraSessionGeometryResult.RotationUnavailable>(result)
        assertEquals(RotationUnavailableReason.NO_SAMPLES, unavailable.reason)
    }

    @Test
    fun `a pairing delta outside tolerance is rejected as RotationUnavailable`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        // 100ms: above the 50ms tolerance, well below the 5s clock-mismatch threshold.
        val pairing =
            pairFrameToNearestRotation(
                frame = f,
                samples = listOf(rotationSample(1_000_000_000L - 100_000_000L)),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val unavailable = assertIs<CameraSessionGeometryResult.RotationUnavailable>(result)
        assertEquals(RotationUnavailableReason.OUTSIDE_TOLERANCE, unavailable.reason)
    }

    @Test
    fun `a clock-mismatch-scale delta is rejected as RotationUnavailable with the mismatch reason`() {
        val f = frame(timestampNanos = 10_000_000_000L)
        // 6s: above the 5s clock-mismatch threshold.
        val pairing =
            pairFrameToNearestRotation(
                frame = f,
                samples = listOf(rotationSample(10_000_000_000L - 6_000_000_000L)),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val unavailable = assertIs<CameraSessionGeometryResult.RotationUnavailable>(result)
        assertEquals(RotationUnavailableReason.CLOCK_MISMATCH_SUSPECTED, unavailable.reason)
    }

    @Test
    fun `a zero or negative viewport dimension is reported as InvalidViewport, never a fake 1x1 transform`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 1_000L)

        assertEquals(
            CameraSessionGeometryResult.InvalidViewport(0, 1080),
            createCameraSessionGeometry(f, pairing, resolvedIntrinsics, viewportWidthPx = 0, viewportHeightPx = 1080),
        )
        assertEquals(
            CameraSessionGeometryResult.InvalidViewport(1080, 0),
            createCameraSessionGeometry(f, pairing, resolvedIntrinsics, viewportWidthPx = 1080, viewportHeightPx = 0),
        )
        assertEquals(
            CameraSessionGeometryResult.InvalidViewport(-5, 1080),
            createCameraSessionGeometry(f, pairing, resolvedIntrinsics, viewportWidthPx = -5, viewportHeightPx = 1080),
        )
    }

    @Test
    fun `a pairing result computed for a different frame timestamp is rejected, not silently accepted`() {
        val f = frame(timestampNanos = 1_000L)
        val otherFrame = frame(timestampNanos = 2_000L)
        val pairing = paired(otherFrame, rotationTimestampNanos = 2_000L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.FRAME_ROTATION_TIMESTAMP_MISMATCH, rejected.reason)
    }

    @Test
    fun `transform buffer size, crop, rotation, and viewport all match the source frame and requested viewport`() {
        val f =
            frame(
                timestampNanos = 1_000L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                rotationDegrees = 90,
                cropRectLeftPx = 100,
                cropRectTopPx = 50,
                cropRectRightPx = 1800,
                cropRectBottomPx = 1000,
            )
        val pairing = paired(f, rotationTimestampNanos = 1_000L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, viewportWidthPx = 1080, viewportHeightPx = 1920)
        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        val transform = ready.geometry.cropScaleTransform

        assertEquals(PixelSize(1920.0, 1080.0), transform.sourceBufferSize)
        assertEquals(PixelRect(100.0, 50.0, 1800.0, 1000.0), transform.sourceCrop)
        assertEquals(90, transform.rotationDegrees)
        assertEquals(PixelSize(1080.0, 1920.0), transform.viewportSize)
        assertEquals(PixelSize(1080.0, 1920.0), ready.geometry.viewportSize)
    }

    @Test
    fun `a frame with no crop rect maps to the full-buffer crop`() {
        val f = frame(timestampNanos = 1_000L, bufferWidthPx = 1920, bufferHeightPx = 1080)
        val pairing = paired(f, rotationTimestampNanos = 1_000L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)
        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)

        assertEquals(PixelRect(0.0, 0.0, 1920.0, 1080.0), ready.geometry.cropScaleTransform.sourceCrop)
    }

    @Test
    fun `stored delta equals frame timestamp minus paired rotation timestamp`() {
        val f = frame(timestampNanos = 1_000_500L)
        val pairing = paired(f, rotationTimestampNanos = 1_000_000L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        assertEquals(500L, ready.geometry.frameRotationDeltaNanos)
        assertEquals(pairing.pair.deltaNanos, ready.geometry.frameRotationDeltaNanos)
    }

    @Test
    fun `the paired rotation matrix is copied on the way in - mutating the caller's array afterward does not corrupt the bundle`() {
        val matrix = FloatArray(9) { it.toFloat() }
        val f = frame(timestampNanos = 1_000L)
        val sample = TimedRotationSample(timestampNanos = 1_000L, rotationMatrix = matrix)
        val pairing = FrameRotationPairingResult.Paired(FrameRotationPair(frame = f, rotation = sample, deltaNanos = 0L))

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)
        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)

        matrix[0] = -999f

        assertEquals(0f, ready.geometry.pairedRotation.rotationMatrix[0])
    }

    @Test
    fun `pairedRotation is exactly the pairing result's rotation sample, never independently rebuilt`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 970L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        assertEquals(970L, ready.geometry.pairedRotation.timestampNanos)
        assertEquals(pairing.pair.rotation.timestampNanos, ready.geometry.pairedRotation.timestampNanos)
    }
}
