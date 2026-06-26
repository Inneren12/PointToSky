package dev.pointtosky.mobile.visibility

import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid

/**
 * Resolved Bortle for a location: the [effective] class actually used, the [auto] grid lookup (null
 * unless AUTO + a resolved location + a real grid produced one), and whether a real (non-placeholder)
 * grid was [available].
 */
data class EffectiveBortle(
    val effective: Bortle,
    val auto: Bortle?,
    val available: Boolean,
)

/**
 * Resolve the effective Bortle from the source/grid/manual inputs, mirroring the logic shared by the
 * AR overlay, the sky map, and the object card. Pure — callers pass the [grid] so this stays free of
 * singletons. Placeholder grids are ignored (treated as unavailable). AUTO falls back to [manual]
 * when the location is unresolved or the grid yields no value (e.g. ocean / out-of-range).
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
        realGrid?.bortleAt(latDeg, lonDeg)
    } else {
        null
    }
    return EffectiveBortle(effective = auto ?: manual, auto = auto, available = realGrid != null)
}
