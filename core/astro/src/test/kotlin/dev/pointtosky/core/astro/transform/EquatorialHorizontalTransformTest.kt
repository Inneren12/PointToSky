package dev.pointtosky.core.astro.transform

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.units.wrapDeg0To360
import dev.pointtosky.core.astro.units.wrapDegMinus180To180
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ANGLE_TOLERANCE = 1e-6

private fun deltaAngleDeg(expected: Double, actual: Double): Double {
    val diff = wrapDegMinus180To180(actual - expected)
    return abs(diff)
}

class EquatorialHorizontalTransformTest {

    @Test
    fun roundTripWithoutRefractionIsAccurate() {
        val lstDeg = 200.0
        val latitudes = listOf(-60.0, 0.0, 60.0)
        val declinations = listOf(-60.0, 0.0, 60.0)
        val hourAngles = (-150..150 step 30).map { it.toDouble() }

        for (lat in latitudes) {
            for (dec in declinations) {
                for (tau in hourAngles) {
                    val ra = wrapDeg0To360(lstDeg - tau)
                    val equatorial = Equatorial(raDeg = ra, decDeg = dec)

                    val horizontal = raDecToAltAz(
                        eq = equatorial,
                        lstDeg = lstDeg,
                        latDeg = lat,
                        applyRefraction = false,
                    )

                    val equatorialBack = altAzToRaDec(
                        hor = horizontal,
                        lstDeg = lstDeg,
                        latDeg = lat,
                    )

                    val horizontalBack = raDecToAltAz(
                        eq = equatorialBack,
                        lstDeg = lstDeg,
                        latDeg = lat,
                        applyRefraction = false,
                    )

                    assertTrue(
                        deltaAngleDeg(equatorial.raDeg, equatorialBack.raDeg) <= ANGLE_TOLERANCE,
                        "RA mismatch for lat=$lat dec=$dec tau=$tau: expected ${equatorial.raDeg}, got ${equatorialBack.raDeg}",
                    )
                    assertTrue(
                        abs(equatorial.decDeg - equatorialBack.decDeg) <= ANGLE_TOLERANCE,
                        "Dec mismatch for lat=$lat dec=$dec tau=$tau: expected ${equatorial.decDeg}, got ${equatorialBack.decDeg}",
                    )
                    assertTrue(
                        deltaAngleDeg(horizontal.azDeg, horizontalBack.azDeg) <= ANGLE_TOLERANCE,
                        "Az mismatch for lat=$lat dec=$dec tau=$tau: expected ${horizontal.azDeg}, got ${horizontalBack.azDeg}",
                    )
                    assertTrue(
                        abs(horizontal.altDeg - horizontalBack.altDeg) <= ANGLE_TOLERANCE,
                        "Alt mismatch for lat=$lat dec=$dec tau=$tau: expected ${horizontal.altDeg}, got ${horizontalBack.altDeg}",
                    )
                }
            }
        }
    }

    @Test
    fun latitudeMatchesDeclinationGivesZenith() {
        val lstDeg = 45.0
        val latitudes = listOf(-60.0, -30.0, 0.0, 30.0, 60.0)

        for (lat in latitudes) {
            val equatorial = Equatorial(raDeg = lstDeg, decDeg = lat)
            val horizontal = raDecToAltAz(
                eq = equatorial,
                lstDeg = lstDeg,
                latDeg = lat,
                applyRefraction = false,
            )
            assertEquals(90.0, horizontal.altDeg, 1e-9, "Altitude should be ~90° at zenith for lat=$lat")
        }
    }

    @Test
    fun hourAngleNinetyDegreesIsOnHorizon() {
        val lstDeg = 120.0
        val latDeg = 0.0
        val taus = listOf(-90.0, 90.0)

        for (tau in taus) {
            val ra = wrapDeg0To360(lstDeg - tau)
            val equatorial = Equatorial(raDeg = ra, decDeg = 0.0)
            val horizontal = raDecToAltAz(
                eq = equatorial,
                lstDeg = lstDeg,
                latDeg = latDeg,
                applyRefraction = false,
            )
            assertEquals(0.0, horizontal.altDeg, 1e-9, "Altitude should be ~0° on horizon for tau=$tau")
        }
    }

    @Test
    fun saemundssonRefractionRaisesAltitudeAroundTenDegrees() {
        val lstDeg = 10.0
        val latDeg = 45.0
        val targetHorizontal = Horizontal(azDeg = 45.0, altDeg = 10.0)

        val equatorial = altAzToRaDec(
            hor = targetHorizontal,
            lstDeg = lstDeg,
            latDeg = latDeg,
        )

        val horizontalWithoutRefraction = raDecToAltAz(
            eq = equatorial,
            lstDeg = lstDeg,
            latDeg = latDeg,
            applyRefraction = false,
        )
        val horizontalWithRefraction = raDecToAltAz(
            eq = equatorial,
            lstDeg = lstDeg,
            latDeg = latDeg,
            applyRefraction = true,
        )

        val delta = horizontalWithRefraction.altDeg - horizontalWithoutRefraction.altDeg
        assertTrue(delta in 0.1..0.2, "Refraction correction should be between 0.1° and 0.2°, was $delta")
    }
}
