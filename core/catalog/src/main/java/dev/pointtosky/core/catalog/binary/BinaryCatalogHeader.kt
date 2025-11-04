package dev.pointtosky.core.catalog.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class BinaryCatalogHeader(
    val magic: String,
    val type: String,
    val version: Int,
    val recordCount: Int,
    val payloadCrc32: Long,
) {
    companion object {
        const val HEADER_SIZE_BYTES: Int = 4 + 4 + 4 + 4 + 4
        private const val MAGIC_EXPECTED: String = "PTSK"

        fun read(bytes: ByteArray): Pair<BinaryCatalogHeader, ByteBuffer>? {
            if (bytes.size < HEADER_SIZE_BYTES) {
                return null
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.readAscii(4) ?: return null
            val type = buffer.readAscii(4) ?: return null
            if (buffer.remaining() < Int.SIZE_BYTES * 3) {
                return null
            }
            val version = buffer.int
            val recordCount = buffer.int
            val crc = buffer.int.toLong() and 0xFFFF_FFFFL
            val header = BinaryCatalogHeader(
                magic = magic,
                type = type,
                version = version,
                recordCount = recordCount,
                payloadCrc32 = crc,
            )
            if (header.magic != MAGIC_EXPECTED) {
                return null
            }
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
