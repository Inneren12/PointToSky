package dev.pointtosky.core.astro.coord

/**
 * Equatorial coordinates of a celestial object.
 *
 * @property raDeg Right ascension in decimal degrees (`0°..360°`).
 * @property decDeg Declination in decimal degrees (`-90°..+90°`).
 */
data class Equatorial(
    val raDeg: Double,
    val decDeg: Double,
)

/**
 * Horizontal coordinates relative to the local horizon.
 *
 * @property azDeg Azimuth in decimal degrees (`0°..360°`, clockwise from North).
 * @property altDeg Altitude in decimal degrees (`-90°..+90°`).
 */
data class Horizontal(
    val azDeg: Double,
    val altDeg: Double,
)

/**
 * Geographic location of the observer.
 *
 * @property latDeg Latitude in decimal degrees (`-90°..+90°`, positive northward).
 * @property lonDeg Longitude in decimal degrees (`-180°..+180°`, positive eastward).
 */
data class GeoPoint(
    val latDeg: Double,
    val lonDeg: Double,
)

/**
 * Local apparent sidereal time measured at the observer's meridian.
 *
 * @property lstDeg Sidereal time in decimal degrees (`0°..360°`).
 */
data class Sidereal(
    val lstDeg: Double,
)
