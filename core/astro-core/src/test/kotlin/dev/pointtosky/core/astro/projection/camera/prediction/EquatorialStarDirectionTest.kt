package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EquatorialStarDirectionTest {
    private val eps = 1e-9

    // --- of(): the only construction path; every stored RA is canonical [0, 2π) --------------------

    @Test
    fun `negative catalogIndex is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = -1, rightAscensionRad = 0.0, declinationRad = 0.0)
        }
    }

    @Test
    fun `zero catalogIndex is accepted`() {
        EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0)
    }

    @Test
    fun `non-finite right ascension is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Double.NaN, declinationRad = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Double.POSITIVE_INFINITY, declinationRad = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Double.NEGATIVE_INFINITY, declinationRad = 0.0)
        }
    }

    @Test
    fun `non-finite declination is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = Double.NaN)
        }
    }

    @Test
    fun `declination outside -π,2,π,2 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = PI / 2.0 + 0.001)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = -PI / 2.0 - 0.001)
        }
    }

    @Test
    fun `declination exactly at the pole boundaries is accepted`() {
        EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = PI / 2.0)
        EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = -PI / 2.0)
    }

    @Test
    fun `null magnitude is accepted`() {
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = null)
        assertEquals(null, star.magnitude)
    }

    @Test
    fun `non-finite magnitude is rejected when present`() {
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `finite magnitude including negative is accepted`() {
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 0.0, magnitude = -1.46)
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

    @Test
    fun `of handles many-turn angles without precision loss beyond floating tolerance`() {
        val manyTurns = 1000.0 * 2.0 * PI + 0.75
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = manyTurns, declinationRad = 0.0)
        assertApproxEquals(0.75, star.rightAscensionRad, 1e-6)
    }

    @Test
    fun `of applied to equivalent canonical RA values (many turns apart) produces equal instances`() {
        val a = EquatorialStarDirection.of(catalogIndex = 7, rightAscensionRad = 0.6, declinationRad = 0.1, magnitude = 2.0)
        val b = EquatorialStarDirection.of(catalogIndex = 7, rightAscensionRad = 0.6 + 5.0 * 2.0 * PI, declinationRad = 0.1, magnitude = 2.0)
        assertApproxEquals(a.rightAscensionRad, b.rightAscensionRad, 1e-9)
        assertEquals(a.catalogIndex, b.catalogIndex)
        assertEquals(a.declinationRad, b.declinationRad)
        assertEquals(a.magnitude, b.magnitude)
    }

    // --- copy() cannot bypass canonicalization: it is private, like the primary constructor ----------

    @Test
    fun `copy is not part of the public API`() {
        // EquatorialStarDirection is @ConsistentCopyVisibility + `private constructor`, so both the
        // constructor and the generated copy() are private to this file — there is no public path
        // (direct construction or copy()) that can produce a noncanonical instance. This test exists
        // to document that guarantee; the actual enforcement is compile-time (see the class KDoc).
        val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.5, declinationRad = 0.0)
        assertApproxEquals(0.5, star.rightAscensionRad, eps)
    }

    private fun assertApproxEquals(expected: Double, actual: Double, tolerance: Double) {
        kotlin.test.assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }
}
