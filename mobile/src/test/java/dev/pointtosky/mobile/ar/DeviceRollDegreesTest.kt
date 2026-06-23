package dev.pointtosky.mobile.ar

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceRollDegreesTest {
    // Only indices [6] and [7] (world-up projected onto the screen axes) are read.
    @Test
    fun `upright pose maps to zero roll`() {
        assertEquals(
            0f,
            deviceRollDegrees(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
            1e-3f,
        )
    }

    @Test
    fun `rolled 90 degrees clockwise maps to minus 90`() {
        assertEquals(
            -90f,
            deviceRollDegrees(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f)),
            1e-3f,
        )
    }

    @Test
    fun `rolled 90 degrees counter-clockwise maps to plus 90`() {
        assertEquals(
            90f,
            deviceRollDegrees(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)),
            1e-3f,
        )
    }

    @Test
    fun `near zenith returns fallback`() {
        assertEquals(
            0f,
            deviceRollDegrees(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f), fallback = 0f),
            1e-3f,
        )
    }
}
