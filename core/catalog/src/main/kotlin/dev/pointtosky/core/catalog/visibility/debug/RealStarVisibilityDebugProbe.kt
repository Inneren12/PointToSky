package dev.pointtosky.core.catalog.visibility.debug

import dev.pointtosky.core.catalog.binary.RealStarCatalogLoadException
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput

/** Computed counts from a single [RealStarVisibilityDebugProbe.snapshot] call. */
data class RealStarVisibilityDebugInfo(
    val catalogCount: Int,
    val catalogMagLimit: Double,
    val limitingMagnitude: Double,
    val visibleCount: Int,
)

/** Result of [RealStarVisibilityDebugProbe.snapshot]. */
sealed interface RealStarVisibilityDebugSnapshot {
    data class Success(val info: RealStarVisibilityDebugInfo) : RealStarVisibilityDebugSnapshot
    data class Failure(val message: String) : RealStarVisibilityDebugSnapshot
}

/**
 * VF-1e: a tiny, non-rendering probe over [RealStarVisibilityService] for
 * runtime/debug wiring — confirms the PTSKCAT0 real-star pipeline loads and
 * computes counts, without touching the renderer or the PTSKCAT4
 * constellation-art catalog. [RealStarCatalogLoadException] is caught and
 * surfaced as [RealStarVisibilityDebugSnapshot.Failure] rather than
 * propagated, so a missing/corrupt real-star asset can never crash a caller
 * that only wants a debug snapshot.
 */
class RealStarVisibilityDebugProbe(
    private val service: RealStarVisibilityService,
) {
    fun snapshot(input: SkyQualityInput): RealStarVisibilityDebugSnapshot {
        return try {
            val result = service.select(input)
            RealStarVisibilityDebugSnapshot.Success(
                RealStarVisibilityDebugInfo(
                    catalogCount = result.catalog.count,
                    catalogMagLimit = result.catalog.magLimit,
                    limitingMagnitude = result.limitingMagnitude,
                    visibleCount = result.selection.count,
                ),
            )
        } catch (e: RealStarCatalogLoadException) {
            RealStarVisibilityDebugSnapshot.Failure(e.message ?: "Failed to load PTSKCAT0 real-star catalog")
        }
    }
}
