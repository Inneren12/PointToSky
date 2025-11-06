package dev.pointtosky.core.catalog.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Проверяем разбор заголовка PTSKSTAR (см. BinaryCatalogHeader). Тест JVM-only.
 */
class BinaryCatalogHeaderTest {
    @Test
    fun `read valid header`() {
        // Собираем корректные 32 байта заголовка
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSKSTAR".toByteArray(StandardCharsets.US_ASCII)) // 8 байт
            putShort(1)     // version
            putShort(0)     // reserved
            putInt(10)      // starCount
            putInt(100)     // stringPoolSize
            putInt(420)     // indexOffset
            putInt(128)     // indexSize
            putInt(0x12345678.toInt()) // payloadCrc32 (как int LE)
        }.array()

        val pair = BinaryCatalogHeader.read(header)
        assertNotNull(pair)
        val (parsed, _) = pair!!
        assertEquals(1, parsed.version)
        assertEquals(10, parsed.starCount)
        assertEquals(100, parsed.stringPoolSize)
        assertEquals(420, parsed.indexOffset)
        assertEquals(128, parsed.indexSize)
        assertEquals(0x12345678L, parsed.payloadCrc32)
    }
}

