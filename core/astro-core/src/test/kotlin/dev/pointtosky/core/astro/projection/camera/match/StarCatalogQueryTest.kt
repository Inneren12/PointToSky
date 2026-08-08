package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
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
 * [requireValidStarCatalogQuery]: because the fake below and the real
 * `dev.pointtosky.core.catalog.binary.PtskCat0StarCatalogQuery` call the *same* validator, a query that
 * the fake accepts is a query the device accepts — which is what makes a test written against the fake
 * evidence about production rather than about the fake.
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

        val wrapped = query.nearby(vega.rightAscensionRad, vega.declinationRad, radiusRad)
        val unwrapped = query.nearby(vega.rightAscensionRad + 2.0 * PI, vega.declinationRad, radiusRad)

        assertEquals(wrapped, unwrapped)
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
        requireValidStarCatalogQuery(rightAscensionRad, declinationRad, radiusRad, magnitudeLimit)
        if (radiusRad == 0.0) return emptyList()
        return stars.filter { star ->
            val magnitude = star.magnitude ?: Double.NEGATIVE_INFINITY
            val withinMagnitude = magnitudeLimit == null || magnitude <= magnitudeLimit
            withinMagnitude && separationRad(rightAscensionRad, declinationRad, star) <= radiusRad
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
