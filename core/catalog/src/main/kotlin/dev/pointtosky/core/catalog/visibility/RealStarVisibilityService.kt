package dev.pointtosky.core.catalog.visibility

import dev.pointtosky.core.catalog.binary.PtskCat0Catalog
import dev.pointtosky.core.catalog.binary.RealStarCatalogProvider
import dev.pointtosky.core.catalog.binary.RealStarVisibilityFilter
import dev.pointtosky.core.catalog.binary.VisibleStarSelection

/** Manual sky-quality input accepted by [RealStarVisibilityService.select]. */
sealed interface SkyQualityInput {

    /** Bortle dark-sky class `1..9`; converted via [LimitingMagnitudeModel.fromBortle]. */
    data class Bortle(val value: Int) : SkyQualityInput

    /** Sky-quality-meter reading in mag/arcsec^2; converted via [LimitingMagnitudeModel.fromSqm]. */
    data class Sqm(val value: Double) : SkyQualityInput

    /**
     * A limiting magnitude supplied directly by the caller, bypassing
     * [LimitingMagnitudeModel]'s Bortle/SQM conversion. `NaN` is rejected.
     * [Double.POSITIVE_INFINITY] / [Double.NEGATIVE_INFINITY] are passed through
     * unchanged, preserving [RealStarVisibilityFilter]'s "no limit" / "no stars"
     * semantics. Any other, finite value is clamped to
     * [LimitingMagnitudeModel.SUPPORTED_RANGE] before being passed to the filter,
     * so a direct limiting magnitude stays within the same outer bound as the
     * Bortle/SQM conversions.
     */
    data class LimitingMagnitude(val value: Double) : SkyQualityInput
}

/**
 * Result of [RealStarVisibilityService.select]. [catalog] is exposed alongside
 * [selection] so callers can resolve selected indices into ra/dec/mag/name
 * without a second catalog load; no star data is copied here.
 */
data class RealStarVisibilityResult(
    val catalog: PtskCat0Catalog,
    val limitingMagnitude: Double,
    val selection: VisibleStarSelection,
)

/**
 * VF-1d: composes [RealStarCatalogProvider], [LimitingMagnitudeModel], and
 * [RealStarVisibilityFilter] into a single call from manual sky-quality input to
 * a visible real-star selection. No GPS, no automatic skyglow-grid lookup, no
 * Moon/twilight, no weather/clouds, no camera matching, and no renderer/UI
 * wiring — those stay out of scope for this layer.
 *
 * The catalog is loaded fresh on every [select] call; this service holds no
 * cache.
 */
class RealStarVisibilityService(
    private val provider: RealStarCatalogProvider,
) {

    /**
     * @throws dev.pointtosky.core.catalog.binary.RealStarCatalogLoadException if the
     *   catalog asset can't be loaded ([RealStarCatalogProvider.load]).
     * @throws IllegalArgumentException if [input] is an out-of-range
     *   [SkyQualityInput.Bortle] class, or a [SkyQualityInput.LimitingMagnitude]
     *   value that is `NaN`.
     */
    fun select(input: SkyQualityInput): RealStarVisibilityResult {
        val limitingMagnitude = when (input) {
            is SkyQualityInput.Bortle -> LimitingMagnitudeModel.fromBortle(input.value)
            is SkyQualityInput.Sqm -> LimitingMagnitudeModel.fromSqm(input.value)
            is SkyQualityInput.LimitingMagnitude -> {
                require(!input.value.isNaN()) { "limitingMagnitude must not be NaN, was ${input.value}" }
                if (input.value.isInfinite()) {
                    input.value
                } else {
                    input.value.coerceIn(LimitingMagnitudeModel.SUPPORTED_RANGE)
                }
            }
        }

        val catalog = provider.load()
        val selection = RealStarVisibilityFilter.select(catalog, limitingMagnitude)

        return RealStarVisibilityResult(
            catalog = catalog,
            limitingMagnitude = limitingMagnitude,
            selection = selection,
        )
    }
}
