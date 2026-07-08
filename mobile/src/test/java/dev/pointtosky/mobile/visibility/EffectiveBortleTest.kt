package dev.pointtosky.mobile.visibility

import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for [resolveEffectiveBortle]. A small PTSKLP01 v3 builder (mirroring the grid
 * writer) keeps these self-contained — no file I/O, no Android.
 */
class EffectiveBortleTest {

    // 2×4 global grid; cell [0,0] byte=51 at lat 45, lon −135.
    private val cells = byteArrayOf(51, 91, 0, 31, 21, 0, 111, 41)
    private val realGrid = LightPollutionGrid.parse(buildGridBytes(placeholder = false))
    private val placeholderGrid = LightPollutionGrid.parse(buildGridBytes(placeholder = true))

    private val lat = 45.0
    private val lon = -135.0
    private val manual = Bortle.CLASS_4

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(actual - expected) <= 1e-9, "Expected $expected but was $actual")
    }

    @Test
    fun placeholderGridIsUnavailableAndFallsBackToManual() {
        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = true,
            latDeg = lat,
            lonDeg = lon,
            grid = placeholderGrid,
        )

        assertNull(result.auto)
        assertFalse(result.available)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
    }

    @Test
    fun realGridAutoResolvedUsesGridLookup() {
        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = true,
            latDeg = lat,
            lonDeg = lon,
            grid = realGrid,
        )

        val expectedSqm = LightPollutionGrid.SQM_MIN + (51 - 1) * LightPollutionGrid.SQM_STEP
        assertNear(expectedSqm, result.auto!!.sqmMag)
        assertNear(expectedSqm, result.effective.sqmMag)
        assertTrue(result.available)
    }

    @Test
    fun manualSourceIgnoresGridButReportsAvailability() {
        val result = resolveEffectiveBortle(
            source = BortleSource.MANUAL,
            manual = manual,
            locationResolved = true,
            latDeg = lat,
            lonDeg = lon,
            grid = realGrid,
        )

        assertNull(result.auto)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
        assertTrue(result.available)
    }

    @Test
    fun unresolvedLocationYieldsNoAutoLookup() {
        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = false,
            latDeg = lat,
            lonDeg = lon,
            grid = realGrid,
        )

        assertNull(result.auto)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
        // Coverage is unknown without a resolved location, so the signal is honest: unavailable.
        assertFalse(result.available)
    }

    @Test
    fun nodataCellFallsBackToManual() {
        // [0,2] at lat 45, lon 45 is nodata (byte 0), so the grid yields no value here.
        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = true,
            latDeg = 45.0,
            lonDeg = 45.0,
            grid = realGrid,
        )

        assertNull(result.auto)
        assertFalse(result.available)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
    }

    @Test
    fun realGridOutsideCoverageIsUnavailable() {
        // Regional 2×2 tile covering lat [30,50) × lon [10,30); (45, 5) is a valid coordinate
        // just west of the tile, so the grid yields no value there — like a point outside a
        // regional asset. Availability must be honest even though the grid is non-placeholder.
        val regionCells = byteArrayOf(41, 51, 61, 71)
        val regionGrid = LightPollutionGrid.parse(
            buildGridBytes(
                placeholder = false,
                rows = 2,
                cols = 2,
                latTop = 50.0,
                lonLeft = 10.0,
                degPerCell = 10.0,
                cellBytes = regionCells,
            ),
        )

        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = true,
            latDeg = 45.0,
            lonDeg = 5.0,
            grid = regionGrid,
        )

        assertNull(result.auto)
        assertFalse(result.available)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
    }

    // ── PTSKLP01 v3 wire-format builder (mirrors the Python writer) ────────────

    private fun buildGridBytes(
        placeholder: Boolean,
        rows: Int = 2,
        cols: Int = 4,
        latTop: Double = 90.0,
        lonLeft: Double = -180.0,
        degPerCell: Double = 90.0,
        cellBytes: ByteArray = cells,
    ): ByteArray {
        val compressed = deflate(cellBytes)
        val buf = ByteArray(52 + compressed.size)
        "PTSKLP01".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        putLeInt(buf, 8, 3)
        putLeInt(buf, 12, rows)
        putLeInt(buf, 16, cols)
        putLeDouble(buf, 20, latTop)
        putLeDouble(buf, 28, lonLeft)
        putLeDouble(buf, 36, degPerCell)
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
}
