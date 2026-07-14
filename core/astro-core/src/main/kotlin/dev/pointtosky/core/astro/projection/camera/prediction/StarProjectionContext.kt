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
 *  - [magneticDeclinationRad] is the local magnetic declination in radians — the angle from **true**
 *    north to **magnetic** north, **east-positive**. This is the exact same quantity and sign
 *    convention as the existing production correction,
 *    `dev.pointtosky.mobile.ar.RotationFrame.correctedForTrueNorth(declinationDeg)`
 *    (`mobile/.../ar/RotationFrame.kt`) and `android.hardware.GeomagneticField.getDeclination()`
 *    which feeds it: positive means magnetic north lies east of true north, and
 *    `trueAzimuth = magneticAzimuth + declination`. See
 *    [dev.pointtosky.core.astro.projection.camera.prediction.trueEnuToMagneticEnu] for the CAM-2a
 *    transform this value feeds, and its KDoc for the full algebraic trace back to
 *    `correctedForTrueNorth`. Stored canonically in `[-π, π)` via [wrapRadMinusPiToPi], the same
 *    wraparound policy as [longitudeRad] (an angle, not a position, but the same canonical interval
 *    is a natural, unambiguous choice and matches this class's existing convention).
 *
 *    **The default, `0.0`, means "treat magnetic north as true north" — an explicit, deliberate
 *    uncorrected mode — not a claim that the real local magnetic declination is known to be zero.**
 *    CAM-2a is a pure math slice and cannot compute a real declination itself (that requires a
 *    platform magnetic model such as `android.hardware.GeomagneticField`, driven by latitude,
 *    longitude, altitude, and UTC time — an Android dependency this module must not take on). A
 *    future mobile integration (CAM-2b or later) is responsible for supplying the real value it
 *    already computes for the legacy renderer (see `ArScreen.kt`'s `GeomagneticField(...).declination`
 *    call site); production visual alignment must not silently assume `0°` when the actual declination
 *    is unknown — that integration should either supply the real value or surface an explicit
 *    diagnostic/unavailable/uncorrected quality state, mirroring how CAM-2a itself already refuses to
 *    silently fabricate a physical-sensor-to-buffer intrinsics mapping (`CameraIntrinsicsReference`)
 *    rather than guessing. Implementing that mobile-side state is explicitly out of scope for this fix.
 */
@ConsistentCopyVisibility
data class StarProjectionContext private constructor(
    val latitudeRad: Double,
    val longitudeRad: Double,
    val utcEpochMillis: Long,
    val magneticDeclinationRad: Double,
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
        require(magneticDeclinationRad.isFinite()) {
            "magneticDeclinationRad must be finite; was $magneticDeclinationRad"
        }
        require(magneticDeclinationRad >= -PI && magneticDeclinationRad < PI) {
            "magneticDeclinationRad must be canonical [-π, π); was $magneticDeclinationRad"
        }
    }

    companion object {
        /**
         * The sole construction path: wraps [longitudeRad] and [magneticDeclinationRad] into
         * `[-π, π)` (see [wrapRadMinusPiToPi]) before constructing, so neither can ever reach a
         * stored [StarProjectionContext] outside its canonical range.
         *
         * [magneticDeclinationRad] defaults to `0.0` — an explicit "treat magnetic north as true
         * north" uncorrected mode, preserving every existing pure caller/test whose correction is
         * intentionally disabled. See the class KDoc for why this default must never be mistaken for
         * "declination is known to be zero" in a production context.
         */
        fun of(
            latitudeRad: Double,
            longitudeRad: Double,
            utcEpochMillis: Long,
            magneticDeclinationRad: Double = 0.0,
        ): StarProjectionContext {
            require(longitudeRad.isFinite()) { "longitudeRad must be finite; was $longitudeRad" }
            require(magneticDeclinationRad.isFinite()) {
                "magneticDeclinationRad must be finite; was $magneticDeclinationRad"
            }
            return StarProjectionContext(
                latitudeRad = latitudeRad,
                longitudeRad = wrapRadMinusPiToPi(longitudeRad),
                utcEpochMillis = utcEpochMillis,
                magneticDeclinationRad = wrapRadMinusPiToPi(magneticDeclinationRad),
            )
        }
    }
}
