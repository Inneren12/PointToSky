package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.SkyCatalog
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.astro.identify.Type
import dev.pointtosky.core.astro.identify.angularSeparationDeg
import dev.pointtosky.core.catalog.fake.FakeSkyCatalog
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

public class BinaryStarCatalog private constructor(
    private val stars: List<SkyObject>,
) : SkyCatalog {
    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<SkyObject> {
        return stars.filter { star ->
            val separation = angularSeparationDeg(center, star.eq)
            val magCheck = magLimit?.let { limit -> star.mag?.let { it <= limit } ?: true } ?: true
            separation <= radiusDeg && magCheck
        }
    }

    public companion object {
        private const val TAG: String = "BinaryStarCatalog"
        private const val EXPECTED_TYPE: String = "STAR"
        public const val DEFAULT_PATH: String = "catalog/stars_v1.bin"
        private const val SUPPORTED_VERSION: Int = 1

        public fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: SkyCatalog = FakeSkyCatalog,
            logger: Logger = LogBus,
        ): SkyCatalog {
            val bytes = try {
                assetProvider.open(path).use { it.readBytes() }
            } catch (ioe: IOException) {
                logger.e(TAG, "Failed to open star catalog", ioe, mapOf("path" to path))
                return fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Unexpected error while opening star catalog", ex, mapOf("path" to path))
                return fallback
            }

            val (header, buffer) = BinaryCatalogHeader.read(bytes) ?: run {
                logger.e(TAG, "Invalid header", payload = mapOf("path" to path))
                return fallback
            }
            if (header.type != EXPECTED_TYPE || header.version != SUPPORTED_VERSION) {
                logger.e(
                    TAG,
                    "Unsupported catalog",
                    payload = mapOf("path" to path, "type" to header.type, "version" to header.version),
                )
                return fallback
            }
            if (header.recordCount < 0) {
                logger.e(
                    TAG,
                    "Negative record count",
                    payload = mapOf("path" to path, "count" to header.recordCount),
                )
                return fallback
            }

            val payload = bytes.copyOfRange(BinaryCatalogHeader.HEADER_SIZE_BYTES, bytes.size)
            val computedCrc = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computedCrc != header.payloadCrc32) {
                logger.e(
                    TAG,
                    "CRC mismatch",
                    payload = mapOf(
                        "path" to path,
                        "expectedCrc" to header.payloadCrc32,
                        "actualCrc" to computedCrc,
                    ),
                )
                return fallback
            }

            val stars = try {
                buffer.position(BinaryCatalogHeader.HEADER_SIZE_BYTES)
                parseStars(buffer, header.recordCount)
            } catch (buf: BufferUnderflowException) {
                logger.e(TAG, "Unexpected EOF while parsing star catalog", buf, mapOf("path" to path))
                return fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Failed to parse star catalog", ex, mapOf("path" to path))
                return fallback
            }
            return BinaryStarCatalog(stars)
        }

        private fun parseStars(buffer: ByteBuffer, recordCount: Int): List<SkyObject> {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val list = ArrayList<SkyObject>(recordCount.coerceAtLeast(0))
            repeat(recordCount.coerceAtLeast(0)) {
                val id = buffer.readUtf8String() ?: throw IllegalStateException("Missing star id")
                val name = buffer.readNullableUtf8String()
                val raDeg = buffer.double
                val decDeg = buffer.double
                val magnitude = buffer.float
                val magValue = if (magnitude.isNaN()) null else magnitude.toDouble()
                list += SkyObject(
                    id = id,
                    name = name,
                    eq = Equatorial(raDeg, decDeg),
                    mag = magValue,
                    type = Type.STAR,
                )
            }
            return list
        }

        private fun ByteBuffer.readUtf8String(): String? {
            if (remaining() < Int.SIZE_BYTES) {
                return null
            }
            val length = int
            if (length < 0 || remaining() < length) {
                return null
            }
            val bytes = ByteArray(length)
            get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun ByteBuffer.readNullableUtf8String(): String? {
            if (remaining() < Int.SIZE_BYTES) {
                return null
            }
            val length = int
            if (length < 0) {
                return null
            }
            if (remaining() < length) {
                return null
            }
            val bytes = ByteArray(length)
            get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }
}
