package dev.pointtosky.core.catalog.visibility

import dev.pointtosky.core.astro.visibility.darkNelmFromSqm
import dev.pointtosky.core.catalog.binary.PtskCat0Catalog
import dev.pointtosky.core.catalog.binary.RealStarVisibilityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Pure JVM tests for [LimitingMagnitudeModel]. No Android dependency. */
class LimitingMagnitudeModelTest {

    @Test
    fun `each Bortle class maps to the expected limiting magnitude`() {
        assertEquals(7.6, LimitingMagnitudeModel.fromBortle(1), 0.0)
        assertEquals(7.1, LimitingMagnitudeModel.fromBortle(2), 0.0)
        assertEquals(6.6, LimitingMagnitudeModel.fromBortle(3), 0.0)
        assertEquals(6.1, LimitingMagnitudeModel.fromBortle(4), 0.0)
        assertEquals(5.6, LimitingMagnitudeModel.fromBortle(5), 0.0)
        assertEquals(5.1, LimitingMagnitudeModel.fromBortle(6), 0.0)
        assertEquals(4.6, LimitingMagnitudeModel.fromBortle(7), 0.0)
        assertEquals(4.1, LimitingMagnitudeModel.fromBortle(8), 0.0)
        assertEquals(3.6, LimitingMagnitudeModel.fromBortle(9), 0.0)
    }

    @Test
    fun `Bortle class below 1 fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            LimitingMagnitudeModel.fromBortle(0)
        }
    }

    @Test
    fun `Bortle class above 9 fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            LimitingMagnitudeModel.fromBortle(10)
        }
    }

    @Test
    fun `darker Bortle class yields higher limiting magnitude`() {
        for (bortle in 2..9) {
            assertTrue(
                "Bortle ${bortle - 1} should be brighter-limit than Bortle $bortle",
                LimitingMagnitudeModel.fromBortle(bortle - 1) > LimitingMagnitudeModel.fromBortle(bortle),
            )
        }
    }

    @Test
    fun `Bortle 1 is fainter limit than Bortle 9`() {
        assertTrue(LimitingMagnitudeModel.fromBortle(1) > LimitingMagnitudeModel.fromBortle(9))
    }

    @Test
    fun `Bortle mapping is clamped to the supported range`() {
        for (bortle in 1..9) {
            val mag = LimitingMagnitudeModel.fromBortle(bortle)
            assertTrue(mag in LimitingMagnitudeModel.SUPPORTED_RANGE)
        }
    }

    @Test
    fun `fromSqm delegates to the SkyBrightness dark-NELM calibration`() {
        // Cross-check against dev.pointtosky.core.astro.visibility.darkNelmFromSqm directly
        // so this test fails if the two ever drift apart, without hardcoding its anchors here.
        for (sqm in listOf(21.75, 21.0, 19.10, 18.0, 17.5, 16.5)) {
            assertEquals(darkNelmFromSqm(sqm), LimitingMagnitudeModel.fromSqm(sqm), 1e-9)
        }
    }

    @Test
    fun `SQM 17_5 (Bortle 8_5 urban sky) matches the existing calibration anchor, not the old broad interpolation`() {
        // Bortle 8.5 / NELM ~4.1-4.2 per SkyBrightness.kt's anchors — previously the old
        // full-range 16..22 linear interpolation over-selected and returned ~4.6 here.
        val limit = LimitingMagnitudeModel.fromSqm(17.5)

        assertEquals(4.15, limit, 1e-9)
        assertTrue("expected ~4.15 (Bortle 8.5), old broken model returned ~4.6", limit < 4.3)
    }

    @Test
    fun `darkest SQM reading yields the calibration's darkest NELM`() {
        // Beyond the darkest anchor (21.75), bortleFromSqm clamps to Bortle 1.0, whose
        // NELM anchor is exactly 7.8 (see SkyBrightness.kt NELM_ANCHORS).
        assertEquals(7.8, LimitingMagnitudeModel.fromSqm(22.0), 1e-9)
    }

    @Test
    fun `brightest SQM reading yields the calibration's brightest NELM`() {
        // Beyond the brightest anchor (17.50), bortleFromSqm clamps to Bortle 9.0, whose
        // NELM anchor is exactly 4.0 (see SkyBrightness.kt NELM_ANCHORS).
        assertEquals(4.0, LimitingMagnitudeModel.fromSqm(16.0), 1e-9)
    }

    @Test
    fun `SQM is monotonic, darker sky gives fainter limit`() {
        val dim = LimitingMagnitudeModel.fromSqm(18.0)
        val dark = LimitingMagnitudeModel.fromSqm(21.0)
        assertTrue(dark > dim)
    }

    @Test
    fun `bright urban SQM does not over-select relative to Bortle 8_9 anchors`() {
        val bortle8Nelm = LimitingMagnitudeModel.fromSqm(17.5) // ~Bortle 8.5
        val bortle9Nelm = LimitingMagnitudeModel.fromSqm(16.0) // clamped to Bortle 9.0

        assertTrue(bortle8Nelm > bortle9Nelm)
        assertTrue(bortle8Nelm < 4.3)
    }

    @Test
    fun `SQM outside plausible range is clamped, not rejected`() {
        assertEquals(LimitingMagnitudeModel.fromSqm(16.0), LimitingMagnitudeModel.fromSqm(5.0), 0.0)
        assertEquals(LimitingMagnitudeModel.fromSqm(22.0), LimitingMagnitudeModel.fromSqm(30.0), 0.0)
    }

    @Test
    fun `SQM NaN fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            LimitingMagnitudeModel.fromSqm(Double.NaN)
        }
    }

    @Test
    fun `SQM positive infinity fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            LimitingMagnitudeModel.fromSqm(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `SQM negative infinity fails fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            LimitingMagnitudeModel.fromSqm(Double.NEGATIVE_INFINITY)
        }
    }

    // --- Smoke test: LimitingMagnitudeModel feeding RealStarVisibilityFilter ---

    private data class Rec(val mag: Double, val hip: Int)

    private fun buildCatalogBytes(records: List<Rec>, magLimit: Double = 8.0): ByteArray {
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSKCAT0".toByteArray(Charsets.US_ASCII))
            putInt(1)
            putInt(records.size)
            putInt((magLimit * 100.0).roundToInt())
            putInt(16)
            putInt(2000)
        }.array()

        val recordBytes = ByteBuffer.allocate(records.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        records.forEach { r ->
            recordBytes.putFloat(0f)
            recordBytes.putFloat(0f)
            recordBytes.putShort((r.mag * 100.0).roundToInt().toShort())
            recordBytes.putShort(-32768) // bv unknown
            recordBytes.putInt(r.hip)
        }

        val namesBytes = ByteArrayOutputStream()
        namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())

        return header + recordBytes.array() + namesBytes.toByteArray()
    }

    @Test
    fun `fromBortle output can drive RealStarVisibilityFilter select`() {
        val records = listOf(
            Rec(-1.0, 1),
            Rec(2.0, 2),
            Rec(4.0, 3),
            Rec(5.6, 4),
            Rec(6.5, 5),
            Rec(8.0, 6),
        )
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(records))

        val limit = LimitingMagnitudeModel.fromBortle(5)
        assertEquals(5.6, limit, 0.0)

        val selection = RealStarVisibilityFilter.select(catalog, limit)

        assertEquals(4, selection.count)
        for (i in selection.indices) {
            assertTrue(catalog.magAt(i) <= limit)
        }
    }

    @Test
    fun `fromSqm output can drive RealStarVisibilityFilter select`() {
        val records = listOf(
            Rec(-1.0, 1),
            Rec(2.0, 2),
            Rec(4.0, 3),
            Rec(4.15, 4),
            Rec(6.5, 5),
            Rec(8.0, 6),
        )
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(records))

        val limit = LimitingMagnitudeModel.fromSqm(17.5) // Bortle 8.5 urban sky
        assertEquals(4.15, limit, 1e-9)

        val selection = RealStarVisibilityFilter.select(catalog, limit)

        assertEquals(4, selection.count)
        for (i in selection.indices) {
            assertTrue(catalog.magAt(i) <= limit)
        }
    }
}
