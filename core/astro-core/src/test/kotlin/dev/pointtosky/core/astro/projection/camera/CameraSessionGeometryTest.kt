package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame

/**
 * Pure JVM tests for the CAM-1f [createCameraSessionGeometry] factory and the
 * [CameraSessionGeometry]/[CameraSessionGeometryResult] invariants it enforces. No Android type is
 * involved; every rejection path is a categorized result, never a thrown exception. Includes
 * forged-[FrameRotationPairingResult.Paired] tests: that type and [FrameRotationPair] are public
 * data classes, so this factory must not trust one as proof it actually came from
 * [pairFrameToNearestRotation].
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

    /** Directly constructs a `Paired` result — never through [pairFrameToNearestRotation] — to simulate a forged/stale pair. */
    private fun forgedPaired(
        pairedFrame: CameraFrameMetadata,
        rotation: TimedRotationSample,
        deltaNanos: Long,
    ) = FrameRotationPairingResult.Paired(FrameRotationPair(frame = pairedFrame, rotation = rotation, deltaNanos = deltaNanos))

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

    // --- Happy paths -----------------------------------------------------------------------------

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
    fun `pairedRotation is exactly the pairing result's rotation sample, never independently rebuilt`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 970L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        assertEquals(970L, ready.geometry.pairedRotation.timestampNanos)
        assertEquals(pairing.pair.rotation.timestampNanos, ready.geometry.pairedRotation.timestampNanos)
    }

    // --- Rotation-matrix true immutability --------------------------------------------------------

    @Test
    fun `mutating the original input matrix after bundle creation does not change the bundle`() {
        val matrix = FloatArray(9) { it.toFloat() }
        val f = frame(timestampNanos = 1_000L)
        val sample = TimedRotationSample(timestampNanos = 1_000L, rotationMatrix = matrix)
        val pairing = forgedPaired(f, sample, deltaNanos = 0L)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080))

        matrix[0] = -999f

        assertEquals(0f, ready.geometry.pairedRotation.rotationMatrix[0])
    }

    @Test
    fun `mutating the array returned by one pairedRotation read does not affect a later read`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 970L)
        val ready = assertIs<CameraSessionGeometryResult.Ready>(createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080))

        val firstRead = ready.geometry.pairedRotation
        firstRead.rotationMatrix[0] = -999f
        val secondRead = ready.geometry.pairedRotation

        assertEquals(0f, secondRead.rotationMatrix[0])
    }

    @Test
    fun `two separate reads of pairedRotation never share the same array instance`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 970L)
        val ready = assertIs<CameraSessionGeometryResult.Ready>(createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080))

        val a = ready.geometry.pairedRotation.rotationMatrix
        val b = ready.geometry.pairedRotation.rotationMatrix

        assertNotSame(a, b)
    }

    @Test
    fun `timestamp and matrix values remain correct across every defensive copy`() {
        val matrix = FloatArray(9) { (it + 1).toFloat() }
        val f = frame(timestampNanos = 1_000L)
        val sample = TimedRotationSample(timestampNanos = 970L, rotationMatrix = matrix)
        val pairing = forgedPaired(f, sample, deltaNanos = 30L)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080))

        assertEquals(970L, ready.geometry.pairedRotation.timestampNanos)
        assertContentEquals(matrix, ready.geometry.pairedRotation.rotationMatrix)
        // A second, independent read must still carry the same correct values.
        assertEquals(970L, ready.geometry.pairedRotation.timestampNanos)
        assertContentEquals(matrix, ready.geometry.pairedRotation.rotationMatrix)
    }

    // --- Forged Paired results: exact frame coherence -----------------------------------------

    @Test
    fun `a pairing result computed for a different frame timestamp is rejected as PAIRING_FRAME_MISMATCH`() {
        val f = frame(timestampNanos = 1_000L)
        val otherFrame = frame(timestampNanos = 2_000L)
        val pairing = forgedPaired(otherFrame, rotationSample(2_000L), deltaNanos = 0L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
    }

    @Test
    fun `a same-timestamp but different-dimensions forged frame is rejected as PAIRING_FRAME_MISMATCH`() {
        val f = frame(timestampNanos = 1_000L, bufferWidthPx = 1920, bufferHeightPx = 1080)
        val differentDimensions = frame(timestampNanos = 1_000L, bufferWidthPx = 1280, bufferHeightPx = 720)
        val pairing = forgedPaired(differentDimensions, rotationSample(1_000L), deltaNanos = 0L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
    }

    @Test
    fun `a same-timestamp but different-rotation forged frame is rejected as PAIRING_FRAME_MISMATCH`() {
        val f = frame(timestampNanos = 1_000L, rotationDegrees = 0)
        val differentRotation = frame(timestampNanos = 1_000L, rotationDegrees = 90)
        val pairing = forgedPaired(differentRotation, rotationSample(1_000L), deltaNanos = 0L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
    }

    @Test
    fun `a same-timestamp but different-crop-rect forged frame is rejected as PAIRING_FRAME_MISMATCH`() {
        val f =
            frame(
                timestampNanos = 1_000L,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 1000,
                cropRectBottomPx = 1000,
            )
        val differentCrop =
            frame(
                timestampNanos = 1_000L,
                cropRectLeftPx = 100,
                cropRectTopPx = 100,
                cropRectRightPx = 1000,
                cropRectBottomPx = 1000,
            )
        val pairing = forgedPaired(differentCrop, rotationSample(1_000L), deltaNanos = 0L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
    }

    // --- Forged Paired results: delta arithmetic ------------------------------------------------

    @Test
    fun `a forged Paired result with an incorrect deltaNanos is rejected as PAIRING_DELTA_MISMATCH`() {
        val f = frame(timestampNanos = 1_000L)
        val sample = rotationSample(900L) // real delta is 100
        val pairing = forgedPaired(f, sample, deltaNanos = 999L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_DELTA_MISMATCH, rejected.reason)
    }

    @Test
    fun `a forged Paired result with an inverted-sign deltaNanos is rejected as PAIRING_DELTA_MISMATCH`() {
        val f = frame(timestampNanos = 1_000L)
        val sample = rotationSample(900L) // correct delta is +100, not -100
        val pairing = forgedPaired(f, sample, deltaNanos = -100L)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_DELTA_MISMATCH, rejected.reason)
    }

    // --- Forged Paired results: configured tolerance, not subtype-implied ------------------------

    @Test
    fun `a correct delta outside the default tolerance is rejected as PAIRING_OUTSIDE_CONFIGURED_TOLERANCE`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val delta = 100_000_000L // 100ms: correct arithmetic, but above the 50ms default tolerance
        val sample = rotationSample(1_000_000_000L - delta)
        val pairing = forgedPaired(f, sample, deltaNanos = delta)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE, rejected.reason)
    }

    @Test
    fun `a delta exactly at the configured tolerance boundary is accepted`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val boundary = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS
        val sample = rotationSample(1_000_000_000L - boundary)
        val pairing = forgedPaired(f, sample, deltaNanos = boundary)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        assertIs<CameraSessionGeometryResult.Ready>(result)
    }

    @Test
    fun `a delta one nanosecond over the configured tolerance is rejected`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val overBoundary = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS + 1
        val sample = rotationSample(1_000_000_000L - overBoundary)
        val pairing = forgedPaired(f, sample, deltaNanos = overBoundary)

        val result = createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE, rejected.reason)
    }

    @Test
    fun `a custom maxAllowedPairDeltaNanos is honored instead of the default constant`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val delta = 200_000_000L // 200ms - exceeds the 50ms default, within a 250ms custom tolerance
        val sample = rotationSample(1_000_000_000L - delta)
        val pairing = forgedPaired(f, sample, deltaNanos = delta)

        val result =
            createCameraSessionGeometry(
                f,
                pairing,
                resolvedIntrinsics,
                1080,
                1080,
                maxAllowedPairDeltaNanos = 250_000_000L,
            )

        assertIs<CameraSessionGeometryResult.Ready>(result)
    }

    @Test
    fun `a custom maxAllowedPairDeltaNanos still rejects a delta beyond it`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val delta = 10_000_000L // 10ms - within the 50ms default, but above a 5ms custom tolerance
        val sample = rotationSample(1_000_000_000L - delta)
        val pairing = forgedPaired(f, sample, deltaNanos = delta)

        val result =
            createCameraSessionGeometry(
                f,
                pairing,
                resolvedIntrinsics,
                1080,
                1080,
                maxAllowedPairDeltaNanos = 5_000_000L,
            )

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(result)
        assertEquals(GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE, rejected.reason)
    }

    @Test
    fun `createCameraSessionGeometry rejects a negative maxAllowedPairDeltaNanos as a programmer error`() {
        val f = frame(timestampNanos = 1_000L)
        val pairing = paired(f, rotationTimestampNanos = 1_000L)

        assertFailsWith<IllegalArgumentException> {
            createCameraSessionGeometry(f, pairing, resolvedIntrinsics, 1080, 1080, maxAllowedPairDeltaNanos = -1L)
        }
    }

    // --- CameraSessionGeometry.of stays defense in depth ------------------------------------------

    @Test
    fun `CameraSessionGeometry rejects a cropScaleTransform whose crop does not match the frame`() {
        val f = frame(timestampNanos = 1_000L, bufferWidthPx = 1920, bufferHeightPx = 1080)
        val wrongCropFrame =
            frame(
                timestampNanos = 1_000L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 1000,
                cropRectBottomPx = 1000,
            )
        val mismatchedTransform = createFillCenterCropScaleTransform(wrongCropFrame, 1080, 1080)
        val sample = rotationSample(1_000L)

        assertFailsWith<IllegalArgumentException> {
            CameraSessionGeometry.of(
                frame = f,
                pairedRotation = sample,
                frameRotationDeltaNanos = 0L,
                cropScaleTransform = mismatchedTransform,
                intrinsics = resolvedIntrinsics,
                viewportSize = mismatchedTransform.viewportSize,
            )
        }
    }
}
