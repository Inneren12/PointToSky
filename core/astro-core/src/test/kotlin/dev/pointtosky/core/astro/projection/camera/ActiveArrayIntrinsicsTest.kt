package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [ActiveArrayIntrinsics] and [activeArrayIntrinsicsFromFocalLength] (CAM-2c §3).
 */
class ActiveArrayIntrinsicsTest {
    @Test
    fun `valid intrinsics construct successfully`() {
        val intrinsics = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 960.0, cyPx = 540.0, widthPx = 1920, heightPx = 1080)
        assertEquals(1000.0, intrinsics.fxPx)
        assertEquals(1920, intrinsics.widthPx)
    }

    @Test
    fun `non-finite or non-positive focal length is rejected`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                ActiveArrayIntrinsics(fxPx = bad, fyPx = 1000.0, cxPx = 0.0, cyPx = 0.0, widthPx = 100, heightPx = 100)
            }
            assertFailsWith<IllegalArgumentException> {
                ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = bad, cxPx = 0.0, cyPx = 0.0, widthPx = 100, heightPx = 100)
            }
        }
    }

    @Test
    fun `non-finite principal point is rejected but zero or negative is accepted`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = bad, cyPx = 0.0, widthPx = 100, heightPx = 100)
            }
        }
        val negative = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = -5.0, cyPx = -5.0, widthPx = 100, heightPx = 100)
        assertEquals(-5.0, negative.cxPx)
    }

    @Test
    fun `zero or negative widthPx or heightPx is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 0.0, cyPx = 0.0, widthPx = 0, heightPx = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 0.0, cyPx = 0.0, widthPx = 100, heightPx = -1)
        }
    }

    // --- activeArrayIntrinsicsFromFocalLength ---

    @Test
    fun `focal-length derivation matches the documented formula when pixel array equals active array`() {
        val result =
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                pixelArrayWidthPx = 4032,
                pixelArrayHeightPx = 3024,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
            )
        val expectedFx = 4.25 / 5.76 * 4032
        val expectedFy = 4.25 / 4.29 * 3024
        assertEquals(expectedFx, result.fxPx, 1e-9)
        assertEquals(expectedFy, result.fyPx, 1e-9)
    }

    @Test
    fun `focal-length derivation uses the pixel array, not the active array, for fx and fy when they differ`() {
        // Pixel array (6.4x4.8mm) is larger than the active array - a real, documented Camera2
        // possibility (optically black/inactive border pixels excluded from the active array).
        val result =
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = 3.6,
                sensorWidthMm = 6.4,
                sensorHeightMm = 4.8,
                pixelArrayWidthPx = 4100,
                pixelArrayHeightPx = 3100,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
            )
        val expectedFx = 3.6 / 6.4 * 4100
        val expectedFy = 3.6 / 4.8 * 3100
        assertEquals(expectedFx, result.fxPx, 1e-9)
        assertEquals(expectedFy, result.fyPx, 1e-9)
        // The old (incorrect) active-array-based formula would have produced a measurably different
        // (smaller) fx/fy - explicitly assert this result is NOT that old value.
        val oldWrongFx = 3.6 / 6.4 * 4032
        val oldWrongFy = 3.6 / 4.8 * 3024
        assertTrue(kotlin.math.abs(oldWrongFx - result.fxPx) > 1.0)
        assertTrue(kotlin.math.abs(oldWrongFy - result.fyPx) > 1.0)
    }

    @Test
    fun `focal-length derivation defaults the principal point to the active-array geometric centre`() {
        val result =
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                pixelArrayWidthPx = 4032,
                pixelArrayHeightPx = 3024,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
            )
        assertEquals(2016.0, result.cxPx)
        assertEquals(1512.0, result.cyPx)
    }

    @Test
    fun `the principal-point default uses the active array's own centre even when the pixel array differs`() {
        val result =
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = 3.6,
                sensorWidthMm = 6.4,
                sensorHeightMm = 4.8,
                pixelArrayWidthPx = 4100,
                pixelArrayHeightPx = 3100,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
            )
        // Still the active array's own local centre (4032/2, 3024/2) - the pixel array's larger size
        // affects fx/fy only, never the coordinate domain or the centre approximation.
        assertEquals(2016.0, result.cxPx)
        assertEquals(1512.0, result.cyPx)
        assertEquals(4032, result.widthPx)
        assertEquals(3024, result.heightPx)
    }

    @Test
    fun `focal-length derivation honours a caller-supplied calibrated principal point`() {
        val result =
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                pixelArrayWidthPx = 4032,
                pixelArrayHeightPx = 3024,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
                principalPointXPx = 2001.3,
                principalPointYPx = 1498.7,
            )
        assertEquals(2001.3, result.cxPx)
        assertEquals(1498.7, result.cyPx)
    }

    @Test
    fun `non-finite or non-positive physical inputs are rejected`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                activeArrayIntrinsicsFromFocalLength(bad, 5.76, 4.29, 4032, 3024, 4032, 3024)
            }
            assertFailsWith<IllegalArgumentException> {
                activeArrayIntrinsicsFromFocalLength(4.25, bad, 4.29, 4032, 3024, 4032, 3024)
            }
            assertFailsWith<IllegalArgumentException> {
                activeArrayIntrinsicsFromFocalLength(4.25, 5.76, bad, 4032, 3024, 4032, 3024)
            }
        }
    }

    @Test
    fun `zero or negative pixel array dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            activeArrayIntrinsicsFromFocalLength(4.25, 5.76, 4.29, 0, 3024, 4032, 3024)
        }
        assertFailsWith<IllegalArgumentException> {
            activeArrayIntrinsicsFromFocalLength(4.25, 5.76, 4.29, 4032, -1, 4032, 3024)
        }
    }

    @Test
    fun `zero or negative active array dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            activeArrayIntrinsicsFromFocalLength(4.25, 5.76, 4.29, 4032, 3024, 0, 3024)
        }
        assertFailsWith<IllegalArgumentException> {
            activeArrayIntrinsicsFromFocalLength(4.25, 5.76, 4.29, 4032, 3024, 4032, -1)
        }
    }
}
