package dev.pointtosky.core.catalog

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.SkyCatalog
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.astro.identify.Type
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.astro.identify.ConstellationBoundaries as IdentifyConstellationBoundaries
import dev.pointtosky.core.catalog.constellation.ConstellationBoundaries as CatalogConstellationBoundaries

/**
 * Bridges the data catalog API to the IdentifySolver interfaces.
 */
public class CatalogAdapter(
    private val stars: StarCatalog,
    private val boundaries: CatalogConstellationBoundaries,
) : SkyCatalog, IdentifyConstellationBoundaries {

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<SkyObject> {
        return stars.nearby(center, radiusDeg, magLimit).map { it.toSkyObject() }
    }

    override fun findByEq(eq: Equatorial): String? = boundaries.findByEq(eq)

    private fun Star.toSkyObject(): SkyObject {
        val nameOrDesignation = name ?: bayer ?: flamsteed?.let { flamsteedDesignation ->
            constellation?.let { "$flamsteedDesignation $it" } ?: flamsteedDesignation
        }
        return SkyObject(
            id = "star-$id",
            name = nameOrDesignation,
            eq = Equatorial(raDeg.toDouble(), decDeg.toDouble()),
            mag = mag.toDouble(),
            type = Type.STAR,
        )
    }
}
