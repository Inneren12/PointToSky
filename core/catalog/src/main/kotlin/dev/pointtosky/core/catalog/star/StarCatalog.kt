package dev.pointtosky.core.catalog.star

import dev.pointtosky.core.astro.coord.Equatorial

/**
 * Read-only access to a collection of bright stars.
 */
interface StarCatalog {
    /**
     * Returns stars within the specified angular radius from [center].
     *
     * @param radiusDeg Search radius in decimal degrees.
     * @param magLimit Optional limiting magnitude (inclusive). If `null`, no filter is applied.
     */
    fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double? = 6.5): List<Star>
}
