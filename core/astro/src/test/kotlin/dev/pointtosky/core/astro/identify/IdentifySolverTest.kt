package dev.pointtosky.core.astro.identify

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.CatalogAdapter
import dev.pointtosky.core.catalog.constellation.FakeConstellationBoundaries
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentifySolverTest {
    private val starCatalog = StubStarCatalog()
    private val constellationBoundaries = FakeConstellationBoundaries()
    private val adapter = CatalogAdapter(starCatalog, constellationBoundaries)
    private val solver = IdentifySolver(adapter, adapter)

    @Test
    fun `returns closest bright star`() {
        val center = Equatorial(279.3, 38.7)

        val result = solver.findBest(center)

        val objResult = assertIs<SkyObjectOrConstellation.Object>(result)
        assertEquals("Vega", objResult.obj.name)
    }

    @Test
    fun `falls back to constellation when nothing nearby`() {
        val center = Equatorial(30.0, -50.0)

        val result = solver.findBest(center, searchRadiusDeg = 2.0, magLimit = 3.0)

        val constellationResult = assertIs<SkyObjectOrConstellation.Constellation>(result)
        assertEquals("TUC", constellationResult.iauCode)
    }
}

private class StubStarCatalog : StarCatalog {
    private data class Candidate(val star: Star, val separationDeg: Double)

    private val stars: List<Star> = listOf(
        Star(1, 101.287f, -16.716f, -1.46f, "Sirius", "α CMa", "9 CMa", "CMA"),
        Star(2, 95.987f, -52.695f, -0.74f, "Canopus", "α Car", null, "CAR"),
        Star(3, 213.915f, 19.182f, -0.05f, "Arcturus", "α Boo", "16 Boo", "BOO"),
        Star(5, 279.234f, 38.783f, 0.03f, "Vega", "α Lyr", "3 Lyr", "LYR"),
        Star(8, 88.793f, 7.407f, 0.42f, "Betelgeuse", "α Ori", "58 Ori", "ORI"),
    )

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> {
        return stars.asSequence()
            .map { star ->
                val separation = angularSeparationDeg(center, Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()))
                Candidate(star, separation)
            }
            .filter { candidate ->
                candidate.separationDeg <= radiusDeg &&
                    (magLimit == null || candidate.star.mag.toDouble() <= magLimit)
            }
            .sortedBy { it.separationDeg }
            .map { it.star }
            .toList()
    }
}
