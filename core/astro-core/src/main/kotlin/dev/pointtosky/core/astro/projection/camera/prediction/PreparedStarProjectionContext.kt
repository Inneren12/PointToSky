package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.units.radToDeg
import java.time.Instant

/**
 * Per-batch prepared form of a [StarProjectionContext] (CAM-2b perf hardening): the parts of the
 * context that are identical for every star in one [projectStars] call, computed **once** rather than
 * once per star.
 *
 * [StarProjectionContext.utcEpochMillis]/[StarProjectionContext.longitudeRad] only ever feed
 * [lstDeg] — local sidereal time depends on the observer's longitude and the instant, not on any
 * individual star — so re-deriving it inside a per-star loop (as an earlier revision of
 * [equatorialToLocalSky] did, calling [Instant.ofEpochMilli]/[lstAt] once per star) repeats the exact
 * same deterministic computation up to [PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS]-many times for a
 * bounded CAM-2b batch (`dev.pointtosky.mobile.ar.camera.prediction.PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS`)
 * for no benefit — [lstAt] is a pure function of ([Instant], longitude) and returns the identical
 * value every time for a fixed [StarProjectionContext].
 *
 * `internal`: this is a batch-perf implementation detail of [projectStars], not a new public contract
 * — external callers still only ever construct/pass a [StarProjectionContext].
 */
internal data class PreparedStarProjectionContext(
    val latitudeRad: Double,
    val lstDeg: Double,
    val magneticDeclinationRad: Double,
)

/**
 * Computes [PreparedStarProjectionContext] from [context] exactly once — the single place
 * [StarProjectionContext.utcEpochMillis] is converted to an [Instant] and fed to [lstAt]. [projectStars]
 * calls this once per batch and then projects every star through the 3-arg
 * [equatorialToLocalSky] overload using the resulting [PreparedStarProjectionContext.lstDeg], never
 * recomputing sidereal time per star.
 */
internal fun prepareStarProjectionContext(context: StarProjectionContext): PreparedStarProjectionContext {
    val instant = Instant.ofEpochMilli(context.utcEpochMillis)
    val lstDeg = lstAt(instant, radToDeg(context.longitudeRad)).lstDeg
    return PreparedStarProjectionContext(
        latitudeRad = context.latitudeRad,
        lstDeg = lstDeg,
        magneticDeclinationRad = context.magneticDeclinationRad,
    )
}
