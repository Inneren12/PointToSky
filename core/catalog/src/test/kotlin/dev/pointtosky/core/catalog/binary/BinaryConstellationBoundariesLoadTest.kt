package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.ByteArrayAssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Юнит-тест загрузчика границ созвездий: собираем минимальный валидный бинарник:
 *   header: "PTSK" + "CONS" + version=1 + recordCount=1 + CRC32(payload)
 *   payload: 1 запись с IAU="ORI", [80..100] RA, [-10..10] Dec.
 */
class BinaryConstellationBoundariesLoadTest {

    @Test
    fun `load one region and find point`() {
        val payload = buildPayload(
            code = "ORI",
            minRa = 80.0,
            maxRa = 100.0,
            minDec = -10.0,
            maxDec = 10.0,
        )
        val crc = CRC32().apply { update(payload) }.value.toInt()

        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSK".toByteArray(StandardCharsets.US_ASCII)) // magic
            put("CONS".toByteArray(StandardCharsets.US_ASCII)) // type
            putInt(1) // version
            putInt(1) // recordCount
            putInt(crc) // payloadCrc32
        }.array()

        val fileBytes = header + payload
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to fileBytes),
        )

        val loaded = BinaryConstellationBoundaries.load(provider)
        // Должен вернуться именно бинарный загрузчик, а не фоллбэк
        assertTrue(loaded is BinaryConstellationBoundaries)
        val bin = loaded as BinaryConstellationBoundaries

        // Точка внутри прямоугольника — ожидаем "ORI"
        val hit = bin.findByEq(Equatorial(90.0, 0.0))
        assertEquals("ORI", hit)
        // Метаданные соответствуют 1 записи
        assertEquals(1, bin.metadata.recordCount)
    }

    private fun buildPayload(code: String, minRa: Double, maxRa: Double, minDec: Double, maxDec: Double): ByteArray {
        val nameBytes = code.toByteArray(StandardCharsets.UTF_8)
        val bb = ByteBuffer.allocate(4 + nameBytes.size + 8 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nameBytes.size)
        bb.put(nameBytes)
        bb.putDouble(minRa)
        bb.putDouble(maxRa)
        bb.putDouble(minDec)
        bb.putDouble(maxDec)
        return bb.array()
    }
}
