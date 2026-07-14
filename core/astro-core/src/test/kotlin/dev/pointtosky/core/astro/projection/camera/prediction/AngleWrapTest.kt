package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class AngleWrapTest {
    private val eps = 1e-9

    @Test
    fun `wrapRadTwoPi leaves an already-canonical value unchanged`() {
        assertTrue(abs(wrapRadTwoPi(0.0) - 0.0) < eps)
        assertTrue(abs(wrapRadTwoPi(1.0) - 1.0) < eps)
        assertTrue(abs(wrapRadTwoPi(PI) - PI) < eps)
    }

    @Test
    fun `wrapRadTwoPi wraps exactly 2π to zero`() {
        assertTrue(abs(wrapRadTwoPi(2.0 * PI) - 0.0) < eps)
    }

    @Test
    fun `wrapRadTwoPi wraps a small negative value forward near 2π`() {
        assertTrue(abs(wrapRadTwoPi(-0.1) - (2.0 * PI - 0.1)) < eps)
    }

    @Test
    fun `wrapRadTwoPi wraps a value slightly beyond 2π back near zero`() {
        assertTrue(abs(wrapRadTwoPi(2.0 * PI + 0.3) - 0.3) < eps)
    }

    @Test
    fun `wrapRadTwoPi handles multiple full turns`() {
        assertTrue(abs(wrapRadTwoPi(4.0 * PI + 0.7) - 0.7) < 1e-6)
        assertTrue(abs(wrapRadTwoPi(-4.0 * PI - 0.7) - (2.0 * PI - 0.7)) < 1e-6)
    }

    @Test
    fun `wrapRadTwoPi result is always in the canonical range`() {
        val samples = listOf(-100.0, -10.0, -0.0001, 0.0, 0.0001, 6.0, 6.28, 6.29, 100.0)
        for (v in samples) {
            val wrapped = wrapRadTwoPi(v)
            assertTrue(wrapped >= 0.0 && wrapped < 2.0 * PI, "wrapRadTwoPi($v) = $wrapped out of [0, 2π)")
        }
    }

    @Test
    fun `wrapRadMinusPiToPi leaves an already-canonical value unchanged`() {
        assertTrue(abs(wrapRadMinusPiToPi(0.0) - 0.0) < eps)
        assertTrue(abs(wrapRadMinusPiToPi(1.0) - 1.0) < eps)
        assertTrue(abs(wrapRadMinusPiToPi(-PI) - (-PI)) < eps)
    }

    @Test
    fun `wrapRadMinusPiToPi wraps positive π to negative π`() {
        assertTrue(abs(wrapRadMinusPiToPi(PI) - (-PI)) < eps)
    }

    @Test
    fun `wrapRadMinusPiToPi wraps a value slightly beyond π back near minus π`() {
        assertTrue(abs(wrapRadMinusPiToPi(PI + 0.2) - (-PI + 0.2)) < eps)
    }

    @Test
    fun `wrapRadMinusPiToPi wraps a value slightly below minus π back near π`() {
        assertTrue(abs(wrapRadMinusPiToPi(-PI - 0.2) - (PI - 0.2)) < eps)
    }

    @Test
    fun `wrapRadMinusPiToPi result is always in the canonical range`() {
        val samples = listOf(-100.0, -10.0, -PI - 0.0001, -PI, 0.0, PI - 0.0001, PI, 6.0, 100.0)
        for (v in samples) {
            val wrapped = wrapRadMinusPiToPi(v)
            assertTrue(wrapped >= -PI && wrapped < PI, "wrapRadMinusPiToPi($v) = $wrapped out of [-π, π)")
        }
    }
}
