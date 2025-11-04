package dev.pointtosky.core.catalog.constellation

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class BinaryConstellationBoundaries(
    assetProvider: AssetProvider,
    path: String = DEFAULT_PATH,
) : ConstellationBoundaries {

    private val constellations: List<RuntimeConstellation>

    init {
        val bytes = assetProvider.open(path).use(InputStream::readBytes)
        constellations = parse(bytes)
    }

    override fun findByEq(eq: Equatorial): String? {
        val ra = normalizeRa(eq.raDeg)
        val dec = eq.decDeg
        for (constellation in constellations) {
            if (!constellation.aabb.contains(ra, dec)) continue
            for (polygon in constellation.polygons) {
                if (!polygon.aabb.contains(ra, dec)) continue
                if (polygon.contains(ra, dec)) {
                    return constellation.code
                }
            }
        }
        return null
    }

    private fun parse(bytes: ByteArray): List<RuntimeConstellation> {
        if (bytes.size < HEADER_SIZE) {
            error("Binary constellation file is too small")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(8)
        buffer.get(magicBytes)
        val magic = magicBytes.toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Unexpected magic: $magic" }

        val version = buffer.short.toInt() and 0xFFFF
        require(version == VERSION) { "Unsupported version: $version" }
        buffer.short // reserved
        val countConst = buffer.short.toInt() and 0xFFFF
        val polyCount = buffer.int
        val vertexCount = buffer.int
        val storedCrc = buffer.int

        require(countConst == CONSTELLATION_COUNT) { "Unexpected constellation count: $countConst" }
        require(polyCount >= 0) { "Negative polygon count" }
        require(vertexCount >= 0) { "Negative vertex count" }

        val dataOffset = buffer.position()
        val dataBytes = bytes.copyOfRange(dataOffset, bytes.size)
        val crc = CRC32().apply { update(dataBytes) }.value.toInt()
        require(crc == storedCrc) { "CRC mismatch: expected $storedCrc, actual $crc" }

        val directories = List(countConst) {
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
            DirectoryEntry(code, polyStart, polyCountEntry, Aabb(raMin, raMax, decMin, decMax))
        }

        val polygons = List(polyCount) {
            val vertexStart = buffer.int
            val vertexCountEntry = buffer.int
            val raMin = buffer.float
            val raMax = buffer.float
            val decMin = buffer.float
            val decMax = buffer.float
            PolygonEntry(vertexStart, vertexCountEntry, Aabb(raMin, raMax, decMin, decMax))
        }

        val vertices = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            vertices[i * 2] = buffer.float
            vertices[i * 2 + 1] = buffer.float
        }

        val runtimePolygons = polygons.map { buildRuntimePolygon(it, vertices) }

        return directories.map { entry ->
            val start = entry.polyStart
            val end = start + entry.polyCount
            val subset = if (start in runtimePolygons.indices) {
                runtimePolygons.subList(start, min(end, runtimePolygons.size))
            } else {
                emptyList()
            }
            RuntimeConstellation(entry.code, entry.aabb, subset)
        }
    }

    private fun buildRuntimePolygon(entry: PolygonEntry, vertices: FloatArray): RuntimePolygon {
        val start = entry.vertexStart
        val count = entry.vertexCount
        if (count <= 0) {
            return RuntimePolygon(entry.aabb, DoubleArray(0), DoubleArray(0), 0.0, 0.0)
        }
        val raValues = DoubleArray(count)
        val decValues = DoubleArray(count)
        for (i in 0 until count) {
            val index = (start + i) * 2
            raValues[i] = normalizeRa(vertices[index].toDouble())
            decValues[i] = vertices[index + 1].toDouble()
        }
        var actualCount = count
        if (actualCount >= 2 && approximatelyEqual(raValues[0], raValues[actualCount - 1]) &&
            approximatelyEqual(decValues[0], decValues[actualCount - 1])
        ) {
            actualCount -= 1
        }
        val trimmedRa = raValues.copyOf(actualCount)
        val trimmedDec = decValues.copyOf(actualCount)
        val unwrapped = unwrap(trimmedRa)
        val rangeMin = unwrapped.minOrNull() ?: 0.0
        val rangeMax = unwrapped.maxOrNull() ?: 0.0
        return RuntimePolygon(entry.aabb, unwrapped, trimmedDec, rangeMin, rangeMax)
    }

    private fun unwrap(raValues: DoubleArray): DoubleArray {
        if (raValues.isEmpty()) return raValues
        val result = DoubleArray(raValues.size)
        result[0] = raValues[0]
        var previous = raValues[0]
        for (i in 1 until raValues.size) {
            var value = raValues[i]
            val diff = value - previous
            if (diff > 180) {
                value -= 360
            } else if (diff < -180) {
                value += 360
            }
            result[i] = value
            previous = value
        }
        return result
    }

    private fun normalizeRa(value: Double): Double {
        var ra = value % 360.0
        if (ra < 0) ra += 360.0
        return if (ra == 360.0) 0.0 else ra
    }

    private fun approximatelyEqual(a: Double, b: Double): Boolean = abs(a - b) < 1e-6

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

    private data class RuntimeConstellation(
        val code: String,
        val aabb: Aabb,
        val polygons: List<RuntimePolygon>,
    )

    private data class RuntimePolygon(
        val aabb: Aabb,
        val unwrappedRa: DoubleArray,
        val dec: DoubleArray,
        val rangeMin: Double,
        val rangeMax: Double,
    ) {
        fun contains(ra: Double, dec: Double): Boolean {
            if (unwrappedRa.isEmpty()) return false
            val aligned = alignRa(ra) ?: return false
            return rayCast(aligned, dec)
        }

        private fun alignRa(ra: Double): Double? {
            var candidate = ra
            if (candidate < rangeMin) {
                val shift = ceil((rangeMin - candidate) / 360.0)
                candidate += shift * 360.0
            } else if (candidate > rangeMax) {
                val shift = ceil((candidate - rangeMax) / 360.0)
                candidate -= shift * 360.0
            }
            if (candidate < rangeMin - RA_TOLERANCE || candidate > rangeMax + RA_TOLERANCE) {
                return null
            }
            return candidate
        }

        private fun rayCast(ra: Double, decValue: Double): Boolean {
            var inside = false
            var j = unwrappedRa.lastIndex
            for (i in unwrappedRa.indices) {
                val yi = dec[i]
                val yj = dec[j]
                val xi = unwrappedRa[i]
                val xj = unwrappedRa[j]
                val denominator = yj - yi
                if (abs(denominator) < 1e-9) {
                    j = i
                    continue
                }
                val intersects = ((yi > decValue) != (yj > decValue)) &&
                    (ra < (xj - xi) * (decValue - yi) / denominator + xi)
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }
    }

    private data class Aabb(
        val raMin: Float,
        val raMax: Float,
        val decMin: Float,
        val decMax: Float,
    ) {
        private val wraps: Boolean = raMin > raMax

        fun contains(ra: Double, dec: Double): Boolean {
            if (dec < decMin - DEC_TOLERANCE || dec > decMax + DEC_TOLERANCE) return false
            return if (!wraps) {
                ra >= raMin - RA_TOLERANCE && ra <= raMax + RA_TOLERANCE
            } else {
                ra >= raMin - RA_TOLERANCE || ra <= raMax + RA_TOLERANCE
            }
        }
    }

    companion object {
        private const val MAGIC = "PTSKCONS"
        private const val VERSION = 1
        private const val HEADER_SIZE = 8 + 2 + 2 + 2 + 4 + 4 + 4
        private const val RA_TOLERANCE = 1e-5
        private const val DEC_TOLERANCE = 1e-5
        private const val CONSTELLATION_COUNT = 88
        const val DEFAULT_PATH: String = "catalog/const_v1.bin"
    }
}
