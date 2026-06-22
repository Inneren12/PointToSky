package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.catalog.fake.FakeConstellationBoundaries
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.min

/**
 * Status indicating whether real or fake constellation boundaries are in use.
 */
sealed class ConstellationBoundariesStatus {
    /**
     * Successfully loaded real constellation boundaries from binary file.
     */
    data class Real(val metadata: BinaryConstellationBoundaries.Metadata) : ConstellationBoundariesStatus()

    /**
     * Using fake/fallback constellation boundaries due to load failure.
     */
    data class Fake(val reason: String) : ConstellationBoundariesStatus()
}

/**
 * Result of loading constellation boundaries, including status information.
 */
data class BoundariesLoadResult(
    val boundaries: ConstellationBoundaries,
    val status: ConstellationBoundariesStatus,
)

class BinaryConstellationBoundaries private constructor(
    private val constellations: List<RuntimeConstellation>,
    val metadata: Metadata,
) : ConstellationBoundaries {

    override fun findByEq(eq: Equatorial): String? {
        val ra = normalizeRa(eq.raDeg)
        val dec = eq.decDeg
        for (constellation in constellations) {
            for (polygon in constellation.polygons) {
                if (polygon.contains(ra, dec)) return constellation.code
            }
        }
        return null
    }

    private data class RuntimeConstellation(
        val code: String,
        val polygons: List<RuntimePolygon>,
    )

    private class RuntimePolygon(
        private val ra: DoubleArray,           // normalized [0, 360)
        private val dec: DoubleArray,
        private val enclosesNorthPole: Boolean,
        private val enclosesSouthPole: Boolean,
    ) {
        fun contains(qRa: Double, qDec: Double): Boolean {
            val n = ra.size
            if (n == 0) return false
            val up = qDec <= 0.0                // cast ray toward the opposite pole
            var crossings = 0
            for (i in 0 until n) {
                val j = if (i + 1 < n) i + 1 else 0
                val x1 = sdiff(ra[i], qRa)
                val x2 = x1 + sdiff(ra[j], ra[i]) // unwrap edge end relative to its start
                val y1 = dec[i] - qDec
                val y2 = dec[j] - qDec
                if ((x1 <= 0.0 && 0.0 < x2) || (x2 <= 0.0 && 0.0 < x1)) {
                    val yc = y1 + (y2 - y1) * (-x1) / (x2 - x1)
                    if ((up && yc > 0.0) || (!up && yc < 0.0)) crossings++
                }
            }
            var inside = crossings % 2 == 1
            if (up && enclosesNorthPole) inside = !inside
            if (!up && enclosesSouthPole) inside = !inside
            return inside
        }
    }

    // Aabb is kept as a plain data holder so parseConstellations keeps the buffer aligned.
    private data class Aabb(
        val raMin: Float,
        val raMax: Float,
        val decMin: Float,
        val decMax: Float,
    )

    companion object {
        private const val TAG = "BinaryConstellation"
        private const val MAGIC = "PTSKCONS"
        // 8(magic) + 2(version) + 2(reserved) + 2(constCount) + 4(polyCount) + 4(vertexCount) + 4(crc32)
        private const val HEADER_SIZE = 26
        private const val SUPPORTED_VERSION = 1
        const val DEFAULT_PATH = "catalog/const_v1.bin"

        fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: ConstellationBoundaries = FakeConstellationBoundaries,
            logger: Logger = LogBus,
        ): BoundariesLoadResult {
            fun returnFallback(reason: String, exception: Exception? = null): BoundariesLoadResult {
                if (exception != null) {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", exception, mapOf("path" to path, "reason" to reason))
                } else {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", payload = mapOf("path" to path, "reason" to reason))
                }
                return BoundariesLoadResult(boundaries = fallback, status = ConstellationBoundariesStatus.Fake(reason))
            }

            val bytes = try {
                assetProvider.open(path).use { it.readBytes() }
            } catch (ioe: IOException) {
                return returnFallback("Failed to open file", ioe)
            } catch (ex: Exception) {
                return returnFallback("Unexpected error while opening file", ex)
            }

            if (bytes.size < HEADER_SIZE) {
                return returnFallback("Invalid header")
            }

            val hdr = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            hdr.get(magicBytes)
            if (magicBytes.toString(Charsets.US_ASCII) != MAGIC) {
                return returnFallback("Invalid header")
            }
            val version = hdr.short.toInt() and 0xFFFF
            if (version != SUPPORTED_VERSION) {
                return returnFallback("Unsupported catalog version=$version")
            }
            hdr.short // reserved
            val constellationCount = hdr.short.toInt() and 0xFFFF
            val polygonCount = hdr.int
            val vertexCount = hdr.int
            val storedCrc = hdr.int.toLong() and 0xFFFF_FFFFL

            if (polygonCount < 0 || vertexCount < 0) {
                return returnFallback("Negative counts in header")
            }

            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            val computedCrc = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computedCrc != storedCrc) {
                return returnFallback("CRC mismatch expected=$storedCrc actual=$computedCrc")
            }

            val constellations = try {
                val buffer = ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                parseConstellations(buffer, constellationCount, polygonCount, vertexCount)
            } catch (buf: BufferUnderflowException) {
                return returnFallback("Unexpected EOF while parsing", buf)
            } catch (ex: Exception) {
                return returnFallback("Failed to parse constellation boundaries", ex)
            }

            val metadata = Metadata(
                sizeBytes = bytes.size,
                recordCount = constellationCount,
                payloadCrc32 = storedCrc,
            )
            logger.i(TAG, "Constellation boundaries loaded successfully", payload = mapOf(
                "path" to path,
                "sizeBytes" to metadata.sizeBytes,
                "crc32" to metadata.payloadCrc32,
                "count" to metadata.recordCount,
            ))

            return BoundariesLoadResult(
                boundaries = BinaryConstellationBoundaries(constellations, metadata),
                status = ConstellationBoundariesStatus.Real(metadata),
            )
        }

        private fun parseConstellations(
            buffer: ByteBuffer,
            constellationCount: Int,
            polygonCount: Int,
            vertexCount: Int,
        ): List<RuntimeConstellation> {
            val directories = ArrayList<DirectoryEntry>(constellationCount)
            repeat(constellationCount) {
                val codeBytes = ByteArray(4)
                buffer.get(codeBytes)
                val code = codeBytes.takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.US_ASCII)
                    .trim()
                val polyStart = buffer.int
                val polyCountEntry = buffer.int
                val raMin = buffer.float
                val raMax = buffer.float
                val decMin = buffer.float
                val decMax = buffer.float
                directories += DirectoryEntry(code, polyStart, polyCountEntry, Aabb(raMin, raMax, decMin, decMax))
            }

            val polygonEntries = ArrayList<PolygonEntry>(polygonCount)
            repeat(polygonCount) {
                val vertexStart = buffer.int
                val vCount = buffer.int
                val raMin = buffer.float
                val raMax = buffer.float
                val decMin = buffer.float
                val decMax = buffer.float
                polygonEntries += PolygonEntry(vertexStart, vCount, Aabb(raMin, raMax, decMin, decMax))
            }

            val vertices = FloatArray(vertexCount * 2)
            for (i in 0 until vertexCount) {
                vertices[i * 2] = buffer.float
                vertices[i * 2 + 1] = buffer.float
            }

            val runtimePolygons = polygonEntries.map { buildRuntimePolygon(it, vertices) }

            return directories.map { entry ->
                val start = entry.polyStart
                val end = start + entry.polyCount
                val subset = if (start in runtimePolygons.indices) {
                    runtimePolygons.subList(start, min(end, runtimePolygons.size))
                } else {
                    emptyList()
                }
                RuntimeConstellation(entry.code, subset)
            }
        }

        private fun buildRuntimePolygon(entry: PolygonEntry, vertices: FloatArray): RuntimePolygon {
            val count = entry.vertexCount
            if (count <= 0) return RuntimePolygon(DoubleArray(0), DoubleArray(0), false, false)

            val start = entry.vertexStart
            val ra = DoubleArray(count) { normalizeRa(vertices[(start + it) * 2].toDouble()) }
            val dec = DoubleArray(count) { vertices[(start + it) * 2 + 1].toDouble() }

            // Remove duplicate closing vertex if present
            var n = count
            if (n >= 2 && abs(ra[0] - ra[n - 1]) < 1e-6 && abs(dec[0] - dec[n - 1]) < 1e-6) n -= 1

            val raT = ra.copyOf(n)
            val decT = dec.copyOf(n)

            // Precompute pole-enclosure flags via RA winding sum
            var wind = 0.0
            for (i in 0 until n) {
                val j = if (i + 1 < n) i + 1 else 0
                wind += sdiff(raT[j], raT[i])
            }
            val meanDec = if (n == 0) 0.0 else decT.sum() / n
            return RuntimePolygon(raT, decT, abs(wind) > 180.0 && meanDec > 0, abs(wind) > 180.0 && meanDec < 0)
        }

        // Signed shortest RA delta in (-180, 180]: sdiff(a, b) = a - b wrapped.
        private fun sdiff(a: Double, b: Double): Double {
            var d = (a - b) % 360.0
            if (d < -180.0) d += 360.0
            if (d >= 180.0) d -= 360.0
            return d
        }

        private fun normalizeRa(value: Double): Double {
            var ra = value % 360.0
            if (ra < 0) ra += 360.0
            return if (ra == 360.0) 0.0 else ra
        }

        private data class DirectoryEntry(
            val code: String,
            val polyStart: Int,
            val polyCount: Int,
            val aabb: Aabb,
        )

        private data class PolygonEntry(
            val vertexStart: Int,
            val vertexCount: Int,
            val aabb: Aabb,
        )
    }

    data class Metadata(
        val sizeBytes: Int,
        val recordCount: Int,
        val payloadCrc32: Long,
    )
}
