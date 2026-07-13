package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure JVM tests for [TimedRotationSample] (CAM-1d): validation only, no Android dependency.
 */
class TimedRotationSampleTest {
    @Test
    fun `valid sample constructs successfully`() {
        val sample = TimedRotationSample(timestampNanos = 123L, rotationMatrix = FloatArray(9) { it.toFloat() })
        assertEquals(123L, sample.timestampNanos)
        assertEquals(9, sample.rotationMatrix.size)
    }

    @Test
    fun `zero timestamp is accepted`() {
        TimedRotationSample(timestampNanos = 0L, rotationMatrix = FloatArray(9))
    }

    @Test
    fun `negative timestamp is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TimedRotationSample(timestampNanos = -1L, rotationMatrix = FloatArray(9))
        }
    }

    @Test
    fun `matrix with fewer than 9 elements is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TimedRotationSample(timestampNanos = 0L, rotationMatrix = FloatArray(8))
        }
    }

    @Test
    fun `matrix with more than 9 elements is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TimedRotationSample(timestampNanos = 0L, rotationMatrix = FloatArray(10))
        }
    }

    @Test
    fun `empty matrix is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TimedRotationSample(timestampNanos = 0L, rotationMatrix = FloatArray(0))
        }
    }
}
