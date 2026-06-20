package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class PointingTest {
    /** Builds a forward ENU vector for the given az/alt, scaled by [scale] (1.0 = unit length). */
    private fun forwardFor(azDeg: Double, altDeg: Double, scale: Double = 1.0): Triple<Double, Double, Double> {
        val az = Math.toRadians(azDeg)
        val alt = Math.toRadians(altDeg)
        val cosAlt = cos(alt)
        // Android world ENU frame: x = East, y = North, z = Up.
        return Triple(scale * sin(az) * cosAlt, scale * cos(az) * cosAlt, scale * sin(alt))
    }

    @Test
    fun `forward vector maps to azimuth and altitude`() {
        val (e, n, u) = forwardFor(azDeg = 359.0, altDeg = 30.0)
        val h = forwardVectorToHorizontal(e, n, u)
        assertEquals(359.0, h.azDeg, 1e-6)
        assertEquals(30.0, h.altDeg, 1e-6)
    }

    @Test
    fun `non-normalized vector still yields correct altitude (az=0, alt=30)`() {
        // Length 250 vector pointing due north at 30° elevation.
        val (e, n, u) = forwardFor(azDeg = 0.0, altDeg = 30.0, scale = 250.0)
        val h = forwardVectorToHorizontal(e, n, u)
        assertEquals(0.0, h.azDeg, 1e-6)
        assertEquals(30.0, h.altDeg, 1e-6)
    }

    @Test
    fun `non-normalized vector still yields correct azimuth and altitude (az=90, alt=45)`() {
        val (e, n, u) = forwardFor(azDeg = 90.0, altDeg = 45.0, scale = 0.013)
        val h = forwardVectorToHorizontal(e, n, u)
        assertEquals(90.0, h.azDeg, 1e-6)
        assertEquals(45.0, h.altDeg, 1e-6)
    }

    @Test
    fun `forward vector altitude is roll invariant (uses up component only)`() {
        // Straight up: any azimuth degenerates, altitude must be +90.
        val h = forwardVectorToHorizontal(east = 0.0, north = 0.0, up = 1.0)
        assertEquals(90.0, h.altDeg, 1e-9)
    }

    @Test
    fun `up component is clamped against floating point overshoot`() {
        val h = forwardVectorToHorizontal(east = 0.0, north = 0.0, up = 1.0000001)
        assertEquals(90.0, h.altDeg, 1e-9)
    }

    @Test
    fun `zero vector returns the documented zenith fallback`() {
        val h = forwardVectorToHorizontal(east = 0.0, north = 0.0, up = 0.0)
        assertEquals(0.0, h.azDeg, 1e-9)
        assertEquals(90.0, h.altDeg, 1e-9)
    }

    @Test
    fun `east declination rotates magnetic azimuth towards true north`() {
        val magnetic = Horizontal(azDeg = 10.0, altDeg = 20.0)
        val trueNorth = magnetic.toTrueNorth(declinationDeg = 12.0)
        assertEquals(22.0, trueNorth.azDeg, 1e-9)
        assertEquals(20.0, trueNorth.altDeg, 1e-9)
    }

    @Test
    fun `declination wraps across the 360 boundary`() {
        val magnetic = Horizontal(azDeg = 355.0, altDeg = 0.0)
        assertEquals(7.0, magnetic.toTrueNorth(12.0).azDeg, 1e-9)
    }

    @Test
    fun `west declination is negative and wraps below zero`() {
        val magnetic = Horizontal(azDeg = 5.0, altDeg = 0.0)
        assertEquals(355.0, magnetic.toTrueNorth(-10.0).azDeg, 1e-9)
    }

    @Test
    fun `zero declination is a no-op`() {
        val magnetic = Horizontal(azDeg = 123.456, altDeg = 7.0)
        assertEquals(magnetic, magnetic.toTrueNorth(0.0))
    }
}
