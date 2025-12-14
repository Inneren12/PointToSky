package dev.pointtosky.wear.tile.tonight

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.time.gmstDeg
import dev.pointtosky.core.astro.time.instantToJulianDay
import dev.pointtosky.core.astro.time.lstDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object AstroMath {
    data class Horizontal(
        val azDeg: Double,
        val altDeg: Double,
    )

    fun raDecToAltAz(
        eq: Equatorial,
        instant: Instant,
        latDeg: Double,
        lonDeg: Double,
    ): Horizontal {
        val decRad = Math.toRadians(eq.decDeg.coerceIn(-90.0, 90.0))
        val latRad = Math.toRadians(latDeg.coerceIn(-90.0, 90.0))
        val jd = instantToJulianDay(instant)
        val gmst = gmstDeg(jd)
        val lst = lstDeg(gmst, lonDeg)
        val haRad = Math.toRadians(wrapDeg0To360(lst) - wrapDeg0To360(eq.raDeg))

        val sinAlt = sin(decRad) * sin(latRad) + cos(decRad) * cos(latRad) * cos(haRad)
        val alt = asin(sinAlt)
        val cosAz = (sin(decRad) - sin(alt) * sin(latRad)) / (cos(alt) * cos(latRad))
        val sinAz = -cos(decRad) * sin(haRad) / cos(alt)
        var az = atan2(sinAz, cosAz)
        if (az < 0) az += 2 * Math.PI
        return Horizontal(azDeg = Math.toDegrees(az), altDeg = Math.toDegrees(alt))
    }

    fun airmassFromAltDeg(altDeg: Double): Double {
        val z = 90.0 - altDeg
        if (z >= 90.0) return 99.0
        val zRad = Math.toRadians(z)
        val cosZ = cos(zRad)
        // Kasten & Young (1989)
        return 1.0 / (cosZ + 0.50572 * (90.0 - z + 6.07995).pow(-1.6364))
    }
}
