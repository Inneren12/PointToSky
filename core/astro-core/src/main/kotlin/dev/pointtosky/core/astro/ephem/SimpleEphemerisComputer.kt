package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import dev.pointtosky.core.astro.time.instantToJulianDay
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Body {
    SUN,
    MOON,
    JUPITER,
    SATURN,
}

data class Ephemeris(
    val eq: Equatorial,
    val distanceAu: Double? = null,
    val phase: Double? = null,
)

interface EphemerisComputer {
    fun compute(body: Body, instant: Instant): Ephemeris
}

class SimpleEphemerisComputer : EphemerisComputer {

    override fun compute(body: Body, instant: Instant): Ephemeris {
        val jd = instantToJulianDay(instant)
        val d = jd - JD_AT_2000_01_01_00UT
        val eclDeg = meanObliquityDegrees(d)
        val eclRad = degToRad(eclDeg)

        val sun = computeSun(d, eclRad)
        val jupiterMeanAnomalyDeg = wrapDeg0To360(
            JUPITER_ELEMENTS.meanAnomaly + JUPITER_ELEMENTS.meanAnomalyRate * d,
        )
        val saturnMeanAnomalyDeg = wrapDeg0To360(
            SATURN_ELEMENTS.meanAnomaly + SATURN_ELEMENTS.meanAnomalyRate * d,
        )

        return when (body) {
            Body.SUN -> Ephemeris(sun.equatorial, sun.distanceAu)
            Body.MOON -> computeMoon(d, eclRad, sun)
            Body.JUPITER -> computePlanet(
                d,
                eclRad,
                sun,
                JUPITER_ELEMENTS,
                jupiterMeanAnomalyDeg,
                saturnMeanAnomalyDeg,
                ::applyJupiterPerturbations,
            )
            Body.SATURN -> computePlanet(
                d,
                eclRad,
                sun,
                SATURN_ELEMENTS,
                jupiterMeanAnomalyDeg,
                saturnMeanAnomalyDeg,
                ::applySaturnPerturbations,
            )
        }
    }

    private fun computeMoon(d: Double, eclRad: Double, sun: SunContext): Ephemeris {
        val nDeg = wrapDeg0To360(125.1228 - 0.0529538083 * d)
        val iDeg = 5.1454
        val wDeg = wrapDeg0To360(318.0634 + 0.1643573223 * d)
        val aEarthRadii = 60.2666
        val e = 0.0549
        val mDeg = wrapDeg0To360(115.3654 + 13.0649929509 * d)

        val nRad = degToRad(nDeg)
        val iRad = degToRad(iDeg)
        val wRad = degToRad(wDeg)
        val mRad = degToRad(mDeg)

        var eccentricAnomaly = mRad + e * sin(mRad) * (1.0 + e * cos(mRad))
        var delta: Double
        do {
            delta = (eccentricAnomaly - e * sin(eccentricAnomaly) - mRad) / (1.0 - e * cos(eccentricAnomaly))
            eccentricAnomaly -= delta
        } while (abs(delta) > 1e-10)

        val xv = aEarthRadii * (cos(eccentricAnomaly) - e)
        val yv = aEarthRadii * sqrt(1.0 - e * e) * sin(eccentricAnomaly)
        val v = atan2(yv, xv)
        val rEarthRadii = sqrt(xv * xv + yv * yv)

        val vPlusW = v + wRad
        val sinVPlusW = sin(vPlusW)
        val cosVPlusW = cos(vPlusW)
        val sinN = sin(nRad)
        val cosN = cos(nRad)

        val xh = rEarthRadii * (cosN * cosVPlusW - sinN * sinVPlusW * cos(iRad))
        val yh = rEarthRadii * (sinN * cosVPlusW + cosN * sinVPlusW * cos(iRad))
        val zh = rEarthRadii * sinVPlusW * sin(iRad)

        var loneclDeg = wrapDeg0To360(radToDeg(atan2(yh, xh)))
        var lateclDeg = radToDeg(atan2(zh, sqrt(xh * xh + yh * yh)))
        var distanceEarthRadii = rEarthRadii

        val msDeg = sun.meanAnomalyDeg
        val lsDeg = wrapDeg0To360(msDeg + sun.wDeg)
        val lmDeg = wrapDeg0To360(mDeg + wDeg + nDeg)
        val dDeg = wrapDeg0To360(lmDeg - lsDeg)
        val fDeg = wrapDeg0To360(lmDeg - nDeg)

        val mRadSun = degToRad(msDeg)
        val dRad = degToRad(dDeg)
        val fRad = degToRad(fDeg)
        val mRadMoon = degToRad(mDeg)

        loneclDeg += (-1.274) * sin(mRadMoon - 2.0 * dRad)
        loneclDeg += 0.658 * sin(2.0 * dRad)
        loneclDeg += (-0.186) * sin(mRadSun)
        loneclDeg += (-0.059) * sin(2.0 * mRadMoon - 2.0 * dRad)
        loneclDeg += (-0.057) * sin(mRadMoon - 2.0 * dRad + mRadSun)
        loneclDeg += 0.053 * sin(mRadMoon + 2.0 * dRad)
        loneclDeg += 0.046 * sin(2.0 * dRad - mRadSun)
        loneclDeg += 0.041 * sin(mRadMoon - mRadSun)
        loneclDeg += (-0.035) * sin(dRad)
        loneclDeg += (-0.031) * sin(mRadMoon + mRadSun)
        loneclDeg += (-0.015) * sin(2.0 * fRad - 2.0 * dRad)
        loneclDeg += 0.011 * sin(mRadMoon - 4.0 * dRad)
        loneclDeg = wrapDeg0To360(loneclDeg)

        lateclDeg += (-0.173) * sin(fRad - 2.0 * dRad)
        lateclDeg += (-0.055) * sin(mRadMoon - fRad - 2.0 * dRad)
        lateclDeg += (-0.046) * sin(mRadMoon + fRad - 2.0 * dRad)
        lateclDeg += 0.033 * sin(fRad + 2.0 * dRad)
        lateclDeg += 0.017 * sin(2.0 * mRadMoon + fRad)

        distanceEarthRadii += (-0.58) * cos(mRadMoon - 2.0 * dRad)
        distanceEarthRadii += (-0.46) * cos(2.0 * dRad)

        val lonRad = degToRad(loneclDeg)
        val latRad = degToRad(lateclDeg)
        val cosLat = cos(latRad)
        val xg = distanceEarthRadii * cos(lonRad) * cosLat
        val yg = distanceEarthRadii * sin(lonRad) * cosLat
        val zg = distanceEarthRadii * sin(latRad)

        val xe = xg
        val ye = yg * cos(eclRad) - zg * sin(eclRad)
        val ze = yg * sin(eclRad) + zg * cos(eclRad)

        val raDeg = wrapDeg0To360(radToDeg(atan2(ye, xe)))
        val decDeg = radToDeg(atan2(ze, sqrt(xe * xe + ye * ye)))

        val distanceAu = distanceEarthRadii * EARTH_RADIUS_AU
        val elongation = degToRad(wrapDeg0To360(loneclDeg - sun.eclipticLongitudeDeg))
        val phase = 0.5 * (1.0 - cos(elongation))

        return Ephemeris(
            eq = Equatorial(raDeg, decDeg),
            distanceAu = distanceAu,
            phase = phase.coerceIn(0.0, 1.0),
        )
    }

    private fun computePlanet(
        d: Double,
        eclRad: Double,
        sun: SunContext,
        elements: PlanetElements,
        jupiterMeanAnomalyDeg: Double,
        saturnMeanAnomalyDeg: Double,
        perturbations: (PlanetPosition, Double, Double, Double) -> PlanetPosition,
    ): Ephemeris {
        val nDeg = wrapDeg0To360(elements.node + elements.nodeRate * d)
        val iDeg = elements.inclination + elements.inclinationRate * d
        val wDeg = wrapDeg0To360(elements.perihelion + elements.perihelionRate * d)
        val a = elements.semiMajorAxis
        val e = elements.eccentricity + elements.eccentricityRate * d
        val mDeg = wrapDeg0To360(elements.meanAnomaly + elements.meanAnomalyRate * d)

        val nRad = degToRad(nDeg)
        val iRad = degToRad(iDeg)
        val wRad = degToRad(wDeg)
        val mRad = degToRad(mDeg)

        var eccentricAnomaly = mRad + e * sin(mRad) * (1.0 + e * cos(mRad))
        var delta: Double
        do {
            delta = (eccentricAnomaly - e * sin(eccentricAnomaly) - mRad) / (1.0 - e * cos(eccentricAnomaly))
            eccentricAnomaly -= delta
        } while (abs(delta) > 1e-12)

        val xv = a * (cos(eccentricAnomaly) - e)
        val yv = a * sqrt(1.0 - e * e) * sin(eccentricAnomaly)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)

        val vPlusW = v + wRad
        val sinVPlusW = sin(vPlusW)
        val cosVPlusW = cos(vPlusW)
        val sinN = sin(nRad)
        val cosN = cos(nRad)

        val xh = r * (cosN * cosVPlusW - sinN * sinVPlusW * cos(iRad))
        val yh = r * (sinN * cosVPlusW + cosN * sinVPlusW * cos(iRad))
        val zh = r * (sinVPlusW * sin(iRad))

        var loneclDeg = wrapDeg0To360(radToDeg(atan2(yh, xh)))
        var lateclDeg = radToDeg(atan2(zh, sqrt(xh * xh + yh * yh)))
        var radius = r

        val corrected = perturbations(
            PlanetPosition(radius, loneclDeg, lateclDeg),
            mDeg,
            jupiterMeanAnomalyDeg,
            saturnMeanAnomalyDeg,
        )
        loneclDeg = corrected.longitudeDeg
        lateclDeg = corrected.latitudeDeg
        radius = corrected.radiusAu

        val lonRad = degToRad(loneclDeg)
        val latRad = degToRad(lateclDeg)
        val cosLat = cos(latRad)
        val xhHelio = radius * cos(lonRad) * cosLat
        val yhHelio = radius * sin(lonRad) * cosLat
        val zhHelio = radius * sin(latRad)

        val xs = sun.distanceAu * cos(sun.eclipticLongitudeRad)
        val ys = sun.distanceAu * sin(sun.eclipticLongitudeRad)

        val xg = xhHelio + xs
        val yg = yhHelio + ys
        val zg = zhHelio

        val xe = xg
        val ye = yg * cos(eclRad) - zg * sin(eclRad)
        val ze = yg * sin(eclRad) + zg * cos(eclRad)

        val raDeg = wrapDeg0To360(radToDeg(atan2(ye, xe)))
        val decDeg = radToDeg(atan2(ze, sqrt(xe * xe + ye * ye)))
        val distanceAu = sqrt(xg * xg + yg * yg + zg * zg)

        return Ephemeris(eq = Equatorial(raDeg, decDeg), distanceAu = distanceAu)
    }

    private fun computeSun(d: Double, eclRad: Double): SunContext {
        val wDeg = 282.9404 + 4.70935e-5 * d
        val e = 0.016709 - 1.151e-9 * d
        val mDeg = wrapDeg0To360(356.0470 + 0.9856002585 * d)
        val mRad = degToRad(mDeg)

        var eccentricAnomaly = mRad + e * sin(mRad) * (1.0 + e * cos(mRad))
        var delta: Double
        do {
            delta = (eccentricAnomaly - e * sin(eccentricAnomaly) - mRad) / (1.0 - e * cos(eccentricAnomaly))
            eccentricAnomaly -= delta
        } while (abs(delta) > 1e-12)

        val xv = cos(eccentricAnomaly) - e
        val yv = sqrt(1.0 - e * e) * sin(eccentricAnomaly)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val lonRad = v + degToRad(wDeg)
        val lonDeg = wrapDeg0To360(radToDeg(lonRad))

        val xs = r * cos(lonRad)
        val ys = r * sin(lonRad)
        val xe = xs
        val ye = ys * cos(eclRad)
        val ze = ys * sin(eclRad)

        val raDeg = wrapDeg0To360(radToDeg(atan2(ye, xe)))
        val decDeg = radToDeg(atan2(ze, sqrt(xe * xe + ye * ye)))

        return SunContext(
            equatorial = Equatorial(raDeg, decDeg),
            distanceAu = r,
            eclipticLongitudeDeg = lonDeg,
            eclipticLongitudeRad = lonRad,
            meanAnomalyDeg = mDeg,
            wDeg = wDeg,
        )
    }

    private fun meanObliquityDegrees(d: Double): Double {
        val t = d / 36525.0
        // Meeus-style approximation. TODO: upgrade to IAU-2006 precession in v1.
        return 23.439291 - 0.0130042 * t - 1.64e-7 * t * t + 5.04e-7 * t * t * t
    }

    private fun applyJupiterPerturbations(
        position: PlanetPosition,
        selfMeanAnomalyDeg: Double,
        @Suppress("UNUSED_PARAMETER")
        jupiterMeanAnomalyDeg: Double,
        saturnMeanAnomalyDeg: Double,
    ): PlanetPosition {
        val mj = degToRad(selfMeanAnomalyDeg)
        val ms = degToRad(saturnMeanAnomalyDeg)
        var lon = position.longitudeDeg
        lon += (-0.332) * sin(2.0 * mj - 5.0 * ms - PERT_DEG_67_6)
        lon += (-0.056) * sin(2.0 * mj - 2.0 * ms + PERT_DEG_21)
        lon += 0.042 * sin(3.0 * mj - 5.0 * ms + PERT_DEG_21)
        lon += (-0.036) * sin(mj - 2.0 * ms)
        lon += 0.022 * cos(mj - ms)
        lon += 0.023 * sin(2.0 * mj - 3.0 * ms + PERT_DEG_52)
        lon += (-0.016) * sin(mj - 5.0 * ms - PERT_DEG_69)
        return PlanetPosition(position.radiusAu, wrapDeg0To360(lon), position.latitudeDeg)
    }

    private fun applySaturnPerturbations(
        position: PlanetPosition,
        selfMeanAnomalyDeg: Double,
        jupiterMeanAnomalyDeg: Double,
        @Suppress("UNUSED_PARAMETER")
        saturnMeanAnomalyDeg: Double,
    ): PlanetPosition {
        val mj = degToRad(jupiterMeanAnomalyDeg)
        val ms = degToRad(selfMeanAnomalyDeg)
        var lon = position.longitudeDeg
        lon += 0.812 * sin(2.0 * mj - 5.0 * ms - PERT_DEG_67_6)
        lon += (-0.229) * cos(2.0 * mj - 4.0 * ms - PERT_DEG_2)
        lon += 0.119 * sin(mj - 2.0 * ms - PERT_DEG_3)
        lon += 0.046 * sin(2.0 * mj - 6.0 * ms - PERT_DEG_69)
        lon += 0.014 * sin(mj - 3.0 * ms + PERT_DEG_32)

        var lat = position.latitudeDeg
        lat += (-0.020) * cos(2.0 * mj - 4.0 * ms - PERT_DEG_2)
        lat += 0.018 * sin(2.0 * mj - 6.0 * ms - PERT_DEG_49)
        return PlanetPosition(position.radiusAu, wrapDeg0To360(lon), lat)
    }

    private data class SunContext(
        val equatorial: Equatorial,
        val distanceAu: Double,
        val eclipticLongitudeDeg: Double,
        val eclipticLongitudeRad: Double,
        val meanAnomalyDeg: Double,
        val wDeg: Double,
    )

    private data class PlanetElements(
        val node: Double,
        val nodeRate: Double,
        val inclination: Double,
        val inclinationRate: Double,
        val perihelion: Double,
        val perihelionRate: Double,
        val semiMajorAxis: Double,
        val eccentricity: Double,
        val eccentricityRate: Double,
        val meanAnomaly: Double,
        val meanAnomalyRate: Double,
    )

    private data class PlanetPosition(
        val radiusAu: Double,
        val longitudeDeg: Double,
        val latitudeDeg: Double,
    )

    private companion object {
        private const val JD_AT_2000_01_01_00UT = 2451544.5
        private const val EARTH_RADIUS_AU = 0.0000426349653318 // 6378.14 km / AU

        // TODO: replace with high-precision solar theory (VSOP87/ELP/IAU-2006/SOFA) in v1.

        private val JUPITER_ELEMENTS = PlanetElements(
            node = 100.4542,
            nodeRate = 2.76854e-5,
            inclination = 1.3030,
            inclinationRate = -1.557e-7,
            perihelion = 273.8777,
            perihelionRate = 1.64505e-5,
            semiMajorAxis = 5.20256,
            eccentricity = 0.048498,
            eccentricityRate = 4.469e-9,
            meanAnomaly = 19.8950,
            meanAnomalyRate = 0.0830853001,
        )

        private val SATURN_ELEMENTS = PlanetElements(
            node = 113.6634,
            nodeRate = 2.38980e-5,
            inclination = 2.4886,
            inclinationRate = -1.081e-7,
            perihelion = 339.3939,
            perihelionRate = 2.97661e-5,
            semiMajorAxis = 9.55475,
            eccentricity = 0.055546,
            eccentricityRate = -9.499e-9,
            meanAnomaly = 316.9670,
            meanAnomalyRate = 0.0334442282,
        )

        private val PERT_DEG_67_6 = degToRad(67.6)
        private val PERT_DEG_21 = degToRad(21.0)
        private val PERT_DEG_52 = degToRad(52.0)
        private val PERT_DEG_69 = degToRad(69.0)
        private val PERT_DEG_2 = degToRad(2.0)
        private val PERT_DEG_3 = degToRad(3.0)
        private val PERT_DEG_32 = degToRad(32.0)
        private val PERT_DEG_49 = degToRad(49.0)
    }
}
