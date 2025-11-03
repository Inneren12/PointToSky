package dev.pointtosky.core.astro.units

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnglesTest {
    @Test
    fun `deg to rad conversion`() {
        assertEquals(0.0, degToRad(0.0))
        assertEquals(PI, degToRad(180.0))
        assertEquals(-PI, degToRad(-180.0))
        assertEquals(2 * PI, degToRad(360.0))
    }

    @Test
    fun `rad to deg conversion`() {
        assertEquals(0.0, radToDeg(0.0))
        assertEquals(180.0, radToDeg(PI))
        assertEquals(-90.0, radToDeg(-PI / 2))
    }

    @Test
    fun `wrap 0-360 handles extremes`() {
        assertEquals(0.0, wrapDeg0_360(0.0))
        assertEquals(0.0, wrapDeg0_360(-0.0))
        assertEquals(0.0, wrapDeg0_360(360.0))
        assertEquals(359.0, wrapDeg0_360(-1.0))
        assertEquals(1.0, wrapDeg0_360(721.0))
        assertEquals(180.0, wrapDeg0_360(-180.0))
    }

    @Test
    fun `wrap -180 180 handles boundary`() {
        assertEquals(0.0, wrapDegN180_180(0.0))
        assertEquals(180.0, wrapDegN180_180(180.0))
        assertEquals(-180.0, wrapDegN180_180(-180.0))
        assertEquals(-179.0, wrapDegN180_180(181.0))
        assertEquals(179.0, wrapDegN180_180(-181.0))
        assertEquals(0.0, wrapDegN180_180(-0.0))
    }

    @Test
    fun `clamp respects bounds`() {
        assertEquals(5.0, clamp(5.0, 0.0, 10.0))
        assertEquals(0.0, clamp(-1.0, 0.0, 10.0))
        assertEquals(10.0, clamp(11.0, 0.0, 10.0))
    }

    @Test
    fun `clamp rejects invalid range`() {
        assertFailsWith<IllegalArgumentException> {
            clamp(0.0, 5.0, 1.0)
        }
    }
}
