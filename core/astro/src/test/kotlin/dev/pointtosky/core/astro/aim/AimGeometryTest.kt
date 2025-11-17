package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimGeometryTest {
    @Test
    fun `aim error handles azimuth wrap across north`() {
        val current = Horizontal(azDeg = 350.0, altDeg = 10.0)
        val target = Horizontal(azDeg = 5.0, altDeg = 12.0)

        val error = aimError(current, target, AimTolerance(azimuthDeg = 20.0, altitudeDeg = 5.0))

        assertEquals(15.0, error.dAzDeg, 1e-9)
        assertEquals(2.0, error.dAltDeg, 1e-9)
        assertTrue(error.inTolerance)
    }

    @Test
    fun `aim error picks shortest azimuthal arc`() {
        val current = Horizontal(azDeg = 30.0, altDeg = 5.0)
        val target = Horizontal(azDeg = 220.0, altDeg = 1.0)

        val error = aimError(current, target, AimTolerance(azimuthDeg = 200.0, altitudeDeg = 10.0))

        assertEquals(-170.0, error.dAzDeg, 1e-9)
        assertEquals(-4.0, error.dAltDeg, 1e-9)
    }
}
