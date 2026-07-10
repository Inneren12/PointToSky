package dev.pointtosky.core.catalog.visibility.render

import dev.pointtosky.core.catalog.visibility.RealStarVisibilityResult
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput

/**
 * Read-only, renderer-facing view of a single visible real star. Exposes only
 * plain projected fields — no [dev.pointtosky.core.catalog.binary.PtskCat0Catalog]
 * or [dev.pointtosky.core.catalog.binary.VisibleStarSelection] reference, so a
 * future renderer/AR consumer never needs to depend on the PTSKCAT0 binary
 * representation directly.
 */
data class VisibleRealStar(
    val raDeg: Double,
    val decDeg: Double,
    val mag: Double,
    val bv: Double? = null,
    val hip: Int? = null,
    val name: String? = null,
)

/**
 * VF-2a: renderer-facing adapter over an already-computed [RealStarVisibilityResult].
 * This wraps a result produced elsewhere — it never calls
 * [RealStarVisibilityService] or loads a catalog itself, so constructing a
 * snapshot is never a new I/O or cold-start path (see [VisibleRealStarProvider]
 * for the one-call "select, then adapt" composition future callers can use
 * instead).
 *
 * [stars] is a lazy, non-copying view: each [VisibleRealStar] is built on
 * access directly from the backing catalog's primitive accessors
 * (`raDegAt`/`decDegAt`/`magAt`/`bvAt`/`hipAt`/`nameAt` on
 * [dev.pointtosky.core.catalog.binary.PtskCat0Catalog]). Nothing is
 * materialized upfront, matching [RealStarVisibilityResult]'s own
 * "no star data copy" contract (docs/real_star_visibility_contract.md §3).
 */
class VisibleRealStarSnapshot private constructor(private val result: RealStarVisibilityResult) {

    /** The limiting magnitude the source selection was computed at. */
    val limitingMagnitude: Double get() = result.limitingMagnitude

    /** Number of visible stars in this snapshot. */
    val count: Int get() = result.selection.count

    /**
     * The [index]-th visible star, mag-sorted brightest first.
     *
     * @throws IndexOutOfBoundsException if [index] is outside `0 until count`.
     */
    fun starAt(index: Int): VisibleRealStar {
        if (index !in 0 until count) {
            throw IndexOutOfBoundsException("index $index out of bounds for count $count")
        }
        val catalog = result.catalog
        return VisibleRealStar(
            raDeg = catalog.raDegAt(index).toDouble(),
            decDeg = catalog.decDegAt(index).toDouble(),
            mag = catalog.magAt(index),
            bv = catalog.bvAt(index),
            hip = catalog.hipAt(index).takeIf { it > 0 },
            name = catalog.nameAt(index),
        )
    }

    /** Lazy, non-copying `List` view over [starAt] — see class doc. */
    val stars: List<VisibleRealStar> = object : AbstractList<VisibleRealStar>() {
        override val size: Int get() = count
        override fun get(index: Int): VisibleRealStar = starAt(index)
    }

    companion object {
        fun from(result: RealStarVisibilityResult): VisibleRealStarSnapshot = VisibleRealStarSnapshot(result)
    }
}

/**
 * Thin, stateless facade composing [RealStarVisibilityService.select] with
 * [VisibleRealStarSnapshot.from] — the single call a future renderer/AR
 * consumer needs to go from manual sky-quality input to a renderer-facing
 * visible-star snapshot, without importing anything from
 * `dev.pointtosky.core.catalog.binary`.
 *
 * Introduces no caching or I/O beyond [RealStarVisibilityService.select]
 * itself; see that method's contract for load/caching behavior.
 */
object VisibleRealStarProvider {
    fun snapshot(service: RealStarVisibilityService, input: SkyQualityInput): VisibleRealStarSnapshot =
        VisibleRealStarSnapshot.from(service.select(input))
}
