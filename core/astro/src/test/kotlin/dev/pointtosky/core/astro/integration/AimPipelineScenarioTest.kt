package dev.pointtosky.core.astro.integration

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.GeoPoint
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyCatalog
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.astro.identify.SkyObjectOrConstellation
import dev.pointtosky.core.astro.identify.Type
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.transform.raDecToAltAz
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimPipelineScenarioTest {
    @Test
    fun `orientation frame converts to expected equatorial and finds catalog target`() {
        val observer = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        val instant = Instant.parse("2024-07-01T06:00:00Z")
        val lstDeg = lstAt(instant, observer.lonDeg).lstDeg

        val frame = FakeOrientationFrame(azimuthDeg = 120.0, altitudeDeg = 45.0)
        val horizontal = frame.toHorizontal()

        val equatorial = altAzToRaDec(horizontal, lstDeg = lstDeg, latDeg = observer.latDeg)

        assertEquals(297.29035415755726, equatorial.raDeg, 1e-6)
        assertEquals(21.019029393340254, equatorial.decDeg, 1e-6)

        val roundTrip = raDecToAltAz(equatorial, lstDeg = lstDeg, latDeg = observer.latDeg, applyRefraction = false)
        assertEquals(horizontal.azDeg, roundTrip.azDeg, 1e-9)
        assertEquals(horizontal.altDeg, roundTrip.altDeg, 1e-9)

        val catalog = FakeCatalog(
            listOf(
                SkyObject(
                    id = "VEGA_TEST",
                    name = "Vega",
                    eq = Equatorial(raDeg = 297.1, decDeg = 21.0),
                    mag = 0.03,
                    type = Type.STAR,
                ),
                SkyObject(
                    id = "FAR",
                    name = "Distant",
                    eq = Equatorial(raDeg = 10.0, decDeg = -20.0),
                    mag = 1.5,
                    type = Type.STAR,
                ),
            ),
        )
        val constellations = FakeConstellationBoundaries

        val solver = IdentifySolver(catalog, constellations)
        val result = solver.findBest(center = equatorial, searchRadiusDeg = 5.0, magLimit = 2.0)

        assertTrue(result is SkyObjectOrConstellation.Object)
        assertEquals("VEGA_TEST", result.obj.id)
        assertTrue(abs(result.obj.eq.raDeg - equatorial.raDeg) < 0.3)
        assertTrue(abs(result.obj.eq.decDeg - equatorial.decDeg) < 0.3)
    }

    private data class FakeOrientationFrame(
        val azimuthDeg: Double,
        val altitudeDeg: Double,
    ) {
        fun toHorizontal(): Horizontal =
            Horizontal(
                azDeg = azimuthDeg,
                altDeg = altitudeDeg,
            )
    }

    private class FakeCatalog(
        private val objects: List<SkyObject>,
    ) : SkyCatalog {
        override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<SkyObject> =
            objects.filter { obj ->
                val sep = angularSeparation(center, obj.eq)
                val withinRadius = sep <= radiusDeg
                val withinMag = magLimit == null || (obj.mag?.let { it <= magLimit } ?: true)
                withinRadius && withinMag
            }

        private fun angularSeparation(a: Equatorial, b: Equatorial): Double {
            val ra1 = Math.toRadians(a.raDeg)
            val ra2 = Math.toRadians(b.raDeg)
            val dec1 = Math.toRadians(a.decDeg)
            val dec2 = Math.toRadians(b.decDeg)
            val cosine = kotlin.math.sin(dec1) * kotlin.math.sin(dec2) +
                kotlin.math.cos(dec1) * kotlin.math.cos(dec2) * kotlin.math.cos(ra1 - ra2)
            return Math.toDegrees(kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)))
        }
    }

    private object FakeConstellationBoundaries : dev.pointtosky.core.astro.identify.ConstellationBoundaries {
        override fun findByEq(eq: Equatorial): String = "LYR"
    }
}
