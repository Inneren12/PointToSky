package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/** Below this length the forward vector carries no usable direction and the fallback is used. */
private const val MIN_FORWARD_LENGTH = 1e-9

/**
 * Horizontal reading returned for a degenerate (zero / near-zero length) forward vector.
 *
 * This is the zenith (azimuth 0°, altitude 90°), which matches the project's existing "no data"
 * convention: `OrientationFrameDefaults.EMPTY` uses `forward = (0, 0, 1)` and callers default a
 * missing up-component to `1.0`. Using the same neutral value here keeps degenerate input from
 * producing arbitrary azimuths.
 */
private val DEGENERATE_FALLBACK = Horizontal(azDeg = 0.0, altDeg = 90.0)

/**
 * Converts a device "forward" pointing vector, expressed in the Android world ENU frame
 * (`x = East`, `y = North`, `z = Up`), into horizontal coordinates.
 *
 * The forward vector is the direction the device is pointing, taken from the world rotation
 * matrix (`SensorManager.getRotationMatrixFromVector` → third column). The altitude is the
 * elevation of that ray (`asin(up / |v|)`) and the azimuth is `atan2(east, north)`. Deriving
 * altitude from the vector this way is **roll-invariant** — unlike the raw Euler pitch returned by
 * `SensorManager.getOrientation`, which mixes in roll and is wrong as an altitude once the wrist is
 * tilted.
 *
 * The input **does not need to be normalized**: the vector is normalized internally before the
 * altitude is taken, so `asin` always receives a value in `[-1, 1]`. Azimuth is invariant under
 * uniform scaling and is computed directly from the raw components. A zero or near-zero length
 * vector (length < 1e-9) carries no direction and yields [DEGENERATE_FALLBACK] (the zenith).
 *
 * Azimuth is measured clockwise from **magnetic** North, because the rotation-vector/compass world
 * frame is referenced to the magnetic meridian. Use [toTrueNorth] to obtain a true-north azimuth
 * before feeding the result into equatorial conversions.
 *
 * @param east East component of the (not necessarily normalized) forward vector.
 * @param north North component of the forward vector.
 * @param up Up component of the forward vector.
 * @return Horizontal coordinates with azimuth in `[0°, 360°)` (magnetic) and altitude in `[-90°, 90°]`.
 */
fun forwardVectorToHorizontal(east: Double, north: Double, up: Double): Horizontal {
    val norm = sqrt(east * east + north * north + up * up)
    if (norm < MIN_FORWARD_LENGTH) return DEGENERATE_FALLBACK
    val altDeg = radToDeg(asin((up / norm).coerceIn(-1.0, 1.0)))
    val azDeg = wrapDeg0To360(radToDeg(atan2(east, north)))
    return Horizontal(azDeg = azDeg, altDeg = altDeg)
}

/**
 * Converts a **magnetic**-north azimuth/altitude into a **true**-north one by applying the local
 * magnetic declination.
 *
 * Declination is east-positive, matching `android.hardware.GeomagneticField.getDeclination()`:
 * when magnetic north lies east of true north the value is positive, and
 * `trueAzimuth = magneticAzimuth + declination`. Altitude is unaffected by declination.
 *
 * @param declinationDeg Magnetic declination in degrees (east positive). `0` is a safe no-op.
 * @return Horizontal coordinates referenced to true north.
 */
fun Horizontal.toTrueNorth(declinationDeg: Double): Horizontal =
    if (declinationDeg == 0.0) this else copy(azDeg = wrapDeg0To360(azDeg + declinationDeg))
