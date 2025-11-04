package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.fake.FakeStarCatalog
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

public class BinaryStarCatalog private constructor(
    private val storage: Storage,
) : StarCatalog {

    public constructor(assetProvider: AssetProvider, path: String = DEFAULT_PATH) : this(
        readFromAsset(assetProvider, path),
    )

    private val stringCache: MutableMap<Int, String?> = ConcurrentHashMap()

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> {
        val metricsEnabled = BinaryStarCatalogMetrics.enabled
        val startTime = if (metricsEnabled) System.nanoTime() else 0L

        val normalizedRa = normalizeRa(center.raDeg)
        val clampedDec = center.decDeg.coerceIn(-90.0, 90.0)
        val minDec = max(-90.0, clampedDec - radiusDeg)
        val maxDec = min(90.0, clampedDec + radiusDeg)
        val minBand = floor(minDec).toInt().coerceIn(-90, 89)
        val maxBand = floor(maxDec).toInt().coerceIn(-90, 89)
        val raIntervals = buildRaIntervals(normalizedRa, radiusDeg)

        val centerRaRad = Math.toRadians(normalizedRa)
        val centerDecRad = Math.toRadians(clampedDec)
        val sinCenterDec = sin(centerDecRad)
        val cosCenterDec = cos(centerDecRad)

        val candidates = ArrayList<Candidate>()
        var candidateChecks = 0

        for (bandId in minBand..maxBand) {
            val bandIndex = bandId + 90
            val start = storage.bandStarts[bandIndex]
            val count = storage.bandCounts[bandIndex]
            if (count <= 0) continue
            val bandEndExclusive = start + count

            for (interval in raIntervals) {
                val intervalStart = lowerBound(storage.raIndex, start, bandEndExclusive, interval.first)
                val intervalEnd = upperBound(storage.raIndex, start, bandEndExclusive, interval.second)
                for (idx in intervalStart until intervalEnd) {
                    val starIndex = storage.starIds[idx]
                    candidateChecks += 1

                    val mag = storage.magnitude[starIndex].toDouble()
                    if (magLimit != null && (!mag.isFinite() || mag > magLimit)) {
                        continue
                    }

                    val separation = angularSeparationDeg(
                        centerRaRad,
                        sinCenterDec,
                        cosCenterDec,
                        storage.raDegrees[starIndex].toDouble(),
                        storage.decDegrees[starIndex].toDouble(),
                    )
                    if (separation > radiusDeg) continue

                    val brightnessBoost = if (mag.isFinite()) BRIGHTNESS_REFERENCE_MAG - mag else 0.0
                    val score = separation - BRIGHTNESS_WEIGHT * brightnessBoost
                    candidates += Candidate(starIndex, separation, score)
                }
            }
        }

        val sorted = candidates
            .sortedWith(compareBy<Candidate> { it.score }.thenBy { it.separation }.thenBy { storage.magnitude[it.index] })
            .map { starAt(it.index) }

        if (metricsEnabled) {
            val duration = System.nanoTime() - startTime
            BinaryStarCatalogMetrics.record(duration, candidateChecks, sorted.size)
        }

        return sorted
    }

    private fun starAt(index: Int): Star {
        val hip = storage.hip[index]
        val id = if (hip > 0) hip else index
        val name = stringAt(storage.nameOffsets[index])
        val constellationIndex = storage.constellation[index].toInt()
        val constellation = constellationIndex
            .takeIf { it in CONSTELLATION_CODES.indices }
            ?.let { CONSTELLATION_CODES[it] }
        val designation = stringAt(storage.designationOffsets[index])
        val designationParts = decodeDesignation(designation, constellation)
        val bayer = designationParts?.bayer ?: designation
        val flamsteed = designationParts?.flamsteed

        return Star(
            id = id,
            raDeg = storage.raDegrees[index],
            decDeg = storage.decDegrees[index],
            mag = storage.magnitude[index],
            name = name,
            bayer = bayer,
            flamsteed = flamsteed,
            constellation = constellation,
        )
    }

    private fun stringAt(offset: Int): String? {
        if (offset <= 0 || offset >= storage.stringPool.size) {
            return null
        }
        return stringCache.getOrPut(offset) { decodeString(offset) }
    }

    private fun decodeString(offset: Int): String? {
        var end = offset
        while (end < storage.stringPool.size && storage.stringPool[end] != 0.toByte()) {
            end += 1
        }
        if (end <= offset) {
            return null
        }
        return String(storage.stringPool, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun decodeDesignation(raw: String?, constellation: String?): Designation? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val withoutSuffix = if (constellation != null && tokens.last().equals(constellation, ignoreCase = true)) {
            tokens.dropLast(1)
        } else {
            tokens
        }
        if (withoutSuffix.isEmpty()) return null
        val first = withoutSuffix.first()
        val second = withoutSuffix.getOrNull(1)
        val firstHasDigits = first.any { it.isDigit() }
        val secondHasDigits = second?.any { it.isDigit() } ?: false
        return when {
            withoutSuffix.size == 1 -> {
                if (firstHasDigits) Designation(null, first) else Designation(first, null)
            }
            !firstHasDigits && secondHasDigits -> Designation(first, second)
            firstHasDigits && !secondHasDigits -> Designation(second, first)
            firstHasDigits && secondHasDigits -> Designation(null, first)
            else -> Designation(first, second)
        }
    }

    private data class Designation(val bayer: String?, val flamsteed: String?)

    private data class Candidate(val index: Int, val separation: Double, val score: Double)

    private data class Storage(
        val stringPool: ByteArray,
        val raDegrees: FloatArray,
        val decDegrees: FloatArray,
        val magnitude: FloatArray,
        val hip: IntArray,
        val nameOffsets: IntArray,
        val designationOffsets: IntArray,
        val constellation: ShortArray,
        val bandStarts: IntArray,
        val bandCounts: IntArray,
        val starIds: IntArray,
        val raIndex: FloatArray,
    )

    private data class Header(
        val recordCount: Int,
        val stringPoolSize: Int,
        val indexOffset: Int,
        val indexSize: Int,
    )

    public companion object {
        public const val DEFAULT_PATH: String = "catalog/stars_v1.bin"
        private const val TAG: String = "BinaryStarCatalog"
        private const val MAGIC: String = "PTSKSTAR"
        private const val SUPPORTED_VERSION: Int = 1
        private const val HEADER_SIZE_BYTES: Int = 32
        private const val RECORD_SIZE_BYTES: Int = 32
        private const val BAND_COUNT: Int = 180
        private const val BRIGHTNESS_REFERENCE_MAG: Double = 6.5
        private const val BRIGHTNESS_WEIGHT: Double = 0.5
        private val CONSTELLATION_CODES: Array<String> = arrayOf(
            "AND", "ANT", "APS", "AQL", "AQR", "ARA", "ARI", "AUR",
            "BOO", "CAE", "CAM", "CAP", "CAR", "CAS", "CEN", "CEP",
            "CET", "CHA", "CIR", "CMA", "CMI", "CNC", "COL", "COM",
            "CRA", "CRB", "CRT", "CRU", "CRV", "CVN", "CYG", "DEL",
            "DOR", "DRA", "EQU", "ERI", "FOR", "GEM", "GRU", "HER",
            "HOR", "HYA", "HYI", "IND", "LAC", "LEO", "LEP", "LIB",
            "LUP", "LYN", "LYR", "MEN", "MIC", "MON", "MUS", "NOR",
            "OCT", "OPH", "ORI", "PAV", "PEG", "PER", "PHE", "PIC",
            "PSA", "PSC", "PUP", "PYX", "RET", "SCL", "SCO", "SCT",
            "SER", "SEX", "SGE", "SGR", "TAU", "TEL", "TRA", "TRI",
            "TUC", "UMA", "UMI", "VEL", "VIR", "VOL", "VUL",
        )
        private val WHITESPACE_REGEX: Regex = "\\s+".toRegex()

        public fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: StarCatalog = FakeStarCatalog(),
            logger: Logger = LogBus,
        ): StarCatalog {
            return try {
                BinaryStarCatalog(assetProvider, path)
            } catch (ioe: IOException) {
                logger.e(TAG, "Failed to open star catalog", ioe, mapOf("path" to path))
                fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Failed to load star catalog", ex, mapOf("path" to path))
                fallback
            }
        }

        private fun readFromAsset(assetProvider: AssetProvider, path: String): Storage {
            val bytes = assetProvider.open(path).use { it.readBytes() }
            if (bytes.size < HEADER_SIZE_BYTES) {
                throw IllegalArgumentException("Star catalog is truncated")
            }
            val header = parseHeader(bytes)
            val data = bytes.copyOfRange(HEADER_SIZE_BYTES, bytes.size)
            if (data.size < header.stringPoolSize) {
                throw IllegalArgumentException("String pool truncated")
            }
            val stringPool = data.copyOfRange(0, header.stringPoolSize)

            val expectedRecordBytes = header.recordCount * RECORD_SIZE_BYTES
            if (header.stringPoolSize + expectedRecordBytes > data.size) {
                throw IllegalArgumentException("Star records truncated")
            }
            val recordsBuffer = ByteBuffer.wrap(
                data,
                header.stringPoolSize,
                expectedRecordBytes,
            ).order(ByteOrder.LITTLE_ENDIAN)

            val ra = FloatArray(header.recordCount)
            val dec = FloatArray(header.recordCount)
            val mag = FloatArray(header.recordCount)
            val hip = IntArray(header.recordCount)
            val nameOffsets = IntArray(header.recordCount)
            val designationOffsets = IntArray(header.recordCount)
            val constellation = ShortArray(header.recordCount)

            repeat(header.recordCount) { index ->
                ra[index] = recordsBuffer.float
                dec[index] = recordsBuffer.float
                mag[index] = recordsBuffer.float
                recordsBuffer.float // BV placeholder
                hip[index] = recordsBuffer.int
                nameOffsets[index] = recordsBuffer.int
                designationOffsets[index] = recordsBuffer.int
                recordsBuffer.short // flags (unused)
                constellation[index] = recordsBuffer.short
            }

            if (header.indexOffset < 0 || header.indexOffset + header.indexSize > data.size) {
                throw IllegalArgumentException("Index section out of bounds")
            }
            val indexBuffer = ByteBuffer.wrap(
                data,
                header.indexOffset,
                header.indexSize,
            ).order(ByteOrder.LITTLE_ENDIAN)

            val bandStarts = IntArray(BAND_COUNT)
            val bandCounts = IntArray(BAND_COUNT)
            repeat(BAND_COUNT) { i ->
                val bandId = indexBuffer.short.toInt()
                val start = indexBuffer.int
                val count = indexBuffer.int
                bandStarts[i] = start
                bandCounts[i] = count
                if (bandId != i - 90) {
                    // Maintain compatibility but ignore mismatch.
                }
            }
            val totalEntries = bandCounts.sum()
            val starIds = IntArray(totalEntries) { indexBuffer.int }
            val raIndex = FloatArray(totalEntries) { indexBuffer.float }
            repeat(SUMMARY_FLOAT_COUNT) { indexBuffer.float }

            return Storage(
                stringPool = stringPool,
                raDegrees = ra,
                decDegrees = dec,
                magnitude = mag,
                hip = hip,
                nameOffsets = nameOffsets,
                designationOffsets = designationOffsets,
                constellation = constellation,
                bandStarts = bandStarts,
                bandCounts = bandCounts,
                starIds = starIds,
                raIndex = raIndex,
            )
        }

        private fun parseHeader(bytes: ByteArray): Header {
            val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            buffer.get(magicBytes)
            val magic = String(magicBytes, StandardCharsets.US_ASCII)
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid catalog magic: $magic")
            }
            val version = buffer.short.toInt() and 0xFFFF
            if (version != SUPPORTED_VERSION) {
                throw IllegalArgumentException("Unsupported catalog version: $version")
            }
            buffer.short // reserved
            val recordCount = buffer.int
            val stringPoolSize = buffer.int
            val indexOffset = buffer.int
            val indexSize = buffer.int
            val crc = buffer.int.toLong() and 0xFFFF_FFFFL

            if (recordCount < 0 || stringPoolSize < 0 || indexOffset < 0 || indexSize < 0) {
                throw IllegalArgumentException("Negative header values")
            }

            val payload = bytes.copyOfRange(HEADER_SIZE_BYTES, bytes.size)
            val computed = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computed != crc) {
                throw IllegalStateException("CRC mismatch: expected=${java.lang.Long.toHexString(crc)} actual=${java.lang.Long.toHexString(computed)}")
            }

            return Header(recordCount, stringPoolSize, indexOffset, indexSize)
        }

        private fun normalizeRa(value: Double): Double {
            var result = value % 360.0
            if (result < 0) {
                result += 360.0
            }
            return result
        }

        private fun buildRaIntervals(center: Double, radiusDeg: Double): List<Pair<Double, Double>> {
            if (radiusDeg >= 180.0) {
                return listOf(0.0 to 360.0)
            }
            val start = center - radiusDeg
            val end = center + radiusDeg
            return when {
                start < 0 && end >= 360 -> listOf(0.0 to 360.0)
                start < 0 -> listOf(0.0 to end.coerceAtMost(360.0), (start + 360.0) to 360.0)
                end >= 360 -> listOf(0.0 to (end - 360.0), start.coerceAtLeast(0.0) to 360.0)
                else -> listOf(start to end)
            }
        }

        private fun lowerBound(values: FloatArray, from: Int, to: Int, key: Double): Int {
            var low = from
            var high = to
            while (low < high) {
                val mid = (low + high) ushr 1
                val value = values[mid].toDouble()
                if (value < key) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

        private fun upperBound(values: FloatArray, from: Int, to: Int, key: Double): Int {
            var low = from
            var high = to
            while (low < high) {
                val mid = (low + high) ushr 1
                val value = values[mid].toDouble()
                if (value <= key) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

        private fun angularSeparationDeg(
            ra1Rad: Double,
            sinDec1: Double,
            cosDec1: Double,
            ra2Deg: Double,
            dec2Deg: Double,
        ): Double {
            val ra2Rad = Math.toRadians(normalizeRa(ra2Deg))
            val dec2Rad = Math.toRadians(dec2Deg.coerceIn(-90.0, 90.0))
            val sinDec2 = sin(dec2Rad)
            val cosDec2 = cos(dec2Rad)
            val deltaRa = ra1Rad - ra2Rad
            val cosine = sinDec1 * sinDec2 + cosDec1 * cosDec2 * cos(deltaRa)
            val clamped = cosine.coerceIn(-1.0, 1.0)
            return Math.toDegrees(acos(clamped))
        }

        private const val SUMMARY_FLOAT_COUNT: Int = 8
    }
}

public object BinaryStarCatalogMetrics {
    @Volatile
    public var enabled: Boolean = false

    @Volatile
    public var lastQueryDurationNanos: Long = 0L
        private set

    @Volatile
    public var lastCandidateCount: Int = 0
        private set

    @Volatile
    public var lastResultCount: Int = 0
        private set

    @Volatile
    public var totalQueries: Long = 0
        private set

    internal fun record(durationNanos: Long, candidateCount: Int, resultCount: Int) {
        synchronized(this) {
            totalQueries += 1
            lastQueryDurationNanos = durationNanos
            lastCandidateCount = candidateCount
            lastResultCount = resultCount
        }
    }
}
