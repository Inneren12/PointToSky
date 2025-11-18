package dev.pointtosky.core.catalog.star

import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class AstroStarCatalogAdapter(
    private val astro: AstroCatalog
) : StarCatalog {

    override fun nearby(
        center: Equatorial,
        radiusDeg: Double,
        magLimit: Double?
    ): List<Star> {
        if (radiusDeg <= 0.0) return emptyList()
        val radius = radiusDeg.coerceAtMost(180.0)

        return astro.allStars()
            .asSequence()
            .filter { s -> magLimit == null || s.magnitude.toDouble() <= magLimit }
            .map { s ->
                val sep = angularSeparationDeg(
                    center,
                    Equatorial(s.rightAscensionDeg.toDouble(), s.declinationDeg.toDouble())
                )
                Pair(s, sep)
            }
            .filter { (_, sep) -> sep <= radius }
            .sortedBy { it.second }
            .map { (s, _) ->
                Star(
                    id = s.id.raw,
                    raDeg = s.rightAscensionDeg,
                    decDeg = s.declinationDeg,
                    mag = s.magnitude,
                    name = s.name,
                    bayer = null,
                    flamsteed = null,
                    constellation = astro.getConstellationMeta(s.constellationId).abbreviation
                )
            }
            .toList()
    }

    private fun angularSeparationDeg(a: Equatorial, b: Equatorial): Double {
        val aRA = Math.toRadians(a.raDeg)
        val aDec = Math.toRadians(a.decDeg)
        val bRA = Math.toRadians(b.raDeg)
        val bDec = Math.toRadians(b.decDeg)
        val cosSep = sin(aDec) * sin(bDec) + cos(aDec) * cos(bDec) * cos(aRA - bRA)
        return Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0)))
    }
}
