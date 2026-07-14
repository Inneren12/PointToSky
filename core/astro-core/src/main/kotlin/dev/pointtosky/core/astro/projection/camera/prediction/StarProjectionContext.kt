package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI

/**
 * Observer location and instant for one CAM-2a prediction call. Pure data — no device locale, no
 * local timezone, no `System.currentTimeMillis()` is read anywhere in the projector; every value
 * this type carries must be supplied by the caller.
 *
 * ## Conventions
 *  - [latitudeRad] is geographic latitude in radians, `[-π/2, +π/2]`, positive north (matching
 *    [dev.pointtosky.core.astro.coord.GeoPoint.latDeg], just in radians). Always range-checked.
 *  - [longitudeRad] is geographic longitude in radians, **east-positive** (matching
 *    `GeoPoint.lonDeg`'s convention and [dev.pointtosky.core.astro.time.lstAt]'s `longitudeDeg`
 *    parameter), and is **always** stored canonically in `[-π, π)`. The primary constructor is
 *    `private` and [of] — which wraps via [wrapRadMinusPiToPi] before constructing — is the *only*
 *    way to obtain an instance: `@ConsistentCopyVisibility` makes the generated `copy()` private too
 *    (mirroring [dev.pointtosky.core.astro.projection.camera.CropScaleTransform]'s own convention),
 *    so a noncanonical longitude cannot reach storage via direct construction *or* `copy()`. `init`
 *    re-derives and checks the same range as defense in depth, even though [of] is the only
 *    reachable path.
 *  - [utcEpochMillis] is an absolute instant — milliseconds since the Unix epoch, UTC, exactly as
 *    `Instant.ofEpochMilli(utcEpochMillis)` would interpret it. It is converted to a Julian day (see
 *    [dev.pointtosky.core.astro.time.instantToJulianDay]) and then local sidereal time (see
 *    [dev.pointtosky.core.astro.time.lstAt]) — never compared against wall-clock time, never
 *    adjusted by a timezone offset.
 */
@ConsistentCopyVisibility
data class StarProjectionContext private constructor(
    val latitudeRad: Double,
    val longitudeRad: Double,
    val utcEpochMillis: Long,
) {
    init {
        require(latitudeRad.isFinite()) { "latitudeRad must be finite; was $latitudeRad" }
        require(longitudeRad.isFinite()) { "longitudeRad must be finite; was $longitudeRad" }
        require(latitudeRad in -PI / 2.0..PI / 2.0) {
            "latitudeRad must be in [-π/2, π/2]; was $latitudeRad"
        }
        require(longitudeRad >= -PI && longitudeRad < PI) {
            "longitudeRad must be canonical [-π, π); was $longitudeRad"
        }
    }

    companion object {
        /**
         * The sole construction path: wraps [longitudeRad] into `[-π, π)` (see
         * [wrapRadMinusPiToPi]) before constructing, so no noncanonical longitude can ever reach a
         * stored [StarProjectionContext].
         */
        fun of(
            latitudeRad: Double,
            longitudeRad: Double,
            utcEpochMillis: Long,
        ): StarProjectionContext {
            require(longitudeRad.isFinite()) { "longitudeRad must be finite; was $longitudeRad" }
            return StarProjectionContext(
                latitudeRad = latitudeRad,
                longitudeRad = wrapRadMinusPiToPi(longitudeRad),
                utcEpochMillis = utcEpochMillis,
            )
        }
    }
}
