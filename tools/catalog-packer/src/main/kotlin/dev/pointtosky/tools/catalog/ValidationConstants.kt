package dev.pointtosky.tools.catalog

/**
 * Validation constants and utilities for catalog packing.
 * These ensure data integrity for star catalogs at pack time.
 */
object ValidationConstants {
    // Right Ascension constraints
    const val RA_MIN_DEG = 0.0
    const val RA_MAX_DEG = 360.0

    // Declination constraints
    const val DEC_MIN_DEG = -90.0
    const val DEC_MAX_DEG = 90.0

    // Magnitude constraints
    // Range chosen to accommodate very bright stars (e.g., Sirius at -1.46)
    // and reasonable faint limit for visual/binocular astronomy
    const val MAG_MIN = -2.0
    const val MAG_MAX = 15.0

    /**
     * Validates a star's coordinates and magnitude.
     * @return Error message if validation fails, null if valid
     */
    fun validateStarInput(raDeg: Double, decDeg: Double, mag: Double): String? {
        if (!raDeg.isFinite()) {
            return "RA is not finite: $raDeg"
        }
        if (!decDeg.isFinite()) {
            return "Dec is not finite: $decDeg"
        }
        if (!mag.isFinite()) {
            return "Magnitude is not finite: $mag"
        }

        // Normalize RA to [0, 360) for validation
        val normalizedRa = raDeg.mod(360.0)
        if (normalizedRa < RA_MIN_DEG || normalizedRa >= RA_MAX_DEG) {
            return "RA out of range [0, 360): $normalizedRa (original: $raDeg)"
        }

        if (decDeg < DEC_MIN_DEG || decDeg > DEC_MAX_DEG) {
            return "Dec out of range [-90, +90]: $decDeg"
        }

        if (mag < MAG_MIN || mag > MAG_MAX) {
            return "Magnitude out of range [$MAG_MIN, $MAG_MAX]: $mag"
        }

        return null
    }

    /**
     * Normalizes RA to [0, 360) range.
     */
    fun normalizeRa(raDeg: Double): Double = raDeg.mod(360.0)
}
