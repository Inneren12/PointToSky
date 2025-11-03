package dev.pointtosky.core.astro.units

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AngleUnitsTest {

    @Test
    fun `deg to rad conversion`() {
        assertEquals(0.0, degToRad(0.0))
        assertEquals(PI, degToRad(180.0))
        assertEquals(PI / 2, degToRad(90.0))
    }

    @Test
    fun `rad to deg conversion`() {
        assertEquals(0.0, radToDeg(0.0))
        assertEquals(180.0, radToDeg(PI))
        assertEquals(90.0, radToDeg(PI / 2))
    }

    @Test
    fun `wrap 0 360 handles extremes`() {
        assertEquals(0.0, wrapDeg0_360(0.0))
        assertEquals(0.0, wrapDeg0_360(360.0))
        assertEquals(0.0, wrapDeg0_360(-0.0))
        assertEquals(179.0, wrapDeg0_360(179.0))
        assertEquals(179.0, wrapDeg0_360(179.0 + 360.0))
        assertEquals(179.0, wrapDeg0_360(179.0 - 360.0))
        assertEquals(359.0, wrapDeg0_360(-1.0))
        assertEquals(1.0, wrapDeg0_360(721.0))
    }

    @Test
    fun `wrap -180 180 handles extremes`() {
        assertEquals(0.0, wrapDegN180_180(0.0))
        assertEquals(0.0, wrapDegN180_180(360.0))
        assertEquals(0.0, wrapDegN180_180(-0.0))
        assertEquals(179.0, wrapDegN180_180(179.0))
        assertEquals(-180.0, wrapDegN180_180(180.0))
        assertEquals(-179.0, wrapDegN180_180(181.0))
        assertEquals(-179.0, wrapDegN180_180(-179.0))
        assertEquals(-1.0, wrapDegN180_180(359.0))
        assertEquals(1.0, wrapDegN180_180(721.0))
        assertEquals(-179.0, wrapDegN180_180(-181.0))
    }

    @Test
    fun `clamp keeps value in range`() {
        assertEquals(0.5, clamp(0.5, 0.0, 1.0))
        assertEquals(0.0, clamp(-0.5, 0.0, 1.0))
        assertEquals(1.0, clamp(2.0, 0.0, 1.0))
    }

    @Test
    fun `clamp rejects inverted range`() {
        assertFailsWith<IllegalArgumentException> {
            clamp(0.0, 1.0, 0.0)
        }
    }
}
