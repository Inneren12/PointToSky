package dev.pointtosky.wear.sensors.orientation

import android.hardware.GeomagneticField

/**
 * [MagneticDeclinationProvider] backed by the World Magnetic Model bundled with Android via
 * [GeomagneticField]. Pure computation, no sensors or permissions required — only the observer's
 * location and the time.
 */
class GeomagneticFieldDeclinationProvider : MagneticDeclinationProvider {
    override fun declinationDeg(
        latDeg: Double,
        lonDeg: Double,
        altitudeM: Double,
        epochMillis: Long,
    ): Float =
        GeomagneticField(
            latDeg.toFloat(),
            lonDeg.toFloat(),
            altitudeM.toFloat(),
            epochMillis,
        ).declination
}
