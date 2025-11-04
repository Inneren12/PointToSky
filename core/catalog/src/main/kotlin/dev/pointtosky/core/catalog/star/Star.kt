package dev.pointtosky.core.catalog.star

/**
 * Simplified representation of a bright star used by the in-app catalog.
 *
 * @property id Numeric identifier matching upstream catalog indices.
 * @property raDeg Right ascension in decimal degrees (J2000).
 * @property decDeg Declination in decimal degrees (J2000).
 * @property mag Apparent magnitude in the V band.
 * @property name Common name if one exists.
 * @property bayer Bayer designation (e.g. "α CMa") if present.
 * @property flamsteed Flamsteed designation (e.g. "13 Ori") if present.
 * @property constellation IAU constellation code (three letters) if known.
 */
public data class Star(
    val id: Int,
    val raDeg: Float,
    val decDeg: Float,
    val mag: Float,
    val name: String?,
    val bayer: String?,
    val flamsteed: String?,
    val constellation: String?,
)
