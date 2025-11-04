package dev.pointtosky.core.catalog.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class BinaryCatalogHeader(
    val magic: String,
    val version: Int,
    val starCount: Int,
    val stringPoolSize: Int,
    val indexOffset: Int,
    val indexSize: Int,
    val payloadCrc32: Long,
) {
    companion object {
        const val HEADER_SIZE_BYTES: Int = 32
        private const val MAGIC_EXPECTED: String = "PTSKSTAR"

        fun read(bytes: ByteArray): Pair<BinaryCatalogHeader, ByteBuffer>? {
            if (bytes.size < HEADER_SIZE_BYTES) {
                return null
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.readAscii(8) ?: return null
            if (magic != MAGIC_EXPECTED) {
                return null
            }
            if (buffer.remaining() < (Short.SIZE_BYTES * 2 + Int.SIZE_BYTES * 5)) {
                return null
            }
            val version = buffer.short.toInt() and 0xFFFF
            buffer.short // reserved, skip
            val starCount = buffer.int
            val stringPoolSize = buffer.int
            val indexOffset = buffer.int
            val indexSize = buffer.int
            val crc = buffer.int.toLong() and 0xFFFF_FFFFL
            val header = BinaryCatalogHeader(
                magic = magic,
                version = version,
                starCount = starCount,
                stringPoolSize = stringPoolSize,
                indexOffset = indexOffset,
                indexSize = indexSize,
                payloadCrc32 = crc,
            )
            return header to buffer
        }

        private fun ByteBuffer.readAscii(length: Int): String? {
            if (remaining() < length) {
                return null
            }
            val array = ByteArray(length)
            get(array)
            return String(array, StandardCharsets.US_ASCII)
        }
    }
}
