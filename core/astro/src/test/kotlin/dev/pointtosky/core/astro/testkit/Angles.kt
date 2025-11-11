package dev.pointtosky.core.astro.testkit

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.toDegrees
import kotlin.math.toRadians

public object Angles {
    /**
     * Computes the great-circle distance between two equatorial coordinates expressed in degrees.
     * Uses the haversine formulation for better numerical stability on very small separations.
     */
    public fun angularSeparationDeg(ra1Deg: Double, dec1Deg: Double, ra2Deg: Double, dec2Deg: Double): Double {
        val ra1Rad = ra1Deg.toRadians()
        val dec1Rad = dec1Deg.toRadians()
        val ra2Rad = ra2Deg.toRadians()
        val dec2Rad = dec2Deg.toRadians()

        val sinDeltaDecHalf = sin((dec2Rad - dec1Rad) / 2.0)
        val sinDeltaRaHalf = sin((ra2Rad - ra1Rad) / 2.0)

        val a = sinDeltaDecHalf * sinDeltaDecHalf + cos(dec1Rad) * cos(dec2Rad) * sinDeltaRaHalf * sinDeltaRaHalf
        val c = 2.0 * asin(min(1.0, sqrt(a)))

        return c.toDegrees()
    }
}
