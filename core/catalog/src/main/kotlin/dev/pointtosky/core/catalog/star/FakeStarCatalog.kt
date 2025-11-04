package dev.pointtosky.core.catalog.star

import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal, hand-authored list of bright stars for demos and tests.
 */
public class FakeStarCatalog : StarCatalog {
    private data class Candidate(val star: Star, val separationDeg: Double)

    private val stars: List<Star> = listOf(
        Star(1, 101.287f, -16.716f, -1.46f, "Sirius", "α CMa", "9 CMa", "CMA"),
        Star(2, 95.987f, -52.695f, -0.74f, "Canopus", "α Car", null, "CAR"),
        Star(3, 213.915f, 19.182f, -0.05f, "Arcturus", "α Boo", "16 Boo", "BOO"),
        Star(4, 219.902f, -60.835f, -0.27f, "Alpha Centauri", "α Cen", null, "CEN"),
        Star(5, 279.234f, 38.783f, 0.03f, "Vega", "α Lyr", "3 Lyr", "LYR"),
        Star(6, 79.172f, 45.997f, 0.08f, "Capella", "α Aur", "13 Aur", "AUR"),
        Star(7, 78.634f, -8.205f, 0.18f, "Rigel", "β Ori", "19 Ori", "ORI"),
        Star(8, 88.793f, 7.407f, 0.42f, "Betelgeuse", "α Ori", "58 Ori", "ORI"),
        Star(9, 114.825f, 5.225f, 0.38f, "Procyon", "α CMi", "10 CMi", "CMI"),
        Star(10, 24.428f, -57.236f, 0.46f, "Achernar", "α Eri", null, "ERI"),
        Star(11, 210.955f, -60.373f, 0.61f, "Hadar", "β Cen", null, "CEN"),
        Star(12, 297.695f, 8.868f, 0.77f, "Altair", "α Aql", "53 Aql", "AQL"),
        Star(13, 68.980f, 16.509f, 0.87f, "Aldebaran", "α Tau", "87 Tau", "TAU"),
        Star(14, 247.351f, -26.432f, 1.06f, "Antares", "α Sco", "21 Sco", "SCO"),
        Star(15, 201.298f, -11.161f, 0.98f, "Spica", "α Vir", "67 Vir", "VIR"),
        Star(16, 116.329f, 28.026f, 1.14f, "Pollux", "β Gem", "78 Gem", "GEM"),
        Star(17, 344.412f, -29.622f, 1.16f, "Fomalhaut", "α PsA", "24 PsA", "PSA"),
        Star(18, 310.358f, 45.280f, 1.25f, "Deneb", "α Cyg", "50 Cyg", "CYG"),
        Star(19, 152.093f, 11.967f, 1.35f, "Regulus", "α Leo", "32 Leo", "LEO"),
        Star(20, 37.954f, 89.264f, 1.97f, "Polaris", "α UMi", "1 UMi", "UMI"),
    )

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> {
        return stars.asSequence()
            .map { star ->
                val separation = angularSeparationDeg(center, Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()))
                Candidate(star, separation)
            }
            .filter { candidate ->
                candidate.separationDeg <= radiusDeg &&
                    (magLimit == null || candidate.star.mag.toDouble() <= magLimit)
            }
            .sortedBy { it.separationDeg }
            .map { it.star }
            .toList()
    }

    private fun angularSeparationDeg(eq1: Equatorial, eq2: Equatorial): Double {
        val ra1 = Math.toRadians(eq1.raDeg)
        val ra2 = Math.toRadians(eq2.raDeg)
        val dec1 = Math.toRadians(eq1.decDeg)
        val dec2 = Math.toRadians(eq2.decDeg)

        val cosine = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
        val clamped = cosine.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clamped))
    }
}
