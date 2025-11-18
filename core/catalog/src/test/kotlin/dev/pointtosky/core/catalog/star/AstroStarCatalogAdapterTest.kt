package dev.pointtosky.core.catalog.star

import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.Asterism
import dev.pointtosky.core.astro.catalog.ArtOverlay
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.ConstellationMeta
import dev.pointtosky.core.astro.catalog.StarId
import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AstroStarCatalogAdapterTest {
    private val astro = StubAstroCatalog()
    private val adapter = AstroStarCatalogAdapter(astro)

    @Test
    fun `filters by magnitude and radius`() {
        val stars = adapter.nearby(center = Equatorial(0.0, 0.0), radiusDeg = 5.0, magLimit = 1.0)

        assertEquals(1, stars.size)
        assertEquals("Bright", stars.first().name)
    }

    @Test
    fun `results sorted by angular separation`() {
        val stars = adapter.nearby(center = Equatorial(0.0, 0.0), radiusDeg = 180.0, magLimit = null)

        assertEquals(listOf("Bright", "Dim"), stars.map { it.name })
        assertTrue(stars[0].raDeg < stars[1].raDeg)
    }
}

private class StubAstroCatalog : AstroCatalog {
    private val constellations = listOf(
        ConstellationMeta(ConstellationId(0), "ORI", "Orion"),
        ConstellationMeta(ConstellationId(1), "LYR", "Lyra"),
    )

    private val stars = listOf(
        StarRecord(StarId(0), 1.0f, 0.0f, 0.5f, constellations[0].id, flags = 0, name = "Bright"),
        StarRecord(StarId(10000), 10.0f, 0.0f, 2.5f, constellations[1].id, flags = 0, name = "Dim"),
    )

    override fun getConstellationMeta(id: ConstellationId): ConstellationMeta = constellations.first()

    override fun allStars(): List<StarRecord> = stars

    override fun starById(raw: Int): StarRecord? = stars.firstOrNull { it.id.raw == raw }

    override fun starsByConstellation(id: ConstellationId): List<StarRecord> = stars.filter { it.constellationId == id }

    override fun asterismsByConstellation(id: ConstellationId): List<Asterism> = emptyList()

    override fun artOverlaysByConstellation(id: ConstellationId): List<ArtOverlay> = emptyList()
}
