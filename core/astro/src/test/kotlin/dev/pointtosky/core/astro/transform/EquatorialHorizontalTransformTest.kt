package dev.pointtosky.core.astro.transform

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.units.wrapDeg0_360
import dev.pointtosky.core.astro.units.wrapDegN180_180
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class EquatorialHorizontalTransformTest {

    @Test
    fun `equatorial to horizontal round trip stability`() {
        val latitudes = listOf(-60.0, 0.0, 60.0)
        val declinations = listOf(-60.0, 0.0, 60.0)
        val taus = (-150..150 step 30).map { it.toDouble() }
        val lstDeg = 123.0

        for (lat in latitudes) {
            for (dec in declinations) {
                for (tau in taus) {
                    val ra = wrapDeg0_360(lstDeg - tau)
                    val eq = Equatorial(raDeg = ra, decDeg = dec)
                    val horizontal = raDecToAltAz(eq, lstDeg, lat, applyRefraction = false)
                    val eqBack = altAzToRaDec(horizontal, lstDeg, lat)
                    val horizontalBack = raDecToAltAz(eqBack, lstDeg, lat, applyRefraction = false)

                    val raDiff = abs(wrapDegN180_180(eq.raDeg - eqBack.raDeg))
                    val decDiff = abs(eq.decDeg - eqBack.decDeg)
                    val altDiff = abs(horizontal.altDeg - horizontalBack.altDeg)
                    val azDiff = abs(wrapDegN180_180(horizontal.azDeg - horizontalBack.azDeg))

                    assertTrue(raDiff <= 1e-6, "RA mismatch $raDiff for lat=$lat dec=$dec tau=$tau")
                    assertTrue(decDiff <= 1e-6, "Dec mismatch $decDiff for lat=$lat dec=$dec tau=$tau")
                    assertTrue(altDiff <= 1e-6, "Alt mismatch $altDiff for lat=$lat dec=$dec tau=$tau")
                    assertTrue(azDiff <= 1e-6, "Az mismatch $azDiff for lat=$lat dec=$dec tau=$tau")
                }
            }
        }
    }

    @Test
    fun `meridian transit hits zenith`() {
        val latDeg = 45.0
        val decDeg = latDeg
        val lstDeg = 200.0
        val raDeg = lstDeg

        val horizontal = raDecToAltAz(Equatorial(raDeg, decDeg), lstDeg, latDeg, applyRefraction = false)
        assertTrue(horizontal.altDeg > 89.999, "Altitude not near zenith: ${horizontal.altDeg}")
    }

    @Test
    fun `hour angle ninety gives horizon`() {
        val latDeg = 0.0
        val decDeg = 0.0
        val lstDeg = 50.0
        val tauDeg = 90.0
        val raDeg = wrapDeg0_360(lstDeg - tauDeg)

        val horizontal = raDecToAltAz(Equatorial(raDeg, decDeg), lstDeg, latDeg, applyRefraction = false)
        assertTrue(abs(horizontal.altDeg) < 1e-6, "Altitude not on horizon: ${horizontal.altDeg}")
    }

    @Test
    fun `saemundsson refraction adds expected offset`() {
        val lstDeg = 80.0
        val latDeg = 52.0
        val target = Horizontal(azDeg = 180.0, altDeg = 10.0)
        val equatorial = altAzToRaDec(target, lstDeg, latDeg)

        val withoutRef = raDecToAltAz(equatorial, lstDeg, latDeg, applyRefraction = false)
        val withRef = raDecToAltAz(equatorial, lstDeg, latDeg, applyRefraction = true)

        val delta = withRef.altDeg - withoutRef.altDeg
        assertTrue(delta in 0.1..0.2, "Refraction delta $delta not in expected range")
    }
}
