package dev.pointtosky.wear.sensors.orientation

/**
 * Supplies the local magnetic declination (east-positive, in degrees) used to turn a
 * magnetic-north compass azimuth into a true-north azimuth.
 *
 * The compass/rotation-vector world frame is referenced to magnetic north, while the celestial
 * pipeline works in true north, so the difference (declination) must be added before any
 * azimuth → equatorial conversion. Kept as a plain interface (no Android types) so it can be
 * injected with a no-op in pure-JVM unit tests; the production implementation is
 * [GeomagneticFieldDeclinationProvider].
 */
fun interface MagneticDeclinationProvider {
    /**
     * @param latDeg Observer latitude in degrees.
     * @param lonDeg Observer longitude in degrees (east positive).
     * @param altitudeM Altitude above the WGS84 ellipsoid in metres (declination is nearly
     *   altitude-independent; `0` is acceptable when unknown).
     * @param epochMillis UTC time in milliseconds since the Unix epoch (the field model drifts
     *   slowly over years).
     * @return Magnetic declination in degrees, east positive.
     */
    fun declinationDeg(latDeg: Double, lonDeg: Double, altitudeM: Double, epochMillis: Long): Float

    companion object {
        /** No-op provider (declination 0°) — leaves azimuth in magnetic north. */
        val Zero: MagneticDeclinationProvider = MagneticDeclinationProvider { _, _, _, _ -> 0f }
    }
}
