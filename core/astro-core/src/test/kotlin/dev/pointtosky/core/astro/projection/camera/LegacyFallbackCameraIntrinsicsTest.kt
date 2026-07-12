package dev.pointtosky.core.astro.projection.camera

import kotlin.math.atan
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [legacyFallbackCameraIntrinsics] — the CAM-1b explicit fallback construction
 * used when no real per-device metadata is available. Vertical FOV must always be the AR overlay's
 * legacy 56° default; horizontal FOV must either mirror the legacy aspect-derived value (when the
 * analyzed image size is known) or fall back to the vertical FOV (square-aspect policy).
 */
class LegacyFallbackCameraIntrinsicsTest {
    private val legacyVerticalFovDeg = 56.0
    private val eps = 1e-6

    @Test
    fun `without image dimensions vertical and horizontal FOV both equal the legacy 56 degree default`() {
        val intrinsics = legacyFallbackCameraIntrinsics()
        assertEquals(legacyVerticalFovDeg, intrinsics.verticalFovDeg, eps)
        assertEquals(legacyVerticalFovDeg, intrinsics.horizontalFovDeg, eps)
        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, intrinsics.source)
    }

    @Test
    fun `source is explicitly LEGACY_FALLBACK and never masquerades as calibrated metadata`() {
        val withoutDims = legacyFallbackCameraIntrinsics()
        val withDims = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)

        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, withoutDims.source)
        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, withDims.source)
    }

    @Test
    fun `all physical fields are null since no real device metadata was used`() {
        val intrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)
        assertNull(intrinsics.focalLengthMm)
        assertNull(intrinsics.sensorWidthMm)
        assertNull(intrinsics.sensorHeightMm)
        assertNull(intrinsics.principalPointXPx)
        assertNull(intrinsics.principalPointYPx)
    }

    @Test
    fun `with known image dimensions horizontal FOV matches the legacy aspect-derived formula`() {
        val widthPx = 1920
        val heightPx = 1080
        val intrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = widthPx, imageHeightPx = heightPx)

        val aspect = widthPx.toDouble() / heightPx.toDouble()
        val tanVFov = tan(Math.toRadians(legacyVerticalFovDeg / 2.0))
        val expectedHorizontalFovDeg = Math.toDegrees(2.0 * atan(tanVFov * aspect))

        assertEquals(legacyVerticalFovDeg, intrinsics.verticalFovDeg, eps)
        assertEquals(expectedHorizontalFovDeg, intrinsics.horizontalFovDeg, eps)
        // Landscape image (wider than tall) must widen horizontal FOV beyond the vertical default.
        assertTrue(intrinsics.horizontalFovDeg > intrinsics.verticalFovDeg)
    }

    @Test
    fun `portrait image dimensions narrow horizontal FOV below the vertical default`() {
        val intrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = 1080, imageHeightPx = 1920)
        assertTrue(intrinsics.horizontalFovDeg < intrinsics.verticalFovDeg)
    }

    @Test
    fun `partially-known image dimensions fall back to the square-aspect default`() {
        val onlyWidth = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = null)
        val onlyHeight = legacyFallbackCameraIntrinsics(imageWidthPx = null, imageHeightPx = 1080)
        val invalidZero = legacyFallbackCameraIntrinsics(imageWidthPx = 0, imageHeightPx = 1080)
        val invalidNegative = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = -1)

        listOf(onlyWidth, onlyHeight, invalidZero, invalidNegative).forEach { intrinsics ->
            assertEquals(legacyVerticalFovDeg, intrinsics.horizontalFovDeg, eps)
        }
    }

    @Test
    fun `extreme aspect ratios still produce a horizontal FOV strictly within 0 and 180 degrees`() {
        val extremeCases =
            listOf(
                10000 to 1,
                1 to 10000,
                Int.MAX_VALUE to 1,
            )
        extremeCases.forEach { (widthPx, heightPx) ->
            val intrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = widthPx, imageHeightPx = heightPx)
            assertTrue(
                intrinsics.horizontalFovDeg > 0.0 && intrinsics.horizontalFovDeg < 180.0,
                "width=$widthPx height=$heightPx produced out-of-range fov=${intrinsics.horizontalFovDeg}",
            )
        }
    }
}
