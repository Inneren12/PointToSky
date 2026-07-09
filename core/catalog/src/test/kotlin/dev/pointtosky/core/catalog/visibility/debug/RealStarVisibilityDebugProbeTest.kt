package dev.pointtosky.core.catalog.visibility.debug

import dev.pointtosky.core.catalog.binary.PtskCat0Catalog
import dev.pointtosky.core.catalog.binary.RealStarCatalogLoadException
import dev.pointtosky.core.catalog.binary.RealStarCatalogProvider
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM tests for [RealStarVisibilityDebugProbe], composing a fake
 * [RealStarCatalogProvider] over an in-memory PTSKCAT0 catalog. Confirms only
 * that the VF-1a..d pipeline loads and computes counts through this
 * runtime/debug entry point — no renderer, no PTSKCAT4 catalog-art code is
 * touched by this class or its tests.
 */
class RealStarVisibilityDebugProbeTest {

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

    private val sampleRecords = listOf(
        Rec(-1.0, 1),
        Rec(2.0, 2),
        Rec(4.0, 3),
        Rec(5.6, 4),
        Rec(6.5, 5),
        Rec(8.0, 6),
    )

    private fun fakeProvider(records: List<Rec> = sampleRecords, magLimit: Double = 8.0): RealStarCatalogProvider {
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(records, magLimit))
        return object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = catalog
        }
    }

    @Test
    fun `successful snapshot exposes catalog and selection counts`() {
        val probe = RealStarVisibilityDebugProbe(RealStarVisibilityService(fakeProvider(magLimit = 8.0)))

        val snapshot = probe.snapshot(SkyQualityInput.Bortle(5))

        when (snapshot) {
            is RealStarVisibilityDebugSnapshot.Success -> {
                assertEquals(sampleRecords.size, snapshot.info.catalogCount)
                assertEquals(8.0, snapshot.info.catalogMagLimit, 0.0)
                assertEquals(5.6, snapshot.info.limitingMagnitude, 0.0)
                assertEquals(4, snapshot.info.visibleCount)
            }
            is RealStarVisibilityDebugSnapshot.Failure -> fail("expected Success, got $snapshot")
        }
    }

    @Test
    fun `direct limiting magnitude input is reflected as-is`() {
        val probe = RealStarVisibilityDebugProbe(RealStarVisibilityService(fakeProvider()))

        val snapshot = probe.snapshot(SkyQualityInput.LimitingMagnitude(4.0))

        when (snapshot) {
            is RealStarVisibilityDebugSnapshot.Success -> {
                assertEquals(4.0, snapshot.info.limitingMagnitude, 0.0)
                assertEquals(3, snapshot.info.visibleCount)
            }
            is RealStarVisibilityDebugSnapshot.Failure -> fail("expected Success, got $snapshot")
        }
    }

    @Test
    fun `catalog load failure produces an explicit Failure snapshot instead of throwing`() {
        val failingProvider = object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = throw RealStarCatalogLoadException("boom: asset missing")
        }
        val probe = RealStarVisibilityDebugProbe(RealStarVisibilityService(failingProvider))

        val snapshot = probe.snapshot(SkyQualityInput.Bortle(5))

        when (snapshot) {
            is RealStarVisibilityDebugSnapshot.Failure -> assertTrue(snapshot.message.contains("boom"))
            is RealStarVisibilityDebugSnapshot.Success -> fail("expected Failure, got $snapshot")
        }
    }
}
