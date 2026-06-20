package dev.pointtosky.wear.sensors.orientation

import dev.pointtosky.core.astro.aim.forwardVectorToHorizontal
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

    // Semantic mapping tests: device +Y (column 1) must produce the correct az/alt via the full pipeline.
    // Android row-major rotation matrix: [R0 R1 R2 / R3 R4 R5 / R6 R7 R8].
    // Column 1 = [R1, R4, R7] = ENU direction of device +Y.

    @Test
    fun `device +Y aligned with world North yields az=0 alt=0`() {
        // Identity matrix: device +X→East, +Y→North, +Z→Up
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val forward = extractForwardVector(matrix) // [E=0, N=1, U=0]
        val h = forwardVectorToHorizontal(forward[0].toDouble(), forward[1].toDouble(), forward[2].toDouble())
        assertEquals(0.0, h.azDeg, 1e-6)
        assertEquals(0.0, h.altDeg, 1e-6)
    }

    @Test
    fun `device +Y aligned with world East yields az=90 alt=0`() {
        // Device rotated: +Y→East, +X→North, +Z→Down (proper rotation, det=+1)
        val matrix = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f)
        val forward = extractForwardVector(matrix) // [E=1, N=0, U=0]
        val h = forwardVectorToHorizontal(forward[0].toDouble(), forward[1].toDouble(), forward[2].toDouble())
        assertEquals(90.0, h.azDeg, 1e-6)
        assertEquals(0.0, h.altDeg, 1e-6)
    }

    @Test
    fun `device +Y aligned with world Up yields alt=90`() {
        // Device tilted: +Y→Up, +X→East, +Z→South
        val matrix = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val forward = extractForwardVector(matrix) // [E=0, N=0, U=1]
        val h = forwardVectorToHorizontal(forward[0].toDouble(), forward[1].toDouble(), forward[2].toDouble())
        assertEquals(90.0, h.altDeg, 1e-6)
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
