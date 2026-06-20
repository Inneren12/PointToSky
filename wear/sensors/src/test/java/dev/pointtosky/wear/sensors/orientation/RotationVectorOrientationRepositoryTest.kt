package dev.pointtosky.wear.sensors.orientation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotationVectorOrientationRepositoryTest {
    @Test
    fun `azimuth normalization wraps with zero offset`() {
        val zero = OrientationZero(azimuthOffsetDeg = 10f)
        val normalized = applyZeroOffset(358f, zero)

        assertEquals(8f, normalized, 1e-3f)
    }

    @Test
    fun `forward vector normalization produces unit length`() {
        val normalized = normalizeVector(floatArrayOf(0f, 0f, 2f))

        val length = kotlin.math.sqrt(normalized.fold(0f) { acc, value -> acc + value * value })
        assertTrue(abs(length - 1f) < 1e-3f)
        assertEquals(0f, normalized[0], 1e-3f)
        assertEquals(0f, normalized[1], 1e-3f)
        assertEquals(1f, normalized[2], 1e-3f)
    }

    @Test
    fun `extractForwardVector returns column 1 (device +Y along-forearm axis)`() {
        // Row-major 3x3 matrix: column 1 = indices [1, 4, 7]
        val matrix = floatArrayOf(
            0f, 1f, 0f,
            0f, 0f, -1f,
            -1f, 0f, 0f,
        )
        val forward = extractForwardVector(matrix)

        assertEquals(1f, forward[0], 1e-3f)
        assertEquals(0f, forward[1], 1e-3f)
        assertEquals(0f, forward[2], 1e-3f)
    }

    @Test
    fun `applyAzimuthOffsetToForward rotates North vector eastward by positive offset`() {
        // Pointing north in ENU: [E=0, N=1, U=0]
        val north = floatArrayOf(0f, 1f, 0f)
        val offsetDeg = 90f
        val rotated = applyAzimuthOffsetToForward(north, offsetDeg)

        // 90° clockwise from North = East: [E=1, N=0, U=0]
        assertEquals(sin(Math.toRadians(90.0)).toFloat(), rotated[0], 1e-3f)
        assertEquals(cos(Math.toRadians(90.0)).toFloat(), rotated[1], 1e-3f)
        assertEquals(0f, rotated[2], 1e-3f)
    }

    @Test
    fun `applyAzimuthOffsetToForward preserves Up component`() {
        val diagonal = floatArrayOf(0f, 0f, 1f)
        val rotated = applyAzimuthOffsetToForward(diagonal, 45f)

        assertEquals(0f, rotated[0], 1e-3f)
        assertEquals(0f, rotated[1], 1e-3f)
        assertEquals(1f, rotated[2], 1e-3f)
    }

    @Test
    fun `applyAzimuthOffsetToForward zero offset returns same vector`() {
        val forward = floatArrayOf(0.5f, 0.866f, 0f)
        val result = applyAzimuthOffsetToForward(forward, 0f)

        assertTrue(result === forward) // same reference, no allocation
    }
}
