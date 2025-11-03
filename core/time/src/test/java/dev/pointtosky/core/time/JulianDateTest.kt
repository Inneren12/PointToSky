package dev.pointtosky.core.time

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JulianDateTest {

    @Test
    fun `instant to julian day matches known reference`() {
        val instant = Instant.parse("1987-04-10T19:21:00Z")
        val julianDay = instantToJulianDay(instant)
        assertEquals(2446896.30625, julianDay, 1e-6)
    }

    @Test
    fun `instant to julian centuries matches reference`() {
        val instant = Instant.parse("1987-04-10T19:21:00Z")
        val centuries = instantToJulianCenturies(instant)
        assertEquals(-0.1272742984, centuries, 1e-10)
    }

    @Test
    fun `j2000 noon is zero centuries`() {
        val instant = Instant.parse("2000-01-01T12:00:00Z")
        val julianDay = instantToJulianDay(instant)
        assertEquals(2451545.0, julianDay, 1e-6)
        val centuries = instantToJulianCenturies(instant)
        assertEquals(0.0, centuries, 1e-12)
    }
}
