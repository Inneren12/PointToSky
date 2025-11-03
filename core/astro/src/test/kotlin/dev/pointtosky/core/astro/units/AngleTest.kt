package dev.pointtosky.core.astro.units

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AngleTest {
    @Test
    fun `degToRad converts degrees to radians`() {
        assertEquals(PI, 180.0.degToRad(), absoluteTolerance)
        assertEquals(PI / 2.0, 90.0.degToRad(), absoluteTolerance)
    }

    @Test
    fun `radToDeg converts radians to degrees`() {
        assertEquals(180.0, PI.radToDeg(), absoluteTolerance)
        assertEquals(90.0, (PI / 2.0).radToDeg(), absoluteTolerance)
    }

    @Test
    fun `wrapDegrees normalizes to 0-360`() {
        assertEquals(10.0, 10.0.wrapDegrees(), absoluteTolerance)
        assertEquals(350.0, (-10.0).wrapDegrees(), absoluteTolerance)
        assertEquals(45.0, 765.0.wrapDegrees(), absoluteTolerance)
    }

    @Test
    fun `wrapDegreesSigned normalizes to minus180 to plus180`() {
        assertEquals(10.0, 10.0.wrapDegreesSigned(), absoluteTolerance)
        assertEquals(-10.0, (-10.0).wrapDegreesSigned(), absoluteTolerance)
        assertEquals(-90.0, 270.0.wrapDegreesSigned(), absoluteTolerance)
        assertEquals(90.0, (-270.0).wrapDegreesSigned(), absoluteTolerance)
        assertEquals(-180.0, (-180.0).wrapDegreesSigned(), absoluteTolerance)
    }

    @Test
    fun `wrapRadians normalizes to 0 to 2pi`() {
        assertEquals(PI / 2.0, (PI / 2.0).wrapRadians(), absoluteTolerance)
        assertEquals(PI / 2.0, (-3.0 * PI / 2.0).wrapRadians(), absoluteTolerance)
        assertEquals(PI / 3.0, (13.0 * PI / 3.0).wrapRadians(), absoluteTolerance)
    }

    @Test
    fun `wrapRadiansSigned normalizes to minusPi to plusPi`() {
        assertEquals(PI / 2.0, (PI / 2.0).wrapRadiansSigned(), absoluteTolerance)
        assertEquals(-PI / 2.0, (-PI / 2.0).wrapRadiansSigned(), absoluteTolerance)
        assertEquals(-PI / 3.0, (5.0 * PI / 3.0).wrapRadiansSigned(), absoluteTolerance)
        assertEquals(PI / 3.0, (-5.0 * PI / 3.0).wrapRadiansSigned(), absoluteTolerance)
        assertEquals(-PI, (-PI).wrapRadiansSigned(), absoluteTolerance)
    }

    @Test
    fun `clamp restricts values to inclusive bounds`() {
        assertEquals(5.0, 5.0.clamp(0.0, 10.0), absoluteTolerance)
        assertEquals(0.0, (-5.0).clamp(0.0, 10.0), absoluteTolerance)
        assertEquals(10.0, 15.0.clamp(0.0, 10.0), absoluteTolerance)
    }

    @Test
    fun `clamp rejects invalid bounds`() {
        assertFailsWith<IllegalArgumentException> {
            0.0.clamp(10.0, 0.0)
        }
    }

    @Test
    fun `angular separation in degrees`() {
        assertEquals(0.0, 10.0.angularSeparationDegrees(10.0), absoluteTolerance)
        assertEquals(30.0, 10.0.angularSeparationDegrees(40.0), absoluteTolerance)
        assertEquals(20.0, 10.0.angularSeparationDegrees(350.0), absoluteTolerance)
    }

    @Test
    fun `angular separation in radians`() {
        assertEquals(0.0, (PI / 6.0).angularSeparationRadians(PI / 6.0), absoluteTolerance)
        assertEquals(PI / 3.0, 0.0.angularSeparationRadians(PI / 3.0), absoluteTolerance)
        assertEquals(PI / 4.0, (PI / 8.0).angularSeparationRadians(-PI / 8.0), absoluteTolerance)
    }

    private companion object {
        private const val absoluteTolerance = 1e-12
    }
}
