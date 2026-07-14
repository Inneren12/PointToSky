package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EquatorialStarDirectionTest {
    private val eps = 1e-9

    // --- Primary constructor: accepts any finite RA, only rejects non-finite/invalid fields --------

    @Test
    fun `primary constructor accepts RA outside 0,2π without normalizing it`() {
        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = -1.5, declinationRad = 0.0)
        assertEquals(-1.5, star.rightAscensionRad)
        val star2 = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 7.0, declinationRad = 0.0)
        assertEquals(7.0, star2.rightAscensionRad)
    }

    @Test
    fun `negative catalogIndex is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = -1, rightAscensionRad = 0.0, declinationRad = 0.0)
        }
    }

    @Test
    fun `zero catalogIndex is accepted`() {
        EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0)
    }

    @Test
    fun `non-finite right ascension is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Double.NaN, declinationRad = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Double.POSITIVE_INFINITY, declinationRad = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Double.NEGATIVE_INFINITY, declinationRad = 0.0)
        }
    }

    @Test
    fun `non-finite declination is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = Double.NaN)
        }
    }

    @Test
    fun `declination outside -π,2,π,2 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = PI / 2.0 + 0.001)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = -PI / 2.0 - 0.001)
        }
    }

    @Test
    fun `declination exactly at the pole boundaries is accepted`() {
        EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = PI / 2.0)
        EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = -PI / 2.0)
    }

    @Test
    fun `null magnitude is accepted`() {
        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = null)
        assertEquals(null, star.magnitude)
    }

    @Test
    fun `non-finite magnitude is rejected when present`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `finite magnitude including negative is accepted`() {
        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = -1.46)
        assertEquals(-1.46, star.magnitude)
    }

    // --- of(): normalizes RA into [0, 2π) ------------------------------------------------------------

    @Test
    fun `of wraps a small negative RA forward into 0,2π`() {
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = -0.1, declinationRad = 0.0)
        assertApproxEquals(2.0 * PI - 0.1, star.rightAscensionRad, eps)
    }

    @Test
    fun `of wraps RA slightly beyond 2π back near zero`() {
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 2.0 * PI + 0.2, declinationRad = 0.0)
        assertApproxEquals(0.2, star.rightAscensionRad, eps)
    }

    @Test
    fun `of wraps exactly 2π to zero`() {
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 2.0 * PI, declinationRad = 0.0)
        assertApproxEquals(0.0, star.rightAscensionRad, eps)
    }

    @Test
    fun `of leaves an already-canonical RA unchanged`() {
        val star = EquatorialStarDirection.of(catalogIndex = 5, rightAscensionRad = 1.2345, declinationRad = 0.3)
        assertApproxEquals(1.2345, star.rightAscensionRad, eps)
    }

    @Test
    fun `of rejects non-finite RA before wrapping`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Double.NaN, declinationRad = 0.0)
        }
    }

    @Test
    fun `of preserves catalogIndex and magnitude`() {
        val star = EquatorialStarDirection.of(catalogIndex = 42, rightAscensionRad = 1.0, declinationRad = 0.5, magnitude = 3.2)
        assertEquals(42, star.catalogIndex)
        assertEquals(3.2, star.magnitude)
    }

    private fun assertApproxEquals(expected: Double, actual: Double, tolerance: Double) {
        kotlin.test.assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }
}
