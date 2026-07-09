package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.catalog.ByteArrayAssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM tests for [AssetRealStarCatalogProvider]: verifies it reads asset bytes and
 * delegates to [PtskCat0Catalog.parse], and that missing/invalid assets fail with a
 * message that names the asset path, the format, and asset packaging as a next step.
 */
class AssetRealStarCatalogProviderTest {

    private fun buildValidCatalog(): ByteArray {
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSKCAT0".toByteArray(Charsets.US_ASCII))
            putInt(1) // version
            putInt(1) // count
            putInt((6.5 * 100.0).roundToInt()) // magLimitCenti
            putInt(16) // recSize
            putInt(2000) // epoch
        }.array()

        val record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(101.2875f) // ra
            putFloat(-16.7161f) // dec
            putShort((-1.46 * 100.0).roundToInt().toShort()) // mag
            putShort(-32768) // bv unknown
            putInt(32349) // hip
        }.array()

        val names = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array() // namesCount = 0

        return header + record + names
    }

    @Test
    fun `loads and delegates to PtskCat0Catalog parse`() {
        val provider = AssetRealStarCatalogProvider(
            ByteArrayAssetProvider(mapOf(AssetRealStarCatalogProvider.DEFAULT_PATH to buildValidCatalog())),
        )

        val catalog = provider.load()

        assertEquals(1, catalog.count)
        assertEquals(32349, catalog.hipAt(0))
        assertEquals(6.5, catalog.magLimit, 1e-9)
    }

    @Test
    fun `uses catalog stars_real bin as the default asset path`() {
        assertEquals("catalog/stars_real.bin", AssetRealStarCatalogProvider.DEFAULT_PATH)
    }

    @Test
    fun `reads from a custom path when provided`() {
        val provider = AssetRealStarCatalogProvider(
            ByteArrayAssetProvider(mapOf("catalog/stars_real_custom.bin" to buildValidCatalog())),
            path = "catalog/stars_real_custom.bin",
        )

        assertEquals(1, provider.load().count)
    }

    @Test
    fun `missing asset fails with a clear message`() {
        val provider = AssetRealStarCatalogProvider(ByteArrayAssetProvider(emptyMap()))

        val error = assertThrows(RealStarCatalogLoadException::class.java) { provider.load() }

        assertTrue(error.message.orEmpty().contains("catalog/stars_real.bin"))
        assertTrue(error.message.orEmpty().contains("PTSKCAT0"))
        assertTrue(error.message.orEmpty().contains("asset packaging"))
    }

    @Test
    fun `invalid magic fails with a clear message`() {
        val bad = buildValidCatalog().also { bytes ->
            "BADMAGIC".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        }
        val provider = AssetRealStarCatalogProvider(
            ByteArrayAssetProvider(mapOf(AssetRealStarCatalogProvider.DEFAULT_PATH to bad)),
        )

        val error = assertThrows(RealStarCatalogLoadException::class.java) { provider.load() }

        assertTrue(error.message.orEmpty().contains("catalog/stars_real.bin"))
        assertTrue(error.message.orEmpty().contains("PTSKCAT0"))
        assertTrue(error.cause is IllegalArgumentException)
    }

    @Test
    fun `truncated asset fails with a clear message`() {
        val truncated = buildValidCatalog().copyOfRange(0, 10)
        val provider = AssetRealStarCatalogProvider(
            ByteArrayAssetProvider(mapOf(AssetRealStarCatalogProvider.DEFAULT_PATH to truncated)),
        )

        val error = assertThrows(RealStarCatalogLoadException::class.java) { provider.load() }

        assertTrue(error.message.orEmpty().contains("PTSKCAT0"))
    }
}
