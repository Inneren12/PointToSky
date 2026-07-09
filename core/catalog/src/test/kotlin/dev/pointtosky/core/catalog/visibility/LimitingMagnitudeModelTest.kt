package dev.pointtosky.core.catalog.visibility

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
    fun `darkest SQM reading yields the Bortle 1 endpoint`() {
        assertEquals(
            LimitingMagnitudeModel.fromBortle(1),
            LimitingMagnitudeModel.fromSqm(22.0),
            1e-9,
        )
    }

    @Test
    fun `brightest SQM reading yields the Bortle 9 endpoint`() {
        assertEquals(
            LimitingMagnitudeModel.fromBortle(9),
            LimitingMagnitudeModel.fromSqm(16.0),
            1e-9,
        )
    }

    @Test
    fun `SQM is monotonic, darker sky gives fainter limit`() {
        val dim = LimitingMagnitudeModel.fromSqm(18.0)
        val dark = LimitingMagnitudeModel.fromSqm(21.0)
        assertTrue(dark > dim)
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
}
