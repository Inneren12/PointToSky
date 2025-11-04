package dev.pointtosky.core.catalog.constellation

import dev.pointtosky.core.astro.coord.Equatorial

/**
 * Simplified rectangular boundaries used for demos and tests.
 */
public class FakeConstellationBoundaries : ConstellationBoundaries {
    private data class Box(
        val raMinDeg: Double,
        val raMaxDeg: Double,
        val decMinDeg: Double,
        val decMaxDeg: Double,
        val iauCode: String,
    ) {
        fun contains(eq: Equatorial): Boolean {
            val ra = normalizeRa(eq.raDeg)
            val dec = eq.decDeg
            val normalizedMin = normalizeRa(raMinDeg)
            val normalizedMax = normalizeRa(raMaxDeg)
            val raMatches = if (normalizedMin <= normalizedMax) {
                ra in normalizedMin..normalizedMax
            } else {
                ra >= normalizedMin || ra <= normalizedMax
            }
            val decMatches = dec in decMinDeg..decMaxDeg
            return raMatches && decMatches
        }

        private fun normalizeRa(value: Double): Double {
            var result = value % 360.0
            if (result < 0) {
                result += 360.0
            }
            return result
        }
    }

    private val boxes: List<Box> = listOf(
        Box(70.0, 100.0, -20.0, 30.0, "ORI"),
        Box(90.0, 130.0, -35.0, 5.0, "CMA"),
        Box(270.0, 300.0, 25.0, 50.0, "LYR"),
        Box(285.0, 330.0, 30.0, 55.0, "CYG"),
        Box(330.0, 40.0, -75.0, -25.0, "TUC"),
        Box(140.0, 210.0, 30.0, 75.0, "UMA"),
    )

    override fun findByEq(eq: Equatorial): String? {
        return boxes.firstOrNull { it.contains(eq) }?.iauCode
    }
}
