package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure JVM tests for the CAM-1f [CameraIntrinsicsResolution] sealed hierarchy: source-consistency
 * invariants and the derived [CameraGeometryQuality].
 */
class CameraIntrinsicsResolutionTest {
    private val calibrated =
        CameraIntrinsics(
            horizontalFovDeg = 60.0,
            verticalFovDeg = 45.0,
            focalLengthMm = 4.25,
            sensorWidthMm = 5.76,
            sensorHeightMm = 4.29,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    private val fallback = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)

    @Test
    fun `Resolved accepts real per-device intrinsics`() {
        val resolution = CameraIntrinsicsResolution.Resolved(calibrated)
        assertEquals(calibrated, resolution.intrinsics)
    }

    @Test
    fun `Resolved rejects intrinsics carrying the legacy fallback source`() {
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsResolution.Resolved(fallback) }
    }

    @Test
    fun `LegacyFallback accepts legacy-sourced intrinsics with a non-blank reason`() {
        val resolution = CameraIntrinsicsResolution.LegacyFallback(fallback, reason = "no_valid_focal_length")
        assertEquals(fallback, resolution.intrinsics)
        assertEquals("no_valid_focal_length", resolution.reason)
    }

    @Test
    fun `LegacyFallback rejects intrinsics that do not carry the legacy fallback source`() {
        assertFailsWith<IllegalArgumentException> {
            CameraIntrinsicsResolution.LegacyFallback(calibrated, reason = "no_valid_focal_length")
        }
    }

    @Test
    fun `LegacyFallback rejects a blank reason`() {
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsResolution.LegacyFallback(fallback, reason = "  ") }
    }

    @Test
    fun `quality maps Resolved to CALIBRATED and LegacyFallback to LEGACY_INTRINSICS_FALLBACK`() {
        assertEquals(CameraGeometryQuality.CALIBRATED, CameraIntrinsicsResolution.Resolved(calibrated).quality)
        assertEquals(
            CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK,
            CameraIntrinsicsResolution.LegacyFallback(fallback, reason = "no_valid_focal_length").quality,
        )
    }
}
