package dev.pointtosky.core.astro.visibility

import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM round-trip tests for [LightPollutionGrid].
 *
 * A small Kotlin helper mirrors the Python writer (same PTSKLP01 v3 format) so
 * tests are fully self-contained with no file I/O.
 *
 * Test grid layout (2 rows × 4 cols, deg=90, latTop=90, lonLeft=-180):
 *   row 0 (north hemisphere): cells [11, 51, 0, 31]
 *   row 1 (south hemisphere): cells [21, 0, 71, 41]
 *   Cell [r,c] covers lat [90-r*90, 90-(r+1)*90) × lon [-180+c*90, -180+(c+1)*90)
 *   Value 0 = nodata/ocean, 1..255 = SQM byte per §3 of the format contract.
 */
class LightPollutionGridTest {

    // ── Wire format builder (mirrors Python write_grid v3) ───────────────────

    private fun buildGridBytes(
        rows: Int,
        cols: Int,
        latTop: Double,
        lonLeft: Double,
        deg: Double,
        cells: ByteArray,
        placeholder: Boolean = false,
        version: Int = 3,
    ): ByteArray {
        val compressed = deflate(cells)
        val buf = ByteArray(52 + compressed.size)
        "PTSKLP01".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        putLeInt(buf, 8, version)
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

    private fun assertSqmNear(expected: Double, actual: Double) {
        assertTrue(abs(actual - expected) <= 1e-9, "Expected $expected but was $actual")
    }

    // ── Test fixture: 2×4 global-span grid ───────────────────────────────────

    // row 0: [byte 11, byte 51, nodata, byte 31]
    // row 1: [byte 21, nodata,  byte 71, byte 41]
    private val CELLS_2X4 = byteArrayOf(11, 51, 0, 31, 21, 0, 71, 41)
    private val GRID_2X4 by lazy {
        LightPollutionGrid.parse(buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4))
    }

    private fun sqmForByte(b: Int): Double = LightPollutionGrid.SQM_MIN + (b - 1) * LightPollutionGrid.SQM_STEP

    // ── Known-cell lookups ────────────────────────────────────────────────────

    @Test
    fun sqmAtCellCentresMatchExpected() {
        val grid = GRID_2X4
        // Cell centres: lat = 90 - (row+0.5)*90; lon = -180 + (col+0.5)*90
        assertSqmNear(sqmForByte(11), grid.sqmAt(45.0, -135.0)!!.sqmMag)  // [0,0]
        assertSqmNear(sqmForByte(51), grid.sqmAt(45.0, -45.0)!!.sqmMag)   // [0,1]
        assertSqmNear(sqmForByte(31), grid.sqmAt(45.0, 135.0)!!.sqmMag)   // [0,3]
        assertSqmNear(sqmForByte(21), grid.sqmAt(-45.0, -135.0)!!.sqmMag) // [1,0]
        assertSqmNear(sqmForByte(71), grid.sqmAt(-45.0, 45.0)!!.sqmMag)   // [1,2]
        assertSqmNear(sqmForByte(41), grid.sqmAt(-45.0, 135.0)!!.sqmMag)  // [1,3]
    }

    // ── Ocean / nodata cells ──────────────────────────────────────────────────

    @Test
    fun oceanCellReturnsNull() {
        val grid = GRID_2X4
        assertNull(grid.sqmAt(45.0, 45.0))   // [0,2] = 0
        assertNull(grid.sqmAt(-45.0, -45.0)) // [1,1] = 0
    }

    // ── Longitude wrap ────────────────────────────────────────────────────────

    @Test
    fun longitudeWrapPositive() {
        // lon=190 wraps to lon=-170; -170 ∈ [-180,-90) → col 0 → [0,0] = byte 11
        assertSqmNear(sqmForByte(11), GRID_2X4.sqmAt(45.0, 190.0)!!.sqmMag)
    }

    @Test
    fun longitudeWrapNegative() {
        // lon=-190 → -190+360=170; 170 ∈ [90,180) → col 3 → [0,3] = byte 31
        assertSqmNear(sqmForByte(31), GRID_2X4.sqmAt(45.0, -190.0)!!.sqmMag)
    }

    @Test
    fun longitudeWrapSymmetry() {
        // lon=190 and lon=-170 must agree
        assertEquals(GRID_2X4.sqmAt(45.0, 190.0), GRID_2X4.sqmAt(45.0, -170.0))
    }

    // ── Out-of-range latitude ─────────────────────────────────────────────────

    @Test
    fun latitudeAboveGridReturnsNull() {
        assertNull(GRID_2X4.sqmAt(91.0, 0.0))
    }

    @Test
    fun latitudeBelowGridReturnsNull() {
        assertNull(GRID_2X4.sqmAt(-91.0, 0.0))
    }

    // ── Non-global regional grid with out-of-range queries ────────────────────

    @Test
    fun nonGlobalGridOutOfRangeReturnsNull() {
        // 2×2 grid: latTop=50, lonLeft=10, deg=10 → covers lat [30,50) × lon [10,30)
        val cells = byteArrayOf(41, 51, 61, 71)
        val grid = LightPollutionGrid.parse(buildGridBytes(2, 2, 50.0, 10.0, 10.0, cells))

        assertSqmNear(sqmForByte(41), grid.sqmAt(45.0, 15.0)!!.sqmMag)  // [0,0] — in range
        assertNull(grid.sqmAt(55.0, 15.0))                     // lat above grid
        assertNull(grid.sqmAt(25.0, 15.0))                     // lat below grid
        assertNull(grid.sqmAt(45.0, 5.0))                      // lon left of grid
        assertNull(grid.sqmAt(45.0, 35.0))                     // lon right of grid
    }

    // ── Version gate ──────────────────────────────────────────────────────────

    @Test
    fun v3AcceptedV2Rejected() {
        LightPollutionGrid.parse(buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, version = 3))
        assertFailsWith<IllegalArgumentException> {
            LightPollutionGrid.parse(buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, version = 2))
        }
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

    // ── Placeholder flag round-trip ───────────────────────────────────────────

    @Test
    fun placeholderFlagTrueRoundTrips() {
        val grid = LightPollutionGrid.parse(
            buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, placeholder = true),
        )
        assertTrue(grid.isPlaceholder)
    }

    @Test
    fun placeholderFlagFalseRoundTrips() {
        val grid = LightPollutionGrid.parse(
            buildGridBytes(2, 4, 90.0, -180.0, 90.0, CELLS_2X4, placeholder = false),
        )
        assertFalse(grid.isPlaceholder)
    }
}
