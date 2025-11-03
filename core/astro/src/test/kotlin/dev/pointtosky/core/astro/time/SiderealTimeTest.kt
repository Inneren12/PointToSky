package dev.pointtosky.core.astro.time

import dev.pointtosky.core.time.instantToJulianDay
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiderealTimeTest {

    @Test
    fun `gmst matches Meeus reference`() {
        val instant = Instant.parse("1987-04-10T19:21:00Z")
        val julianDay = instantToJulianDay(instant)
        val gmst = gmstDeg(julianDay)
        assertEquals(128.73787333333334, gmst, 5e-4)
    }

    @Test
    fun `lst matches fixed reference for New York`() {
        val toleranceDeg = 0.5
        val instant = Instant.parse("2023-09-23T12:00:00Z")
        val lst = lstAt(instant, -73.9857)
        assertEquals(108.0950164767757, lst.lstDeg, toleranceDeg)
    }

    @Test
    fun `lst matches fixed reference for Sydney`() {
        val toleranceDeg = 0.5
        val instant = Instant.parse("2023-03-21T00:00:00Z")
        val lst = lstAt(instant, 151.2093)
        assertEquals(329.4667817338027, lst.lstDeg, toleranceDeg)
    }

    @Test
    fun `lst increases faster than solar time`() {
        val base = Instant.parse("2023-09-23T00:00:00Z")
        val longitude = -73.9857
        val expectedSiderealRateDegPerHour = 15.0410686
        val tolerance = 0.01

        val lstValues = (0..6).map { hour ->
            val instant = base.plusSeconds(hour * 3_600L)
            lstAt(instant, longitude).lstDeg
        }

        lstValues.zipWithNext().forEach { (prev, next) ->
            var delta = next - prev
            if (delta < 0.0) {
                delta += 360.0
            }

            assertTrue(delta > 0.0)
            assertEquals(expectedSiderealRateDegPerHour, delta, tolerance)
        }
    }
}
