package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Converts a device "forward" pointing vector, expressed in the Android world ENU frame
 * (`x = East`, `y = North`, `z = Up`), into horizontal coordinates.
 *
 * The forward vector is the direction the device is pointing, taken from the world rotation
 * matrix (`SensorManager.getRotationMatrixFromVector` → third column). Deriving the altitude as
 * `asin(up)` and the azimuth as `atan2(east, north)` is **roll-invariant** and yields the true
 * elevation of the pointing ray. This is different from the raw Euler pitch returned by
 * `SensorManager.getOrientation`, which mixes in roll and is therefore wrong as an altitude once
 * the wrist is tilted.
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
    val upClamped = up.coerceIn(-1.0, 1.0)
    val altDeg = radToDeg(asin(upClamped))
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
