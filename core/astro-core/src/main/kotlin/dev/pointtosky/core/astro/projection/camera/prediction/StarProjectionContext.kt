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
 *    parameter). The primary constructor only requires it to be finite; prefer
 *    [StarProjectionContext.of], which wraps it into the canonical `[-π, π)` interval via
 *    [wrapRadMinusPiToPi] before constructing.
 *  - [utcEpochMillis] is an absolute instant — milliseconds since the Unix epoch, UTC, exactly as
 *    `Instant.ofEpochMilli(utcEpochMillis)` would interpret it. It is converted to a Julian day (see
 *    [dev.pointtosky.core.astro.time.instantToJulianDay]) and then local sidereal time (see
 *    [dev.pointtosky.core.astro.time.lstAt]) — never compared against wall-clock time, never
 *    adjusted by a timezone offset.
 */
data class StarProjectionContext(
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
    }

    companion object {
        /** Canonical factory: wraps [longitudeRad] into `[-π, π)` (see [wrapRadMinusPiToPi]) before constructing. */
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
