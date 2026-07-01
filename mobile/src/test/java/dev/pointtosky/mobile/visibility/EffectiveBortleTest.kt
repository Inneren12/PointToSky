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
        assertTrue(result.available)
    }

    @Test
    fun nodataCellFallsBackToManual() {
        // [0,2] at lat 45, lon 45 is nodata (byte 0).
        val result = resolveEffectiveBortle(
            source = BortleSource.AUTO,
            manual = manual,
            locationResolved = true,
            latDeg = 45.0,
            lonDeg = 45.0,
            grid = realGrid,
        )

        assertNull(result.auto)
        assertNear(manual.representativeSqm, result.effective.sqmMag)
    }

    // ── PTSKLP01 v3 wire-format builder (mirrors the Python writer) ────────────

    private fun buildGridBytes(placeholder: Boolean): ByteArray {
        val compressed = deflate(cells)
        val buf = ByteArray(52 + compressed.size)
        "PTSKLP01".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
        putLeInt(buf, 8, 3)
        putLeInt(buf, 12, 2) // rows
        putLeInt(buf, 16, 4) // cols
        putLeDouble(buf, 20, 90.0) // latTop
        putLeDouble(buf, 28, -180.0) // lonLeft
        putLeDouble(buf, 36, 90.0) // degPerCell
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
