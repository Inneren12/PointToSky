package dev.pointtosky.wear.sensors.orientation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExponentialLowPassFilterTest {

    @Test
    fun `low pass filter smooths step changes`() {
        val filter = ExponentialLowPassFilter(alpha = 0.15f, dimension = 3)
        val output = FloatArray(3)

        filter.filter(floatArrayOf(0f, 0f, 0f), output)
        assertEquals(0f, output[0])
        assertEquals(0f, output[1])
        assertEquals(0f, output[2])

        filter.filter(floatArrayOf(10f, 10f, 10f), output)
        assertTrue(output.all { it in 1.4f..1.6f })

        filter.filter(floatArrayOf(10f, 10f, 10f), output)
        assertTrue(output.all { it > 1.6f })
        assertTrue(output.all { it < 10f })
    }

    @Test
    fun `reset forces filter to reinitialize`() {
        val filter = ExponentialLowPassFilter(alpha = 0.15f, dimension = 3)
        val output = FloatArray(3)

        filter.filter(floatArrayOf(5f, 5f, 5f), output)
        filter.filter(floatArrayOf(10f, 10f, 10f), output)
        assertTrue(output.all { it > 5f })

        filter.reset()
        filter.filter(floatArrayOf(-3f, -3f, -3f), output)
        assertTrue(output.all { abs(it + 3f) < 1e-3f })
    }
}
