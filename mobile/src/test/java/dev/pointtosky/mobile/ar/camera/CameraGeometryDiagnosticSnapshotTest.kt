package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.GeometryRejectionReason
import dev.pointtosky.core.astro.projection.camera.IntrinsicsUnavailableReason
import dev.pointtosky.core.astro.projection.camera.PixelSize
import dev.pointtosky.core.astro.projection.camera.RotationUnavailableReason
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-1g pure diagnostic mapper: every [CameraSessionGeometryResult] subtype maps
 * to a stable [CameraGeometryDiagnosticCategory], and a [CameraSessionGeometryResult.Ready] bundle's
 * fields are extracted correctly into [CameraGeometryDiagnosticSnapshot].
 */
class CameraGeometryDiagnosticSnapshotTest {
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

    private fun paired(
        frame: CameraFrameMetadata,
        rotationTimestampNanos: Long,
    ): FrameRotationPairingResult.Paired =
        assertIs<FrameRotationPairingResult.Paired>(
            pairFrameToNearestRotation(
                frame = frame,
                samples = listOf(TimedRotationSample(timestampNanos = rotationTimestampNanos, rotationMatrix = FloatArray(9))),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            ),
        )

    private val resolvedIntrinsics =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = 64.2,
                verticalFovDeg = 42.8,
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

    // --- Category mapping: every CameraSessionGeometryResult subtype -------------------------

    @Test
    fun `MissingFrame maps to MISSING_FRAME`() {
        val result = CameraSessionGeometryResult.MissingFrame(viewportSize = null)
        assertEquals(CameraGeometryDiagnosticCategory.MISSING_FRAME, result.toDiagnosticCategory())
    }

    @Test
    fun `InvalidViewport maps to INVALID_VIEWPORT`() {
        val result = CameraSessionGeometryResult.InvalidViewport(0, 0)
        assertEquals(CameraGeometryDiagnosticCategory.INVALID_VIEWPORT, result.toDiagnosticCategory())
    }

    @Test
    fun `IntrinsicsUnavailable PENDING maps to INTRINSICS_PENDING`() {
        val result = CameraSessionGeometryResult.IntrinsicsUnavailable(IntrinsicsUnavailableReason.PENDING)
        assertEquals(CameraGeometryDiagnosticCategory.INTRINSICS_PENDING, result.toDiagnosticCategory())
    }

    @Test
    fun `RotationUnavailable NO_SAMPLES maps to ROTATION_NO_SAMPLES`() {
        val pairingResult =
            pairFrameToNearestRotation(
                frame = frame(),
                samples = emptyList(),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        val result = CameraSessionGeometryResult.RotationUnavailable(RotationUnavailableReason.NO_SAMPLES, pairingResult)
        assertEquals(CameraGeometryDiagnosticCategory.ROTATION_NO_SAMPLES, result.toDiagnosticCategory())
    }

    @Test
    fun `RotationUnavailable OUTSIDE_TOLERANCE maps to ROTATION_OUTSIDE_TOLERANCE`() {
        val f = frame(timestampNanos = 1_000_000_000L)
        val pairingResult =
            pairFrameToNearestRotation(
                frame = f,
                samples =
                    listOf(
                        TimedRotationSample(timestampNanos = 1_000_000_000L - 200_000_000L, rotationMatrix = FloatArray(9)),
                    ),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        val result =
            CameraSessionGeometryResult.RotationUnavailable(RotationUnavailableReason.OUTSIDE_TOLERANCE, pairingResult)
        assertEquals(CameraGeometryDiagnosticCategory.ROTATION_OUTSIDE_TOLERANCE, result.toDiagnosticCategory())
    }

    @Test
    fun `RotationUnavailable CLOCK_MISMATCH_SUSPECTED maps to ROTATION_CLOCK_MISMATCH`() {
        val f = frame(timestampNanos = 10_000_000_000L)
        val pairingResult =
            pairFrameToNearestRotation(
                frame = f,
                samples = listOf(TimedRotationSample(timestampNanos = 0L, rotationMatrix = FloatArray(9))),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        val result =
            CameraSessionGeometryResult.RotationUnavailable(RotationUnavailableReason.CLOCK_MISMATCH_SUSPECTED, pairingResult)
        assertEquals(CameraGeometryDiagnosticCategory.ROTATION_CLOCK_MISMATCH, result.toDiagnosticCategory())
    }

    @Test
    fun `GeometryRejected PAIRING_FRAME_MISMATCH maps correctly`() {
        val result = CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_FRAME_MISMATCH)
        assertEquals(CameraGeometryDiagnosticCategory.PAIRING_FRAME_MISMATCH, result.toDiagnosticCategory())
    }

    @Test
    fun `GeometryRejected PAIRING_DELTA_MISMATCH maps correctly`() {
        val result = CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_DELTA_MISMATCH)
        assertEquals(CameraGeometryDiagnosticCategory.PAIRING_DELTA_MISMATCH, result.toDiagnosticCategory())
    }

    @Test
    fun `GeometryRejected PAIRING_OUTSIDE_CONFIGURED_TOLERANCE maps correctly`() {
        val result =
            CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE)
        assertEquals(
            CameraGeometryDiagnosticCategory.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE,
            result.toDiagnosticCategory(),
        )
    }

    @Test
    fun `GeometryRejected CROP_SCALE_CONSTRUCTION_FAILED maps correctly`() {
        val result = CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.CROP_SCALE_CONSTRUCTION_FAILED)
        assertEquals(CameraGeometryDiagnosticCategory.CROP_SCALE_CONSTRUCTION_FAILED, result.toDiagnosticCategory())
    }

    @Test
    fun `Ready CALIBRATED maps to READY_CALIBRATED`() {
        val f = frame()
        val result =
            createCameraSessionGeometry(
                frame = f,
                pairingResult = paired(f, f.timestampNanos),
                intrinsicsResolution = resolvedIntrinsics,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
            )
        assertEquals(CameraGeometryDiagnosticCategory.READY_CALIBRATED, result.toDiagnosticCategory())
    }

    @Test
    fun `Ready LEGACY_FALLBACK maps to READY_LEGACY_FALLBACK`() {
        val f = frame()
        val result =
            createCameraSessionGeometry(
                frame = f,
                pairingResult = paired(f, f.timestampNanos),
                intrinsicsResolution = fallbackIntrinsics,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
            )
        assertEquals(CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK, result.toDiagnosticCategory())
    }

    @Test
    fun `Disposed maps to DISPOSED`() {
        assertEquals(
            CameraGeometryDiagnosticCategory.DISPOSED,
            CameraSessionGeometryResult.Disposed.toDiagnosticCategory(),
        )
    }

    // --- Ready snapshot field extraction -------------------------------------------------------

    @Test
    fun `Ready snapshot extracts buffer, crop, rotation, viewport, pair delta, intrinsics, FOV, scale, offset`() {
        val f =
            frame(
                timestampNanos = 5_000L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                rotationDegrees = 90,
                cropRectLeftPx = 100,
                cropRectTopPx = 50,
                cropRectRightPx = 1900,
                cropRectBottomPx = 1000,
            )
        val result =
            createCameraSessionGeometry(
                frame = f,
                pairingResult = paired(f, rotationTimestampNanos = 4_970L),
                intrinsicsResolution = resolvedIntrinsics,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
            )
        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        val snapshot = ready.toDiagnosticSnapshot()

        assertEquals(CameraGeometryDiagnosticCategory.READY_CALIBRATED, snapshot.category)
        assertEquals(CameraGeometryQuality.CALIBRATED, snapshot.quality)
        assertEquals(5_000L, snapshot.frameTimestampNanos)
        assertEquals(1920, snapshot.bufferWidthPx)
        assertEquals(1080, snapshot.bufferHeightPx)
        assertEquals(100, snapshot.cropLeftPx)
        assertEquals(50, snapshot.cropTopPx)
        assertEquals(1900, snapshot.cropRightPx)
        assertEquals(1000, snapshot.cropBottomPx)
        assertEquals(90, snapshot.rotationDegrees)
        assertEquals(1080, snapshot.viewportWidthPx)
        assertEquals(1920, snapshot.viewportHeightPx)
        assertEquals(30L, snapshot.pairDeltaNanos)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, snapshot.intrinsicsSource)
        assertEquals(64.2, snapshot.horizontalFovDeg)
        assertEquals(42.8, snapshot.verticalFovDeg)
        assertTrue(snapshot.uniformScale != null && snapshot.uniformScale!! > 0.0)
        assertTrue(snapshot.displayOffsetX != null)
        assertTrue(snapshot.displayOffsetY != null)
        assertTrue(snapshot.centerProbe != null)
    }

    @Test
    fun `Ready snapshot with fallback intrinsics carries LEGACY_FALLBACK source and quality`() {
        val f = frame()
        val result =
            createCameraSessionGeometry(
                frame = f,
                pairingResult = paired(f, f.timestampNanos),
                intrinsicsResolution = fallbackIntrinsics,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
            )
        val ready = assertIs<CameraSessionGeometryResult.Ready>(result)
        val snapshot = ready.toDiagnosticSnapshot()

        assertEquals(CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK, snapshot.category)
        assertEquals(CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK, snapshot.quality)
        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, snapshot.intrinsicsSource)
    }

    @Test
    fun `MissingFrame with a last-known viewport reports only the viewport`() {
        val result = CameraSessionGeometryResult.MissingFrame(viewportSize = PixelSize(1080.0, 1920.0))
        val snapshot = result.toDiagnosticSnapshot()

        assertEquals(CameraGeometryDiagnosticCategory.MISSING_FRAME, snapshot.category)
        assertEquals(1080, snapshot.viewportWidthPx)
        assertEquals(1920, snapshot.viewportHeightPx)
        assertNull(snapshot.bufferWidthPx)
        assertNull(snapshot.centerProbe)
    }

    @Test
    fun `MissingFrame with no last-known viewport reports viewport as unavailable`() {
        val snapshot = CameraSessionGeometryResult.MissingFrame(viewportSize = null).toDiagnosticSnapshot()
        assertNull(snapshot.viewportWidthPx)
        assertNull(snapshot.viewportHeightPx)
    }

    @Test
    fun `InvalidViewport reports the invalid width and height`() {
        val result = CameraSessionGeometryResult.InvalidViewport(0, -5)
        val snapshot = result.toDiagnosticSnapshot()

        assertEquals(0, snapshot.viewportWidthPx)
        assertEquals(-5, snapshot.viewportHeightPx)
        assertNull(snapshot.bufferWidthPx)
    }

    @Test
    fun `non-Ready categories never carry geometry, quality, intrinsics, or center-probe fields`() {
        val snapshot = CameraSessionGeometryResult.Disposed.toDiagnosticSnapshot()
        assertNull(snapshot.bufferWidthPx)
        assertNull(snapshot.quality)
        assertNull(snapshot.centerProbe)
        assertNull(snapshot.intrinsicsSource)
        assertNull(snapshot.rotationDegrees)
        assertNull(snapshot.pairDeltaNanos)
    }

    // --- Session identifier --------------------------------------------------------------------

    @Test
    fun `nextDebugSessionId returns strictly increasing values, never a device identifier`() {
        val first = nextDebugSessionId()
        val second = nextDebugSessionId()
        assertTrue(second > first)
    }
}
