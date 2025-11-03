package dev.pointtosky.wear.sensors.orientation

import kotlin.math.abs
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
}
