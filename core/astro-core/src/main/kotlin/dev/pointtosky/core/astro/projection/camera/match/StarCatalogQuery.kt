package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.wrapRadTwoPi
import kotlin.math.PI

/**
 * The **catalog port** the future star matcher reads its sky-side candidates through — one half of the
 * matcher's input contract (the other half is [AnalysisBufferScale]; [StarMatcherInput] ties them
 * together). This file adds no matching algorithm: no association, no geometric invariant, no pose.
 *
 * ## Why a port instead of a direct call
 * The matcher belongs in `:core:astro-core`, which is a **pure-JVM** module (`kotlin("jvm")`, one
 * runtime dependency: `kotlinx.serialization.json` for the SKY-1 codec). The real star catalog reader,
 * `dev.pointtosky.core.catalog.binary.PtskCat0Catalog`, lives in `:core:catalog`, which is a
 * `com.android.library` — and an Android library can never be a dependency of a pure-JVM module.
 * `:core:catalog` already depends on `:core:astro-core`, so the dependency arrow points the wrong way
 * for a direct call and there is no way to reverse it without dragging Android into astro-core.
 *
 * The reader itself is Android-free (see its own KDoc) — it is only its *module* that is Android. So the
 * boundary is drawn here, at the type level: astro-core declares what it needs, `:core:catalog` supplies
 * it (`dev.pointtosky.core.catalog.binary.PtskCat0StarCatalogQuery`), and astro-core keeps its
 * build file unchanged.
 *
 * ## Why the result is [EquatorialStarDirection] and not a new `CatalogStar`
 * [EquatorialStarDirection] is already exactly "one catalog star's identity plus its equatorial
 * direction plus its magnitude", already the input type `projectStars` consumes, and already owns the
 * canonical-RA and range invariants a fresh type would have to re-implement. A parallel `CatalogStar`
 * would be the same four fields with the same checks, plus a conversion at every boundary — a second
 * spelling of one concept, which is how two spellings drift apart. Reusing it also means the matcher's
 * candidate list and the projector's input list are the *same* list, so a candidate can be projected
 * without a translation step that could silently change identity or units.
 *
 * ## Identity
 * [EquatorialStarDirection.catalogIndex] is whatever stable index the backing catalog uses; for
 * PTSKCAT0 it is the record index, which is stable for a given catalog binary and **not** portable
 * across binaries (a re-packed catalog renumbers records). A caller that needs a cross-binary identity
 * reads the Hipparcos number from its own catalog handle — it is deliberately not carried here, along
 * with names, colours and every other renderer concern.
 *
 * ## Units
 * Radians throughout, matching [EquatorialStarDirection] and the rest of astro-core. Implementations
 * backed by a degree-valued store convert at their own boundary, never here.
 */
interface StarCatalogQuery {
    /**
     * Returns every catalog star whose direction lies within [radiusRad] of
     * ([rightAscensionRad], [declinationRad]) — a great-circle angular separation, not a box — and, when
     * [magnitudeLimit] is present, whose magnitude is `<= magnitudeLimit` (smaller is brighter, so this
     * is a *brighter-or-equal* cut).
     *
     * Contract every implementation owes its caller:
     *  - **Validated and canonicalized inputs.** Arguments go through [normalizeStarCatalogQuery]
     *    before any lookup, and the lookup reads the [NormalizedStarCatalogQuery] it returns — never
     *    the raw parameters. A malformed query throws rather than quietly returning an empty list,
     *    because "no stars near here" and "the query was nonsense" are different answers and a matcher
     *    that cannot tell them apart will conclude the sky is empty.
     *  - **`radiusRad == 0.0` yields an empty list.** A zero-radius cone contains no star with any
     *    positional tolerance at all; treating it as an exact-match probe would be a different query.
     *  - **Deterministic.** Identical arguments over an unchanged catalog return an equal list, in an
     *    equal order. Nothing beyond determinism is promised about *which* order, so a matcher that
     *    depends on ordering must sort for itself.
     *  - **Distinct identities.** No [EquatorialStarDirection.catalogIndex] appears twice, so a matched
     *    pair can be logged by index without ambiguity.
     *  - **No side effects.** No caching, no mutation of the caller's arguments, no I/O beyond whatever
     *    the implementation's own already-loaded store requires.
     *  - **The magnitude cut is exact `Double` arithmetic**, applied to the very
     *    [EquatorialStarDirection.magnitude] the caller receives: for every returned star,
     *    `star.magnitude <= magnitudeLimit` holds when a limit was given. An implementation whose
     *    storage quantizes magnitudes must not let that quantization widen the cut — it may use its
     *    quantized index to narrow the candidate set, then apply the exact comparison itself. (PTSKCAT0
     *    stores centi-magnitudes and rounds a queried limit to them, so a `1.995` limit would otherwise
     *    admit a stored `2.00`; see `PtskCat0StarCatalogQuery`.)
     *
     * @param rightAscensionRad query RA in radians; any finite value is accepted and canonicalized by
     *   [normalizeStarCatalogQuery], matching [EquatorialStarDirection.of]. Callers never have to
     *   pre-normalize.
     * @param declinationRad query declination in radians, `[-π/2, +π/2]`.
     * @param radiusRad cone radius in radians, finite and non-negative.
     * @param magnitudeLimit faintest magnitude to return, or `null` for no limit. Must be finite when
     *   present — see [normalizeStarCatalogQuery] for why `±∞` is rejected rather than treated as
     *   "no limit".
     */
    fun nearby(
        rightAscensionRad: Double,
        declinationRad: Double,
        radiusRad: Double,
        magnitudeLimit: Double? = null,
    ): List<EquatorialStarDirection>
}

/**
 * One [StarCatalogQuery.nearby] call, validated and with its right ascension already canonical — the
 * only form an implementation should ever look a star up by.
 *
 * The constructor is `internal` and `@ConsistentCopyVisibility` makes the generated `copy()` internal
 * too, so outside `:core:astro-core` the sole way to obtain one is [normalizeStarCatalogQuery]. An
 * implementation therefore cannot end up holding a "normalized" query whose RA was never wrapped.
 *
 * @property rightAscensionRad canonical RA in `[0, 2π)`; see [normalizeStarCatalogQuery] for what that
 *   does and does not promise for very large inputs.
 * @property declinationRad the caller's declination, unchanged — it has no wraparound.
 * @property radiusRad the caller's cone radius, unchanged.
 * @property magnitudeLimit the caller's magnitude limit, unchanged.
 */
@ConsistentCopyVisibility
data class NormalizedStarCatalogQuery internal constructor(
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val radiusRad: Double,
    val magnitudeLimit: Double?,
) {
    init {
        require(rightAscensionRad >= 0.0 && rightAscensionRad < 2.0 * PI) {
            "rightAscensionRad must be canonical [0, 2π); was $rightAscensionRad"
        }
    }
}

/**
 * The single, shared entry point every [StarCatalogQuery] implementation runs a call through: it
 * validates the arguments **and** canonicalizes the right ascension, and hands back the values to
 * actually search with.
 *
 * ## Why one step and not two
 * An earlier revision exposed validation alone. That let two implementations accept exactly the same
 * query and then interpret it differently — the PTSKCAT0 adapter wrapped the RA before converting it
 * to degrees while the in-memory fake evaluated the raw value — which is precisely the divergence a
 * shared port exists to prevent, and it made the fake evidence about itself rather than about the
 * contract. Returning a [NormalizedStarCatalogQuery] removes the second step an implementation could
 * forget: there is nothing left to remember to do, because the only value worth searching with is the
 * one this function returns.
 *
 * ## What canonicalizing the RA does and does not promise
 * The wrap is [wrapRadTwoPi], the project's canonical radian wrap and the same one
 * [EquatorialStarDirection.of] applies, so "canonical RA" means one thing everywhere. It is a
 * floating-point remainder against the `Double` value of `2π`, which cannot overflow and cannot produce
 * a `NaN` for any finite input — the concrete failure it prevents is a large-but-finite RA reaching
 * `Math.toDegrees` (a multiply by ~57.3) and becoming infinite, after which every angular separation
 * downstream is `NaN` and a well-defined direction silently returns no stars.
 *
 * What it does **not** promise is exact argument reduction of the true real angle. `2π` is not
 * representable as a `Double`, so for an input spanning an enormous number of turns the difference
 * between the stored constant and real `2π`, multiplied by the number of turns, dominates the result:
 * the value returned is the correct reduction with respect to the constant this codebase uses, not a
 * physically meaningful direction. That is a fine and deterministic answer for a degenerate input, and
 * it is identical across implementations, which is all this contract needs. Real queries sit within a
 * few turns of canonical, where the distinction does not arise.
 *
 * ## Why `±∞` is not a magnitude limit
 * [magnitudeLimit] must be **finite** when present. `±∞` is not a harmless spelling of "no limit": the
 * PTSKCAT0 reader converts a magnitude limit to centi-magnitudes with `Math.round(limit * 100.0).toInt()`,
 * and `Math.round(+∞)` is `Long.MAX_VALUE`, whose `toInt()` is `-1` — so an infinite "no limit" would
 * silently select *zero* stars, the exact opposite of what it reads as. `null` is the only spelling of
 * "no limit" this port accepts.
 *
 * @throws IllegalArgumentException if any argument is outside the contract in [StarCatalogQuery.nearby].
 *   A non-finite right ascension is rejected here and is never wrapped into something plausible.
 */
fun normalizeStarCatalogQuery(
    rightAscensionRad: Double,
    declinationRad: Double,
    radiusRad: Double,
    magnitudeLimit: Double?,
): NormalizedStarCatalogQuery {
    require(rightAscensionRad.isFinite()) { "rightAscensionRad must be finite; was $rightAscensionRad" }
    require(declinationRad.isFinite()) { "declinationRad must be finite; was $declinationRad" }
    require(declinationRad in -PI / 2.0..PI / 2.0) {
        "declinationRad must be in [-π/2, π/2]; was $declinationRad"
    }
    require(radiusRad.isFinite()) { "radiusRad must be finite; was $radiusRad" }
    require(radiusRad >= 0.0) { "radiusRad must be non-negative; was $radiusRad" }
    require(magnitudeLimit == null || magnitudeLimit.isFinite()) {
        "magnitudeLimit must be finite when present (use null for no limit); was $magnitudeLimit"
    }
    return NormalizedStarCatalogQuery(
        rightAscensionRad = wrapRadTwoPi(rightAscensionRad),
        declinationRad = declinationRad,
        radiusRad = radiusRad,
        magnitudeLimit = magnitudeLimit,
    )
}
