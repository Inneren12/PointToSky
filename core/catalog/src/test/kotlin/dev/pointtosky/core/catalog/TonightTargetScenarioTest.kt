package dev.pointtosky.core.catalog

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.GeoPoint
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.catalog.star.FakeStarCatalog
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonightTargetScenarioTest {
    @Test
    fun `brightest stars above horizon are prioritised for tonight window`() {
        val location = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        val instant = Instant.parse("2024-07-01T06:00:00Z")
        val lstDeg = lstAt(instant, location.lonDeg).lstDeg

        val catalog = FakeStarCatalog()
        val visible =
            catalog
                .nearby(center = Equatorial(0.0, 0.0), radiusDeg = 180.0, magLimit = 2.0)
                .map { star ->
                    val eq = Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble())
                    val horizontal = raDecToAltAz(eq, lstDeg = lstDeg, latDeg = location.latDeg, applyRefraction = false)
                    Triple(star, horizontal.altDeg, horizontal.azDeg)
                }
                .filter { (_, alt, _) -> alt >= 0.0 }
                .sortedByDescending { (_, alt, _) -> alt }

        assertTrue(visible.isNotEmpty())
        val topThree = visible.take(3)
        val topNames = topThree.map { (star, _, _) -> star.name }

        assertEquals("Vega", topThree[0].first.name)
        assertTrue(topThree[0].second > 60.0)
        assertTrue(topNames.contains("Deneb"))
        assertTrue(topNames.contains("Polaris"))
    }
}
