package dev.pointtosky.wear.sensors.orientation

import android.hardware.GeomagneticField

/**
 * [MagneticDeclinationProvider] backed by the World Magnetic Model bundled with Android via
 * [GeomagneticField]. Pure computation, no sensors or permissions required — only the observer's
 * location and the time.
 *
 * TODO: cache the result by coarse location (e.g. ~0.1° lat/lon bucket) and date. WMM declination
 *   changes on the scale of years and metres, so recomputing it on every sensor tick (~15 Hz) is
 *   wasted work; a memoized value keyed on rounded inputs would be effectively free.
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
