package dev.pointtosky.core.astro.coord

/** Equatorial coordinates (degrees). */
data class Equatorial(val raDeg: Double, val decDeg: Double)

/** Horizontal (alt-az) coordinates (degrees). */
data class Horizontal(val azDeg: Double, val altDeg: Double)

/** Geographic coordinates (degrees). */
data class GeoPoint(val latDeg: Double, val lonDeg: Double)

/** Local sidereal time (degrees). */
data class Sidereal(val lstDeg: Double)
