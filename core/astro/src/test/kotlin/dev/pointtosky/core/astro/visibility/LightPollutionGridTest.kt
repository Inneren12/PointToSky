package dev.pointtosky.core.astro.visibility

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM round-trip tests for [LightPollutionGrid].
 *
 * A small Kotlin helper mirrors the Python writer (same PTSKLP01 format) so
 * tests are fully self-contained with no file I/O.
 *
 * Test grid layout (2 rows × 4 cols, deg=90, latTop=90, lonLeft=-180):
 *   row 0 (north hemisphere): cells [1, 5, 0, 3]
 *   row 1 (south hemisphere): cells [2, 0, 7, 4]
 *   Cell [r,c] covers lat [90-r*90, 90-(r+1)*90) × lon [-180+c*90, -180+(c+1)*90)
 *   Value 0 = nodata/ocean, 1..9 = Bortle class.
 */
class LightPollutionGridTest {

    // ── Wire format builder (mirrors Python write_grid) ───────────────────────

    private fun buildGridBytes(
        rows: Int,
        cols: Int,
        latTop: Double,
        lonLeft: Double,
        deg: Double,
        cells: ByteArray,
        placeholder: Boolean = false,
    ): ByteArray {
        val compressed = deflate(cells)
        val buf = ByteArray(52 + compressed.size)
        "PTSKLP01".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        putLeInt(buf, 8, 2)
        putLeInt(buf, 12, rows)
        putLeInt(buf, 16, cols)
        putLeDouble(buf, 20, latTop)
        putLeDouble(buf, 28, lonLeft)
        putLeDouble(buf, 36, deg)
        putLeInt(buf, 44, if (placeholder) 1 else 0)
        putLeInt(buf, 48, compressed.size)
        compressed.copyInto(buf, 52)
        return buf
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArray(data.size + 64)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    private fun putLeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putLeDouble(buf: ByteArray, off: Int, v: Double) {
        val bits = v.toBits()
        for (i in 0..7) buf[off + i] = ((bits shr (i * 8)) and 0xFF).toByte()
    }

    // ── Test fixture: 2×4 global-span grid ───────────────────────────────────

    // row 0: [CLASS_1, CLASS_5, nodata, CLASS_3]
    // row 1: [CLASS_2, nodata,  CLASS_7, CLASS_4]
    private val CELLS_2X4 = byteArrayOf(1, 5, 0, 3, 2, 0, 7, 4)
    private val GRID_2X4 by lazy {
        LightPollutionGrid.parse(buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4))
    }

    // ── Known-cell lookups ────────────────────────────────────────────────────

    @Test
    fun bortleAtCellCentresMatchExpected() {
        val grid = GRID_2X4
        // Cell centres: lat = 90 - (row+0.5)*90; lon = -180 + (col+0.5)*90
        assertEquals(Bortle.CLASS_1, grid.bortleAt(45.0, -135.0))  // [0,0]
        assertEquals(Bortle.CLASS_5, grid.bortleAt(45.0, -45.0))   // [0,1]
        assertEquals(Bortle.CLASS_3, grid.bortleAt(45.0, 135.0))   // [0,3]
        assertEquals(Bortle.CLASS_2, grid.bortleAt(-45.0, -135.0)) // [1,0]
        assertEquals(Bortle.CLASS_7, grid.bortleAt(-45.0, 45.0))   // [1,2]
        assertEquals(Bortle.CLASS_4, grid.bortleAt(-45.0, 135.0))  // [1,3]
    }

    // ── Ocean / nodata cells ──────────────────────────────────────────────────

    @Test
    fun oceanCellReturnsNull() {
        val grid = GRID_2X4
        assertNull(grid.bortleAt(45.0, 45.0))   // [0,2] = 0
        assertNull(grid.bortleAt(-45.0, -45.0)) // [1,1] = 0
    }

    // ── Longitude wrap ────────────────────────────────────────────────────────

    @Test
    fun longitudeWrapPositive() {
        // lon=190 wraps to lon=-170; -170 ∈ [-180,-90) → col 0 → [0,0] = CLASS_1
        assertEquals(Bortle.CLASS_1, GRID_2X4.bortleAt(45.0, 190.0))
    }

    @Test
    fun longitudeWrapNegative() {
        // lon=-190 → -190+360=170; 170 ∈ [90,180) → col 3 → [0,3] = CLASS_3
        assertEquals(Bortle.CLASS_3, GRID_2X4.bortleAt(45.0, -190.0))
    }

    @Test
    fun longitudeWrapSymmetry() {
        // lon=190 and lon=-170 must agree
        assertEquals(GRID_2X4.bortleAt(45.0, 190.0), GRID_2X4.bortleAt(45.0, -170.0))
    }

    // ── Out-of-range latitude ─────────────────────────────────────────────────

    @Test
    fun latitudeAboveGridReturnsNull() {
        assertNull(GRID_2X4.bortleAt(91.0, 0.0))
    }

    @Test
    fun latitudeBelowGridReturnsNull() {
        assertNull(GRID_2X4.bortleAt(-91.0, 0.0))
    }

    // ── Non-global regional grid with out-of-range queries ────────────────────

    @Test
    fun nonGlobalGridOutOfRangeReturnsNull() {
        // 2×2 grid: latTop=50, lonLeft=10, deg=10 → covers lat [30,50) × lon [10,30)
        val cells = byteArrayOf(4, 5, 6, 7)
        val grid = LightPollutionGrid.parse(buildGridBytes(2, 2, 50.0, 10.0, 10.0, cells))

        assertEquals(Bortle.CLASS_4, grid.bortleAt(45.0, 15.0))  // [0,0] — in range
        assertNull(grid.bortleAt(55.0, 15.0))                     // lat above grid
        assertNull(grid.bortleAt(25.0, 15.0))                     // lat below grid
        assertNull(grid.bortleAt(45.0, 5.0))                      // lon left of grid
        assertNull(grid.bortleAt(45.0, 35.0))                     // lon right of grid
    }

    // ── Placeholder flag round-trip ───────────────────────────────────────────

    @Test
    fun placeholderFlagRoundTrips() {
        val placeholder =
            LightPollutionGrid.parse(
                buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, placeholder = true),
            )
        assertTrue(placeholder.isPlaceholder)

        val real =
            LightPollutionGrid.parse(
                buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, placeholder = false),
            )
        assertFalse(real.isPlaceholder)
    }

    // ── Malformed input ───────────────────────────────────────────────────────

    @Test
    fun badMagicThrows() {
        val bad = buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4).copyOf()
        bad[0] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { LightPollutionGrid.parse(bad) }
    }

    @Test
    fun wrongInflatedLengthThrows() {
        // Build a valid 2×4 grid, then patch the header to claim cols=5.
        // Parser expects rows*cols=10 inflated bytes but the payload only inflates to 8.
        val bytes = buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4).copyOf()
        putLeInt(bytes, 16, 5)  // cols=5 → expected=10, actual inflated=8 → throw
        assertFailsWith<IllegalArgumentException> { LightPollutionGrid.parse(bytes) }
    }
}
