package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the CAM-1b FOV formula: `fov = 2 * atan(sensorDimensionMm / (2 *
 * focalLengthMm))`. No Android dependency; exercises [fovDegFromFocalLength] and its
 * horizontal/vertical wrappers directly.
 */
class CameraFovTest {
    private val eps = 1e-6

    @Test
    fun `known focal-sensor combination matches a hand-computed FOV`() {
        // sensor = focal * 2 -> 2*atan(1) = 90 degrees exactly.
        assertEquals(90.0, fovDegFromFocalLength(sensorDimensionMm = 36.0, focalLengthMm = 18.0), eps)

        // sensor == focal -> 2*atan(0.5) ~= 53.13010235 degrees.
        assertEquals(53.13010235415598, fovDegFromFocalLength(sensorDimensionMm = 24.0, focalLengthMm = 24.0), eps)

        // A typical smartphone-ish combination: 4.25mm focal length, 5.76mm sensor width.
        val expectedDeg = Math.toDegrees(2.0 * kotlin.math.atan(5.76 / (2.0 * 4.25)))
        assertEquals(expectedDeg, fovDegFromFocalLength(sensorDimensionMm = 5.76, focalLengthMm = 4.25), eps)
    }

    @Test
    fun `horizontalFovDeg and verticalFovDeg are independent specializations of the same formula`() {
        val focalLengthMm = 4.0
        val sensorWidthMm = 6.4
        val sensorHeightMm = 4.8

        assertEquals(fovDegFromFocalLength(sensorWidthMm, focalLengthMm), horizontalFovDeg(sensorWidthMm, focalLengthMm), eps)
        assertEquals(fovDegFromFocalLength(sensorHeightMm, focalLengthMm), verticalFovDeg(sensorHeightMm, focalLengthMm), eps)

        // A wider sensor dimension at the same focal length must yield a wider FOV.
        assertTrue(horizontalFovDeg(sensorWidthMm, focalLengthMm) > verticalFovDeg(sensorHeightMm, focalLengthMm))
    }

    @Test
    fun `larger sensor dimension produces a wider FOV at fixed focal length`() {
        val focalLengthMm = 5.0
        val small = fovDegFromFocalLength(sensorDimensionMm = 4.0, focalLengthMm = focalLengthMm)
        val medium = fovDegFromFocalLength(sensorDimensionMm = 6.0, focalLengthMm = focalLengthMm)
        val large = fovDegFromFocalLength(sensorDimensionMm = 10.0, focalLengthMm = focalLengthMm)

        assertTrue(small < medium, "small=$small medium=$medium")
        assertTrue(medium < large, "medium=$medium large=$large")
    }

    @Test
    fun `longer focal length produces a narrower FOV at fixed sensor dimension`() {
        val sensorDimensionMm = 6.0
        val shortFocal = fovDegFromFocalLength(sensorDimensionMm = sensorDimensionMm, focalLengthMm = 2.0)
        val mediumFocal = fovDegFromFocalLength(sensorDimensionMm = sensorDimensionMm, focalLengthMm = 4.0)
        val longFocal = fovDegFromFocalLength(sensorDimensionMm = sensorDimensionMm, focalLengthMm = 10.0)

        assertTrue(shortFocal > mediumFocal, "short=$shortFocal medium=$mediumFocal")
        assertTrue(mediumFocal > longFocal, "medium=$mediumFocal long=$longFocal")
    }

    @Test
    fun `zero or negative sensor dimension is rejected`() {
        assertFailsWith<IllegalArgumentException> { fovDegFromFocalLength(sensorDimensionMm = 0.0, focalLengthMm = 4.0) }
        assertFailsWith<IllegalArgumentException> { fovDegFromFocalLength(sensorDimensionMm = -1.0, focalLengthMm = 4.0) }
    }

    @Test
    fun `zero or negative focal length is rejected`() {
        assertFailsWith<IllegalArgumentException> { fovDegFromFocalLength(sensorDimensionMm = 6.0, focalLengthMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { fovDegFromFocalLength(sensorDimensionMm = 6.0, focalLengthMm = -3.0) }
    }

    @Test
    fun `NaN and infinite inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            fovDegFromFocalLength(sensorDimensionMm = Double.NaN, focalLengthMm = 4.0)
        }
        assertFailsWith<IllegalArgumentException> {
            fovDegFromFocalLength(sensorDimensionMm = 6.0, focalLengthMm = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            fovDegFromFocalLength(sensorDimensionMm = Double.POSITIVE_INFINITY, focalLengthMm = 4.0)
        }
        assertFailsWith<IllegalArgumentException> {
            fovDegFromFocalLength(sensorDimensionMm = 6.0, focalLengthMm = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `output stays within 0 and 180 degrees across a wide range of valid inputs`() {
        val sensorDimensionsMm = listOf(0.001, 0.5, 1.0, 4.8, 6.4, 10.0, 24.0, 36.0, 100.0, 1e6)
        val focalLengthsMm = listOf(0.001, 0.5, 1.0, 2.0, 4.25, 10.0, 50.0, 200.0, 1e6)

        sensorDimensionsMm.forEach { sensor ->
            focalLengthsMm.forEach { focal ->
                val fov = fovDegFromFocalLength(sensor, focal)
                assertTrue(fov > 0.0 && fov < 180.0, "sensor=$sensor focal=$focal produced out-of-range fov=$fov")
            }
        }
    }
}
