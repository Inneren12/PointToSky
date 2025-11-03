package dev.pointtosky.core.astro.coord

/**
 * Equatorial coordinates in degrees.
 *
 * @property raDeg Right Ascension in degrees within the [0°, 360°) range.
 * @property decDeg Declination in degrees within the [-90°, +90°] range.
 */
data class Equatorial(
    val raDeg: Double,
    val decDeg: Double,
)

/**
 * Horizontal (alt-az) coordinates in degrees.
 *
 * @property azDeg Azimuth measured eastward from geographic north in degrees within the [0°, 360°) range.
 * @property altDeg Altitude above the horizon in degrees within the [-90°, +90°] range.
 */
data class Horizontal(
    val azDeg: Double,
    val altDeg: Double,
)

/**
 * Geographic coordinates in degrees.
 *
 * @property latDeg Geodetic latitude in degrees (+N) within the [-90°, +90°] range.
 * @property lonDeg Longitude in degrees (+E) within the [-180°, +180°] range.
 */
data class GeoPoint(
    val latDeg: Double,
    val lonDeg: Double,
)

/**
 * Local sidereal time in degrees.
 *
 * @property lstDeg Angle representing the local sidereal time within the [0°, 360°) range.
 */
data class Sidereal(
    val lstDeg: Double,
)
