package dev.pointtosky.core.catalog.constellation

import dev.pointtosky.core.astro.coord.Equatorial

/**
 * Maps positions on the celestial sphere to constellation codes.
 */
public interface ConstellationBoundaries {
    /**
     * @return Three-letter IAU constellation code if found, otherwise `null`.
     */
    public fun findByEq(eq: Equatorial): String?
}
