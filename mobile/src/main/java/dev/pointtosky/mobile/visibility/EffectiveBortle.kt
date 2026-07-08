package dev.pointtosky.mobile.visibility

import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import dev.pointtosky.core.astro.visibility.SkyBrightness

/**
 * Resolved sky brightness for a location: the [effective] value actually used, the [auto] grid
 * lookup (null unless AUTO + a resolved location + a real grid produced one), and whether a real
 * grid actually produced a value at this location ([available]) — i.e. the point is within
 * coverage, not merely that the asset is non-placeholder.
 */
data class EffectiveBortle(
    val effective: SkyBrightness,
    val auto: SkyBrightness?,
    val available: Boolean,
)

/**
 * Resolve the effective sky brightness from the source/grid/manual inputs, mirroring the logic
 * shared by the AR overlay, the sky map, and the object card. Pure — callers pass the [grid] so
 * this stays free of singletons. Placeholder grids are ignored (treated as unavailable). AUTO falls
 * back to [manual]'s representative SQM when the location is unresolved or the grid yields no value
 * (e.g. ocean / out-of-range).
 */
fun resolveEffectiveBortle(
    source: BortleSource,
    manual: Bortle,
    locationResolved: Boolean,
    latDeg: Double,
    lonDeg: Double,
    grid: LightPollutionGrid?,
): EffectiveBortle {
    val realGrid = grid?.takeUnless { it.isPlaceholder }
    val auto = if (source == BortleSource.AUTO && locationResolved) {
        realGrid?.sqmAt(latDeg, lonDeg)
    } else {
        null
    }
    val manualSky = SkyBrightness(manual.representativeSqm)
    val covered = locationResolved && realGrid != null && realGrid.sqmAt(latDeg, lonDeg) != null
    return EffectiveBortle(effective = auto ?: manualSky, auto = auto, available = covered)
}
