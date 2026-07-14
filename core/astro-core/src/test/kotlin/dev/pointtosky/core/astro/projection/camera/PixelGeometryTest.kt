package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the CAM-1e pixel-geometry value types ([PixelPoint], [PixelSize], [PixelRect]):
 * finite validation, strictly-positive sizes, ordered rectangles, and inclusive `contains`. No
 * Android type is involved.
 */
class PixelGeometryTest {
    private val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    @Test
    fun `PixelPoint accepts any finite value including negatives`() {
        val p = PixelPoint(-12.5, 340.75)
        assertEquals(-12.5, p.x)
        assertEquals(340.75, p.y)
    }

    @Test
    fun `PixelPoint rejects non-finite coordinates`() {
        nonFinite.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("x=$bad") { PixelPoint(bad, 0.0) }
            assertFailsWith<IllegalArgumentException>("y=$bad") { PixelPoint(0.0, bad) }
        }
    }

    @Test
    fun `PixelSize accepts strictly positive dimensions`() {
        val s = PixelSize(1920.0, 1080.0)
        assertEquals(1920.0, s.width)
        assertEquals(1080.0, s.height)
    }

    @Test
    fun `PixelSize rejects zero or negative dimensions`() {
        listOf(0.0, -1.0, -1920.0).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("width=$bad") { PixelSize(bad, 1080.0) }
            assertFailsWith<IllegalArgumentException>("height=$bad") { PixelSize(1920.0, bad) }
        }
    }

    @Test
    fun `PixelSize rejects non-finite dimensions`() {
        nonFinite.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("width=$bad") { PixelSize(bad, 1080.0) }
            assertFailsWith<IllegalArgumentException>("height=$bad") { PixelSize(1920.0, bad) }
        }
    }

    @Test
    fun `PixelRect computes width and height`() {
        val r = PixelRect(left = 100.0, top = 200.0, right = 1900.0, bottom = 1400.0)
        assertEquals(1800.0, r.width)
        assertEquals(1200.0, r.height)
    }

    @Test
    fun `PixelRect rejects unordered edges`() {
        assertFailsWith<IllegalArgumentException> { PixelRect(100.0, 0.0, 100.0, 100.0) } // left == right
        assertFailsWith<IllegalArgumentException> { PixelRect(200.0, 0.0, 100.0, 100.0) } // left > right
        assertFailsWith<IllegalArgumentException> { PixelRect(0.0, 100.0, 100.0, 100.0) } // top == bottom
        assertFailsWith<IllegalArgumentException> { PixelRect(0.0, 200.0, 100.0, 100.0) } // top > bottom
    }

    @Test
    fun `PixelRect rejects non-finite edges`() {
        nonFinite.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("left=$bad") { PixelRect(bad, 0.0, 100.0, 100.0) }
            assertFailsWith<IllegalArgumentException>("bottom=$bad") { PixelRect(0.0, 0.0, 100.0, bad) }
        }
    }

    @Test
    fun `PixelRect contains is inclusive of edges and corners`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        assertTrue(r.contains(PixelPoint(0.0, 0.0)))
        assertTrue(r.contains(PixelPoint(100.0, 50.0)))
        assertTrue(r.contains(PixelPoint(50.0, 25.0)))
        assertTrue(r.contains(PixelPoint(100.0, 0.0)))
    }

    @Test
    fun `PixelRect contains rejects points outside without tolerance`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        assertFalse(r.contains(PixelPoint(-0.5, 25.0)))
        assertFalse(r.contains(PixelPoint(100.5, 25.0)))
        assertFalse(r.contains(PixelPoint(50.0, -0.5)))
        assertFalse(r.contains(PixelPoint(50.0, 50.5)))
    }

    @Test
    fun `PixelRect contains widens by tolerance without clamping the point`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        assertFalse(r.contains(PixelPoint(100.4, 25.0)))
        assertTrue(r.contains(PixelPoint(100.4, 25.0), tolerancePx = 0.5))
    }

    @Test
    fun `PixelRect contains accepts zero and positive finite tolerance`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        assertTrue(r.contains(PixelPoint(50.0, 25.0), tolerancePx = 0.0))
        assertTrue(r.contains(PixelPoint(50.0, 25.0), tolerancePx = 5.0))
    }

    @Test
    fun `PixelRect contains rejects negative tolerance`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        assertFailsWith<IllegalArgumentException> { r.contains(PixelPoint(50.0, 25.0), tolerancePx = -1.0) }
    }

    @Test
    fun `PixelRect contains rejects non-finite tolerance`() {
        val r = PixelRect(0.0, 0.0, 100.0, 50.0)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("tolerancePx=$bad") {
                r.contains(PixelPoint(50.0, 25.0), tolerancePx = bad)
            }
        }
    }
}
