package dev.pointtosky.core.catalog

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Проверяем мост: StarCatalog -> SkyCatalog через CatalogAdapter.
 */
class CatalogAdapterTest {

    @Test
    fun `maps star fields to sky object`() {
        // Подделка каталога: один объект "Sirius"
        val star = Star(
            id = 42,
            raDeg = 101.2875f,
            decDeg = -16.7161f,
            mag = -1.46f,
            name = "Sirius",
            bayer = null,
            flamsteed = null,
            constellation = "CMA",
        )
        val stars = object : StarCatalog {
            override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> = listOf(star)
        }
        val boundaries = object : ConstellationBoundaries {
            override fun findByEq(eq: Equatorial): String? = "CMA"
        }

        val adapter = CatalogAdapter(stars, boundaries)
        val result: List<SkyObject> = adapter.nearby(
            Equatorial(101.3, -16.7),
            radiusDeg = 2.0,
            magLimit = 2.0,
        )

        assertEquals(1, result.size)
        val obj = result.first()
        assertEquals("Sirius", obj.name)
        assertEquals("star-42", obj.id)
        assertEquals(-1.46, obj.mag!!, 1e-4)
    }
}
