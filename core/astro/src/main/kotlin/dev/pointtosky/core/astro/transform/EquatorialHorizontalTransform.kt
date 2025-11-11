package dev.pointtosky.core.astro.transform

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.units.clamp
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import dev.pointtosky.core.astro.units.wrapDegMinus180To180
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private const val EPS = 1e-12

/**
 * Meteorological parameters for refraction corrections.
 *
 * @property pressureMbar Atmospheric pressure in millibars (hPa).
 * @property temperatureC Ambient temperature in degrees Celsius.
 */
data class Meteo(
    val pressureMbar: Double = 1010.0,
    val temperatureC: Double = 10.0,
)

/**
 * Converts equatorial coordinates (right ascension and declination) to horizontal coordinates
 * (azimuth and altitude) for a given local sidereal time and observer latitude.
 *
 * Uses the standard spherical astronomy relations as given, for example, in Meeus (1991):
 * τ = LST − RA, sin h = sin φ sin δ + cos φ cos δ cos τ, sin A = −sin τ cos δ / cos h,
 * cos A = (sin δ − sin h sin φ) / (cos h cos φ). Azimuth is measured clockwise from North (0°).
 * Optionally applies the Saemundsson atmospheric refraction model.
 *
 * @param eq Equatorial coordinates of the target.
 * @param lstDeg Local sidereal time in degrees.
 * @param latDeg Observer geographic latitude in degrees.
 * @param applyRefraction Whether to apply the Saemundsson refraction correction.
 * @param meteo Meteorological parameters used for refraction correction when [applyRefraction] is true.
 * @return Horizontal coordinates (azimuth 0°–360°, altitude −90°…+90°).
 */
fun raDecToAltAz(eq: Equatorial, lstDeg: Double, latDeg: Double, applyRefraction: Boolean = true, meteo: Meteo? = Meteo()): Horizontal {
    val lstDegWrapped = wrapDeg0To360(lstDeg)
    val raDegWrapped = wrapDeg0To360(eq.raDeg)
    val decRad = degToRad(clamp(eq.decDeg, -90.0, 90.0))
    val latRad = degToRad(clamp(latDeg, -90.0, 90.0))

    val tauRad = degToRad(wrapDegMinus180To180(lstDegWrapped - raDegWrapped))

    val sinPhi = sin(latRad)
    val cosPhi = cos(latRad)
    val sinDelta = sin(decRad)
    val cosDelta = cos(decRad)
    val cosPhiSafe = if (abs(cosPhi) < EPS) {
        if (cosPhi >= 0.0) EPS else -EPS
    } else {
        cosPhi
    }

    val sinTau = sin(tauRad)
    val cosTau = cos(tauRad)

    val sinH = sinPhi * sinDelta + cosPhi * cosDelta * cosTau
    val sinHClamped = clamp(sinH, -1.0, 1.0)
    val hRad = asin(sinHClamped)
    val cosH = max(cos(hRad), EPS)

    val sinA = -sinTau * cosDelta / cosH
    val cosA = (sinDelta - sinHClamped * sinPhi) / (cosH * cosPhiSafe)
    val azRad = atan2(sinA, cosA)

    val altWithoutRef = radToDeg(hRad)
    var altDeg = clamp(altWithoutRef, -90.0, 90.0)

    if (applyRefraction && altDeg > -1.0) {
        val meteoParams = meteo ?: Meteo()
        val tanArgument = altDeg + 10.3 / (altDeg + 5.11)
        val tanValue = tan(degToRad(tanArgument))
        if (abs(tanValue) > EPS) {
            var refractionDeg = 1.02 / tanValue / 60.0
            val pressureFactor = meteoParams.pressureMbar / 1010.0
            val temperatureFactor = 283.0 / (273.0 + meteoParams.temperatureC)
            refractionDeg *= pressureFactor * temperatureFactor
            altDeg = clamp(altDeg + refractionDeg, -90.0, 90.0)
        }
    }

    val azDeg = wrapDeg0To360(radToDeg(azRad))

    return Horizontal(azDeg = azDeg, altDeg = altDeg)
}

/**
 * Converts horizontal coordinates (azimuth and altitude) to equatorial coordinates (right ascension
 * and declination) for a given local sidereal time and observer latitude.
 *
 * Implements the inverse spherical relations of Meeus (1991). Azimuth is expected in degrees,
 * measured clockwise from North (0°). The returned right ascension is wrapped into the [0°, 360°)
 * interval.
 *
 * @param hor Horizontal coordinates of the target.
 * @param lstDeg Local sidereal time in degrees.
 * @param latDeg Observer geographic latitude in degrees.
 * @return Equatorial coordinates (right ascension 0°–360°, declination −90°…+90°).
 */
fun altAzToRaDec(hor: Horizontal, lstDeg: Double, latDeg: Double): Equatorial {
    val lstRad = degToRad(wrapDeg0To360(lstDeg))
    val azRad = degToRad(wrapDeg0To360(hor.azDeg))
    val altRad = degToRad(clamp(hor.altDeg, -90.0, 90.0))
    val latRad = degToRad(clamp(latDeg, -90.0, 90.0))

    val sinPhi = sin(latRad)
    val cosPhi = cos(latRad)
    val cosPhiSafe = if (abs(cosPhi) < EPS) {
        if (cosPhi >= 0.0) EPS else -EPS
    } else {
        cosPhi
    }

    val sinAlt = sin(altRad)
    val cosAlt = max(cos(altRad), EPS)
    val sinAz = sin(azRad)
    val cosAz = cos(azRad)

    val sinDelta = sinAlt * sinPhi + cosAlt * cosPhi * cosAz
    val sinDeltaClamped = clamp(sinDelta, -1.0, 1.0)
    val deltaRad = asin(sinDeltaClamped)
    val cosDelta = sqrt(max(0.0, 1.0 - sinDeltaClamped * sinDeltaClamped))
    val cosDeltaSafe = if (cosDelta < EPS) EPS else cosDelta

    val sinTau = -sinAz * cosAlt / cosDeltaSafe
    val cosTau = (sinAlt - sinPhi * sinDeltaClamped) / (cosPhiSafe * cosDeltaSafe)
    val tauRad = atan2(sinTau, cosTau)

    val raRad = lstRad - tauRad
    val raDeg = wrapDeg0To360(radToDeg(raRad))
    val decDeg = clamp(radToDeg(deltaRad), -90.0, 90.0)

    return Equatorial(raDeg = raDeg, decDeg = decDeg)
}
