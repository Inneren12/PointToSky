package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
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
     *  - **Validated inputs.** Arguments are checked by [requireValidStarCatalogQuery] before any
     *    lookup; a malformed query throws rather than quietly returning an empty list, because
     *    "no stars near here" and "the query was nonsense" are different answers and a matcher that
     *    cannot tell them apart will conclude the sky is empty.
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
     * @param rightAscensionRad query RA in radians; any finite value is accepted and wrapped, matching
     *   [EquatorialStarDirection.of].
     * @param declinationRad query declination in radians, `[-π/2, +π/2]`.
     * @param radiusRad cone radius in radians, finite and non-negative.
     * @param magnitudeLimit faintest magnitude to return, or `null` for no limit. Must be finite when
     *   present — see [requireValidStarCatalogQuery] for why `±∞` is rejected rather than treated as
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
 * The single, shared validation of a [StarCatalogQuery.nearby] call, so every implementation and every
 * test fake rejects exactly the same set of malformed queries — rather than each one inventing its own
 * slightly different notion of "valid", which is how a fake ends up accepting a query the real adapter
 * throws on.
 *
 * [magnitudeLimit] must be **finite** when present. `±∞` is not a harmless spelling of "no limit": the
 * PTSKCAT0 reader converts a magnitude limit to centi-magnitudes with `Math.round(limit * 100.0).toInt()`,
 * and `Math.round(+∞)` is `Long.MAX_VALUE`, whose `toInt()` is `-1` — so an infinite "no limit" would
 * silently select *zero* stars, the exact opposite of what it reads as. `null` is the only spelling of
 * "no limit" this port accepts.
 *
 * @throws IllegalArgumentException if any argument is outside the contract in [StarCatalogQuery.nearby].
 */
fun requireValidStarCatalogQuery(
    rightAscensionRad: Double,
    declinationRad: Double,
    radiusRad: Double,
    magnitudeLimit: Double?,
) {
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
}
