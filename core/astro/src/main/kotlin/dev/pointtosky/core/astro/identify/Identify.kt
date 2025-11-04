package dev.pointtosky.core.astro.identify

import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

public data class SkyObject(
    val id: String,
    val name: String?,
    val eq: Equatorial,
    val mag: Double? = null,
    val type: Type,
)

public enum class Type {
    STAR,
    PLANET,
    MOON,
    CONSTELLATION,
}

public interface SkyCatalog {
    public fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double? = null): List<SkyObject>
}

public interface ConstellationBoundaries {
    /**
     * @return IAU constellation code or `null` if the point is outside of known boundaries.
     */
    public fun findByEq(eq: Equatorial): String?
}

public sealed interface SkyObjectOrConstellation {
    public data class Object(val obj: SkyObject) : SkyObjectOrConstellation
    public data class Constellation(val iauCode: String) : SkyObjectOrConstellation
}

public class IdentifySolver(
    private val catalog: SkyCatalog,
    private val constellations: ConstellationBoundaries,
) {
    private val brightnessReferenceMag: Double = 6.5
    private val brightnessWeight: Double = 0.5

    public fun findBest(
        center: Equatorial,
        searchRadiusDeg: Double = 5.0,
        magLimit: Double = 5.5,
    ): SkyObjectOrConstellation {
        val candidates = catalog.nearby(center, searchRadiusDeg, magLimit)
        val bestObject = candidates.minByOrNull { candidate ->
            val separation = angularSeparationDeg(center, candidate.eq)
            val magnitudeBoost = candidate.mag?.let { brightnessReferenceMag - it } ?: 0.0
            separation - brightnessWeight * magnitudeBoost
        }

        return if (bestObject != null) {
            SkyObjectOrConstellation.Object(bestObject)
        } else {
            val iauCode = constellations.findByEq(center) ?: "UNKNOWN"
            SkyObjectOrConstellation.Constellation(iauCode)
        }
    }
}

public fun angularSeparationDeg(eq1: Equatorial, eq2: Equatorial): Double {
    val ra1 = eq1.raDeg.toRadians()
    val ra2 = eq2.raDeg.toRadians()
    val dec1 = eq1.decDeg.toRadians()
    val dec2 = eq2.decDeg.toRadians()

    val cosine = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
    val clamped = cosine.coerceIn(-1.0, 1.0)
    return acos(clamped).toDegrees()
}

private fun Double.toRadians(): Double = Math.toRadians(this)
private fun Double.toDegrees(): Double = Math.toDegrees(this)
