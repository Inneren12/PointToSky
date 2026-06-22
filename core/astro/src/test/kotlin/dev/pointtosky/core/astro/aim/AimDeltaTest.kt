package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AimDeltaTest {

    @Test
    fun `alt 0 - crossTrackDeg equals raw azimuth delta`() {
        val current = Horizontal(azDeg = 10.0, altDeg = 0.0)
        val target = Horizontal(azDeg = 13.0, altDeg = 0.0)
        val delta = aimDelta(current, target)
        assertEquals(3.0, delta.crossTrackDeg, 1e-9)
    }

    @Test
    fun `alt 60 - crossTrackDeg is half the raw azimuth delta`() {
        val current = Horizontal(azDeg = 0.0, altDeg = 60.0)
        val target = Horizontal(azDeg = 4.0, altDeg = 60.0)
        val delta = aimDelta(current, target)
        val expected = 4.0 * cos(Math.toRadians(60.0)) // 4.0 * 0.5 = 2.0
        assertEquals(expected, delta.crossTrackDeg, 1e-9)
    }

    @Test
    fun `near zenith - large raw azimuth delta collapses to tiny cross-track`() {
        val current = Horizontal(azDeg = 0.0, altDeg = 89.0)
        val target = Horizontal(azDeg = 40.0, altDeg = 89.0)
        val delta = aimDelta(current, target)
        val expected = 40.0 * cos(Math.toRadians(89.0))
        assertEquals(expected, delta.crossTrackDeg, 1e-9)
        assertTrue(delta.crossTrackDeg < 1.0, "Expected cross-track < 1° near zenith, was ${delta.crossTrackDeg}")
    }

    @Test
    fun `positive sign preserved when target is clockwise`() {
        val current = Horizontal(azDeg = 0.0, altDeg = 45.0)
        val target = Horizontal(azDeg = 10.0, altDeg = 45.0)
        val delta = aimDelta(current, target)
        assertTrue(delta.crossTrackDeg > 0.0, "Expected positive cross-track when target is clockwise")
    }

    @Test
    fun `negative sign when target is counter-clockwise`() {
        val current = Horizontal(azDeg = 10.0, altDeg = 45.0)
        val target = Horizontal(azDeg = 0.0, altDeg = 45.0)
        val delta = aimDelta(current, target)
        assertTrue(delta.crossTrackDeg < 0.0, "Expected negative cross-track when target is counter-clockwise")
    }

    @Test
    fun `alongTrackDeg equals target altitude minus current altitude`() {
        val current = Horizontal(azDeg = 0.0, altDeg = 20.0)
        val target = Horizontal(azDeg = 0.0, altDeg = 35.0)
        val delta = aimDelta(current, target)
        assertEquals(15.0, delta.alongTrackDeg, 1e-9)
    }

    @Test
    fun `azimuth wrap across north gives shortest arc`() {
        // current at 355°, target at 5° → raw delta = 10° (not -350°)
        val current = Horizontal(azDeg = 355.0, altDeg = 0.0)
        val target = Horizontal(azDeg = 5.0, altDeg = 0.0)
        val delta = aimDelta(current, target)
        // At alt=0 cross-track == raw
        assertEquals(10.0, delta.crossTrackDeg, 1e-9)
        assertTrue(delta.crossTrackDeg > 0.0)
    }

    @Test
    fun `azimuth wrap across north sign consistent at non-zero altitude`() {
        val current = Horizontal(azDeg = 355.0, altDeg = 30.0)
        val target = Horizontal(azDeg = 5.0, altDeg = 30.0)
        val delta = aimDelta(current, target)
        val expected = 10.0 * cos(Math.toRadians(30.0))
        assertEquals(expected, delta.crossTrackDeg, 1e-9)
        assertTrue(delta.crossTrackDeg > 0.0)
    }
}
