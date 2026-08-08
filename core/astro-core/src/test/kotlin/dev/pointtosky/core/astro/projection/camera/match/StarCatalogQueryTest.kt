package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import java.math.BigDecimal
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The port's own contract, exercised against an in-memory implementation.
 *
 * That this file compiles at all is half the point: `:core:astro-core` is a pure-JVM module whose only
 * dependency is `kotlinx.serialization.json`, so a matcher written here against [StarCatalogQuery] can
 * never accidentally reach the Android-library catalog reader. The other half is
 * [normalizeStarCatalogQuery]: the fake below and the real
 * `dev.pointtosky.core.catalog.binary.PtskCat0StarCatalogQuery` do not merely share a *validator*, they
 * search by the same [NormalizedStarCatalogQuery] — so a query the fake accepts is one the device
 * accepts, and it *means the same thing* to both. The right-ascension wrapping tests below therefore
 * pin the port's own semantics, not one adapter's implementation detail.
 */
class StarCatalogQueryTest {
    private val vega = catalogStar(index = 0, raRad = 4.8757, decRad = 0.6769, magnitude = 0.03)
    private val deneb = catalogStar(index = 1, raRad = 5.4167, decRad = 0.7902, magnitude = 1.25)
    private val altair = catalogStar(index = 2, raRad = 5.1957, decRad = 0.1548, magnitude = 0.76)
    private val faint = catalogStar(index = 3, raRad = 4.8760, decRad = 0.6770, magnitude = 7.4)

    private val query: StarCatalogQuery = InMemoryStarCatalogQuery(listOf(vega, deneb, altair, faint))

    @Test
    fun `returns every star inside the cone and nothing outside it`() {
        // ~25 deg around Vega reaches Deneb (~24 deg away) but not Altair (~34 deg away).
        val found = query.nearby(vega.rightAscensionRad, vega.declinationRad, Math.toRadians(25.0))

        assertEquals(setOf(0, 1, 3), found.map { it.catalogIndex }.toSet())
    }

    @Test
    fun `the magnitude limit is a brighter-or-equal cut`() {
        val radiusRad = Math.toRadians(25.0)

        val bright = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad, magnitudeLimit = 1.25)

        // Deneb sits exactly on the limit and is kept; the mag 7.4 star, a fraction of a degree from the
        // query point, is dropped — proving the cut is on magnitude and not on distance.
        assertEquals(setOf(0, 1), bright.map { it.catalogIndex }.toSet())
    }

    @Test
    fun `a null magnitude limit means no limit`() {
        val radiusRad = Math.toRadians(25.0)

        val limited = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad, magnitudeLimit = null)

        assertTrue(limited.any { it.catalogIndex == 3 }, "the faintest star must survive an absent limit")
    }

    @Test
    fun `a zero radius yields no stars, not an exact-match probe`() {
        val exactlyOnAStar = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad = 0.0)

        assertEquals(emptyList(), exactlyOnAStar)
    }

    @Test
    fun `results carry distinct identities and full catalog data`() {
        val found = query.nearby(vega.rightAscensionRad, vega.declinationRad, Math.toRadians(90.0))

        assertEquals(found.size, found.map { it.catalogIndex }.toSet().size)
        assertEquals(vega, found.single { it.catalogIndex == 0 })
    }

    @Test
    fun `identical queries return identical lists`() {
        val radiusRad = Math.toRadians(30.0)

        val first = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad, magnitudeLimit = 6.0)
        val second = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad, magnitudeLimit = 6.0)

        assertEquals(first, second)
    }

    @Test
    fun `an unwrapped right ascension is accepted and means the same direction`() {
        val radiusRad = Math.toRadians(10.0)

        val canonical = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad)
        assertTrue(canonical.isNotEmpty(), "the fixture must find something to compare")

        assertEquals(canonical, query.nearby(vega.rightAscensionRad + 2.0 * PI, vega.declinationRad, radiusRad))
        assertEquals(canonical, query.nearby(vega.rightAscensionRad - 2.0 * PI, vega.declinationRad, radiusRad))
        assertEquals(canonical, query.nearby(vega.rightAscensionRad + 8.0 * PI, vega.declinationRad, radiusRad))
        assertEquals(canonical, query.nearby(vega.rightAscensionRad - 6.0 * PI, vega.declinationRad, radiusRad))
    }

    @Test
    fun `a huge finite right ascension is wrapped by the port, not just by one adapter`() {
        // The property under test belongs to StarCatalogQuery, so it is exercised here against the
        // in-memory implementation — which searches by the same NormalizedStarCatalogQuery the PTSKCAT0
        // adapter does. Before normalization moved into the port, only that adapter wrapped, and this
        // test would have failed while the device-side one passed.
        val canonicalRaRad = canonicalRaRadIndependently(HUGE_RA_RAD)
        val target = catalogStar(index = 42, raRad = canonicalRaRad, decRad = 0.4, magnitude = 2.0)
        val catalog: StarCatalogQuery = InMemoryStarCatalogQuery(listOf(target))
        val radiusRad = Math.toRadians(1.0)

        val viaHuge = catalog.nearby(HUGE_RA_RAD, target.declinationRad, radiusRad)

        assertEquals(listOf(target), viaHuge, "a huge finite RA must find the star at its canonical direction")
        assertEquals(catalog.nearby(canonicalRaRad, target.declinationRad, radiusRad), viaHuge)
    }

    @Test
    fun `a huge negative finite right ascension wraps forward at the port`() {
        val canonicalRaRad = canonicalRaRadIndependently(-HUGE_RA_RAD)
        assertTrue(canonicalRaRad >= 0.0 && canonicalRaRad < 2.0 * PI, "a canonical RA is never negative")

        val target = catalogStar(index = 7, raRad = canonicalRaRad, decRad = -0.2, magnitude = 3.0)
        val catalog: StarCatalogQuery = InMemoryStarCatalogQuery(listOf(target))
        val radiusRad = Math.toRadians(1.0)

        val viaHuge = catalog.nearby(-HUGE_RA_RAD, target.declinationRad, radiusRad)

        assertEquals(listOf(target), viaHuge, "a huge negative finite RA must wrap forward, not stay negative")
        assertEquals(catalog.nearby(canonicalRaRad, target.declinationRad, radiusRad), viaHuge)
    }

    @Test
    fun `normalization canonicalizes the right ascension and leaves everything else alone`() {
        val normalized =
            normalizeStarCatalogQuery(
                rightAscensionRad = vega.rightAscensionRad - 4.0 * PI,
                declinationRad = vega.declinationRad,
                radiusRad = 0.25,
                magnitudeLimit = 4.5,
            )

        assertEquals(vega.rightAscensionRad, normalized.rightAscensionRad, 1e-12)
        assertTrue(normalized.rightAscensionRad >= 0.0 && normalized.rightAscensionRad < 2.0 * PI)
        assertEquals(vega.declinationRad, normalized.declinationRad)
        assertEquals(0.25, normalized.radiusRad)
        assertEquals(4.5, normalized.magnitudeLimit)
    }

    @Test
    fun `normalization rejects a non-finite right ascension rather than wrapping it into something plausible`() {
        assertFailsWith<IllegalArgumentException> {
            normalizeStarCatalogQuery(Double.NaN, 0.0, 0.1, null)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeStarCatalogQuery(Double.POSITIVE_INFINITY, 0.0, 0.1, null)
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeStarCatalogQuery(Double.NEGATIVE_INFINITY, 0.0, 0.1, null)
        }
    }

    /**
     * The canonical `[0, 2π)` representative of [raRad], derived in exact decimal arithmetic rather than
     * in `Double`.
     *
     * Deliberately **not** the production wrap: [BigDecimal] carries every digit of both operands, so it
     * cannot overflow and cannot round. It reproduces the operation the contract specifies — the
     * remainder against the `Double` value of `2π`, which is the only `2π` this codebase has — without
     * re-running the same floating-point arithmetic, so agreement is a check rather than two copies of
     * one mistake.
     */
    private fun canonicalRaRadIndependently(raRad: Double): Double {
        val twoPi = BigDecimal(2.0 * PI)
        val remainder = BigDecimal(raRad).remainder(twoPi)
        return if (remainder.signum() < 0) remainder.add(twoPi).toDouble() else remainder.toDouble()
    }

    @Test
    fun `a malformed query throws instead of reading as an empty sky`() {
        val ra = vega.rightAscensionRad
        val dec = vega.declinationRad
        val radius = Math.toRadians(5.0)

        assertFailsWith<IllegalArgumentException> { query.nearby(Double.NaN, dec, radius) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, Double.NaN, radius) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, PI, radius) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, dec, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, dec, -1e-9) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, dec, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { query.nearby(ra, dec, radius, magnitudeLimit = Double.NaN) }
    }

    @Test
    fun `an infinite magnitude limit is rejected rather than read as no limit`() {
        val radius = Math.toRadians(5.0)

        // Not pedantry: PTSKCAT0 converts a limit via Math.round(limit * 100.0).toInt(), and
        // Math.round(+inf).toInt() is -1 — so an infinite "no limit" would select zero stars. null is
        // the only spelling of "no limit" the port accepts, and the validator is where that is enforced
        // for every implementation at once.
        assertFailsWith<IllegalArgumentException> {
            query.nearby(vega.rightAscensionRad, vega.declinationRad, radius, magnitudeLimit = Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            query.nearby(vega.rightAscensionRad, vega.declinationRad, radius, magnitudeLimit = Double.NEGATIVE_INFINITY)
        }
    }

    private companion object {
        /**
         * A finite RA far beyond anything an observer means, but well within what the port accepts.
         * Large enough that any degrees conversion applied before wrapping would leave the double range.
         */
        const val HUGE_RA_RAD = Double.MAX_VALUE / 8.0
    }

    @Test
    fun `the port's surface names no Android type`() {
        // The module boundary is the whole reason this interface exists, so it is asserted rather than
        // assumed: no parameter or return type on the port, the scale carrier, or the input DTO may come
        // from an android.* package. (The stronger guarantee — that astro-core has no Android artifact
        // on its classpath at all — is the build file's job, and is why an android.* type could not be
        // named here even if someone tried.)
        val surface =
            listOf(StarCatalogQuery::class.java, AnalysisBufferScale::class.java, StarMatcherInput::class.java)
                .flatMap { type -> type.declaredMethods.toList() }
                .flatMap { method -> method.parameterTypes.toList() + method.returnType }
                .map { it.name }

        val androidTypes = surface.filter { it.startsWith("android.") || it.startsWith("androidx.") }
        assertTrue(androidTypes.isEmpty(), "the matcher input contract must name no Android type; found $androidTypes")
    }
}

/** One catalog star, spelled short enough that a fixture table stays readable. */
private fun catalogStar(
    index: Int,
    raRad: Double,
    decRad: Double,
    magnitude: Double,
): EquatorialStarDirection =
    EquatorialStarDirection.of(
        catalogIndex = index,
        rightAscensionRad = raRad,
        declinationRad = decRad,
        magnitude = magnitude,
    )

/**
 * A [StarCatalogQuery] over a fixed list, used to exercise the port's contract without a catalog file.
 * The great-circle test is the same `acos(sin·sin + cos·cos·cos Δra)` form
 * `dev.pointtosky.core.catalog.binary.PtskCat0Catalog` uses, so "inside the cone" means the same thing
 * here as it does on the device.
 *
 * It searches by the [NormalizedStarCatalogQuery] and never by its own raw parameters — the same
 * discipline `PtskCat0StarCatalogQuery` follows. That is what makes a test written against this fake
 * evidence about the port: if the fake read the unwrapped right ascension, two implementations could
 * accept the same valid query and answer differently, and this file would be documenting only itself.
 */
private class InMemoryStarCatalogQuery(
    private val stars: List<EquatorialStarDirection>,
) : StarCatalogQuery {
    override fun nearby(
        rightAscensionRad: Double,
        declinationRad: Double,
        radiusRad: Double,
        magnitudeLimit: Double?,
    ): List<EquatorialStarDirection> {
        val query = normalizeStarCatalogQuery(rightAscensionRad, declinationRad, radiusRad, magnitudeLimit)
        if (query.radiusRad == 0.0) return emptyList()
        return stars.filter { star ->
            val magnitude = star.magnitude ?: Double.NEGATIVE_INFINITY
            val withinMagnitude = query.magnitudeLimit == null || magnitude <= query.magnitudeLimit
            withinMagnitude && separationRad(query.rightAscensionRad, query.declinationRad, star) <= query.radiusRad
        }
    }

    private fun separationRad(
        rightAscensionRad: Double,
        declinationRad: Double,
        star: EquatorialStarDirection,
    ): Double {
        val cosSeparation =
            sin(declinationRad) * sin(star.declinationRad) +
                cos(declinationRad) * cos(star.declinationRad) * cos(rightAscensionRad - star.rightAscensionRad)
        return acos(cosSeparation.coerceIn(-1.0, 1.0))
    }
}
