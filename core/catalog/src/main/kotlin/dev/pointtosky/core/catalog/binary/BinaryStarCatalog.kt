package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.catalog.BuildConfig
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.catalog.star.FakeStarCatalog
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

public class BinaryStarCatalog private constructor(
    private val data: CatalogData,
    public val metadata: Metadata,
) : StarCatalog {

    private val stringCache = ConcurrentHashMap<Int, String>()

    override fun nearby(center: dev.pointtosky.core.astro.coord.Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> {
        if (data.count == 0 || radiusDeg <= 0.0) {
            return emptyList()
        }

        val radius = radiusDeg.coerceAtMost(180.0)
        val metricsEnabled = BinaryStarCatalogMetrics.enabled
        val startNs = if (metricsEnabled) System.nanoTime() else 0L

        val normRa = normalizeRa(center.raDeg)
        val clampDec = center.decDeg.coerceIn(-90.0, 90.0)
        val centerRaRad = Math.toRadians(normRa)
        val centerDecRad = Math.toRadians(clampDec)
        val sinDec = sin(centerDecRad)
        val cosDec = cos(centerDecRad)

        val minDec = max(-90.0, clampDec - radius)
        val maxDec = min(90.0, clampDec + radius)
        val minBand = floor(minDec).toInt().coerceIn(MIN_BAND_ID, MAX_BAND_ID)
        val maxBand = floor(maxDec).toInt().coerceIn(MIN_BAND_ID, MAX_BAND_ID)

        val raRanges = buildRaRanges(normRa, radius)
        val candidates = ArrayList<Candidate>()
        var inspected = 0
        val limit = magLimit

        for (band in minBand..maxBand) {
            val bandIdx = band - MIN_BAND_ID
            val start = data.bandOffsets[bandIdx]
            val count = data.bandCounts[bandIdx]
            if (count == 0) continue
            val end = start + count

            if (raRanges.isEmpty()) {
                for (cursor in start until end) {
                    inspected += processCandidate(cursor, centerRaRad, sinDec, cosDec, radius, limit, candidates)
                }
            } else {
                ranges@ for (range in raRanges) {
                    if (range.coversAll) {
                        for (cursor in start until end) {
                            inspected += processCandidate(cursor, centerRaRad, sinDec, cosDec, radius, limit, candidates)
                        }
                        break@ranges
                    }
                    val lower = lowerBound(data.raByBand, start, end, range.min)
                    val upper = upperBound(data.raByBand, lower, end, range.max)
                    if (upper <= lower) continue
                    for (cursor in lower until upper) {
                        inspected += processCandidate(cursor, centerRaRad, sinDec, cosDec, radius, limit, candidates)
                    }
                }
            }
        }

        candidates.sortWith(compareBy<Candidate>({ it.separationDeg }, { it.magnitude }))
        val result = candidates.map { candidate ->
            data.toStar(candidate.starIndex, ::decodeString, ::splitDesignation)
        }

        if (metricsEnabled) {
            val duration = System.nanoTime() - startNs
            BinaryStarCatalogMetrics.record(duration, inspected)
        }

        return result
    }

    private fun processCandidate(
        cursor: Int,
        centerRaRad: Double,
        sinDec: Double,
        cosDec: Double,
        radius: Double,
        limit: Double?,
        sink: MutableList<Candidate>,
    ): Int {
        val starIndex = data.starIdsByBand[cursor]
        if (starIndex !in 0 until data.count) return 1

        val magnitude = data.mag[starIndex]
        if (limit != null) {
            if (magnitude.isNaN() || magnitude.toDouble() > limit) {
                return 1
            }
        }

        val separation = angularSeparationDeg(
            centerRaRad,
            sinDec,
            cosDec,
            data.raRadians[starIndex],
            data.decRadians[starIndex],
        )
        if (separation <= radius) {
            sink += Candidate(starIndex, separation, magnitude.toDouble())
        }
        return 1
    }

    private fun decodeString(offset: Int): String? {
        if (offset <= 0 || offset >= data.stringPool.size) {
            return null
        }
        return stringCache.computeIfAbsent(offset) {
            var end = offset
            val bytes = data.stringPool
            while (end < bytes.size && bytes[end] != 0.toByte()) {
                end += 1
            }
            String(bytes, offset, end - offset, StandardCharsets.UTF_8)
        }
    }

    private fun splitDesignation(designation: String?, constellation: String?): Pair<String?, String?> {
        if (designation.isNullOrBlank()) return null to null
        val trimmed = designation.trim()
        val suffix = constellation?.takeIf { trimmed.endsWith(it, ignoreCase = true) }
        val body = if (suffix != null) {
            trimmed.dropLast(suffix.length).trimEnd()
        } else {
            trimmed
        }
        if (body.isEmpty()) {
            return designation to null
        }
        val tokens = body.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return designation to null
        }
        val flamsteedTokens = tokens.filter { token -> token.any(Char::isDigit) }
        val bayerTokens = tokens.filterNot { token -> token.any(Char::isDigit) }
        val bayer = bayerTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")
        val flamsteed = flamsteedTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { value ->
            if (suffix != null && !value.endsWith(suffix, ignoreCase = true)) {
                "$value $suffix"
            } else {
                value
            }
        }
        if (bayer == null && flamsteed == null) {
            return designation to null
        }
        return bayer to flamsteed
    }

    private fun angularSeparationDeg(
        centerRa: Double,
        sinDec: Double,
        cosDec: Double,
        candidateRa: Double,
        candidateDec: Double,
    ): Double {
        val cosine = sinDec * sin(candidateDec) + cosDec * cos(candidateDec) * cos(centerRa - candidateRa)
        val clamped = cosine.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clamped))
    }

    private data class Candidate(
        val starIndex: Int,
        val separationDeg: Double,
        val magnitude: Double,
    )

    public companion object {
        private const val TAG: String = "BinaryStarCatalog"
        public const val DEFAULT_PATH: String = "catalog/stars_v1.bin"
        private const val SUPPORTED_VERSION: Int = 1
        private const val STAR_RECORD_SIZE_BYTES: Int = 32
        private const val BAND_COUNT: Int = 180
        private const val MIN_BAND_ID: Int = -90
        private const val MAX_BAND_ID: Int = 89
        private val CONSTELLATION_CODES: Array<String> = arrayOf(
            "And", "Ant", "Aps", "Aql", "Aqr", "Ara", "Ari", "Aur",
            "Boo", "Cae", "Cam", "Cap", "Car", "Cas", "Cen", "Cep",
            "Cet", "Cha", "Cir", "CMa", "CMi", "Cnc", "Col", "Com",
            "CrA", "CrB", "Crt", "Cru", "Crv", "CVn", "Cyg", "Del",
            "Dor", "Dra", "Equ", "Eri", "For", "Gem", "Gru", "Her",
            "Hor", "Hya", "Hyi", "Ind", "Lac", "Leo", "Lep", "Lib",
            "Lup", "Lyn", "Lyr", "Men", "Mic", "Mon", "Mus", "Nor",
            "Oct", "Oph", "Ori", "Pav", "Peg", "Per", "Phe", "Pic",
            "PsA", "Psc", "Pup", "Pyx", "Ret", "Scl", "Sco", "Sct",
            "Ser", "Sex", "Sge", "Sgr", "Tau", "Tel", "TrA", "Tri",
            "Tuc", "UMa", "UMi", "Vel", "Vir", "Vol", "Vul"
        ).map { it.uppercase() }.toTypedArray()

        public fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: StarCatalog = FakeStarCatalog(),
            logger: Logger = LogBus,
        ): StarCatalog {
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

            if (header.version != SUPPORTED_VERSION) {
                logger.e(
                    TAG,
                    "Unsupported catalog",
                    payload = mapOf("path" to path, "version" to header.version),
                )
                return fallback
            }
            if (header.starCount < 0 || header.stringPoolSize < 0 || header.indexOffset < 0 || header.indexSize < 0) {
                logger.e(
                    TAG,
                    "Negative size in header",
                    payload = mapOf(
                        "path" to path,
                        "count" to header.starCount,
                        "pool" to header.stringPoolSize,
                        "indexOffset" to header.indexOffset,
                        "indexSize" to header.indexSize,
                    ),
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

            val data = try {
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                parseCatalog(bytes, header)
            } catch (buf: BufferUnderflowException) {
                logger.e(TAG, "Unexpected EOF while parsing star catalog", buf, mapOf("path" to path))
                return fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Failed to parse star catalog", ex, mapOf("path" to path))
                return fallback
            }

            val metadata = Metadata(
                sizeBytes = bytes.size,
                starCount = header.starCount,
                stringPoolBytes = header.stringPoolSize,
                indexOffsetBytes = header.indexOffset,
                indexSizeBytes = header.indexSize,
                payloadCrc32 = header.payloadCrc32,
                bandEntryCount = data.bandCounts.sum(),
            )
            logger.i(
                TAG,
                "Star catalog loaded",
                payload = mapOf(
                    "path" to path,
                    "sizeBytes" to metadata.sizeBytes,
                    "crc32" to metadata.payloadCrc32,
                    "count" to metadata.starCount,
                    "bandEntries" to metadata.bandEntryCount,
                ),
            )
            return BinaryStarCatalog(data, metadata)
        }

        private fun parseCatalog(bytes: ByteArray, header: BinaryCatalogHeader): CatalogData {
            val starCount = header.starCount
            val stringPoolStart = BinaryCatalogHeader.HEADER_SIZE_BYTES
            val stringPoolEnd = stringPoolStart + header.stringPoolSize
            require(stringPoolEnd <= bytes.size) { "String pool exceeds payload" }

            val stringPool = bytes.copyOfRange(stringPoolStart, stringPoolEnd)

            val recordsStart = stringPoolEnd
            val indexStart = BinaryCatalogHeader.HEADER_SIZE_BYTES + header.indexOffset
            require(indexStart <= bytes.size) { "Index offset outside payload" }

            val recordSectionSize = indexStart - recordsStart
            val expectedRecordSize = starCount * STAR_RECORD_SIZE_BYTES
            require(recordSectionSize == expectedRecordSize) {
                "Star records size mismatch: expected $expectedRecordSize, actual $recordSectionSize"
            }

            val recordBuffer = ByteBuffer.wrap(bytes, recordsStart, expectedRecordSize).order(ByteOrder.LITTLE_ENDIAN)
            val ra = FloatArray(starCount)
            val dec = FloatArray(starCount)
            val mag = FloatArray(starCount)
            val hip = IntArray(starCount)
            val nameOffsets = IntArray(starCount)
            val designationOffsets = IntArray(starCount)
            val flags = ShortArray(starCount)
            val conIndices = ShortArray(starCount)
            val raRad = DoubleArray(starCount)
            val decRad = DoubleArray(starCount)

            for (index in 0 until starCount) {
                val raValue = normalizeRa(recordBuffer.float.toDouble()).toFloat()
                val decValue = recordBuffer.float
                val magValue = recordBuffer.float
                recordBuffer.float // skip BV index
                val hipId = recordBuffer.int
                val nameOffset = recordBuffer.int
                val designationOffset = recordBuffer.int
                val flag = recordBuffer.short
                val conIndex = recordBuffer.short

                ra[index] = raValue
                dec[index] = decValue
                mag[index] = magValue
                hip[index] = hipId
                nameOffsets[index] = nameOffset
                designationOffsets[index] = designationOffset
                flags[index] = flag
                conIndices[index] = conIndex
                raRad[index] = Math.toRadians(raValue.toDouble())
                decRad[index] = Math.toRadians(decValue.toDouble())
            }

            val indexBuffer = ByteBuffer.wrap(bytes, indexStart, header.indexSize).order(ByteOrder.LITTLE_ENDIAN)
            val bandOffsets = IntArray(BAND_COUNT)
            val bandCounts = IntArray(BAND_COUNT)

            repeat(BAND_COUNT) {
                val bandId = indexBuffer.short.toInt()
                val start = indexBuffer.int
                val count = indexBuffer.int
                val idx = bandId - MIN_BAND_ID
                if (idx !in 0 until BAND_COUNT) {
                    throw IllegalArgumentException("Invalid band id $bandId")
                }
                bandOffsets[idx] = start
                bandCounts[idx] = count
            }

            val totalEntries = bandCounts.sum()
            val starIdsByBand = IntArray(totalEntries)
            for (i in 0 until totalEntries) {
                starIdsByBand[i] = indexBuffer.int
            }

            val raByBand = FloatArray(totalEntries)
            for (i in 0 until totalEntries) {
                raByBand[i] = indexBuffer.float
            }

            while (indexBuffer.hasRemaining()) {
                indexBuffer.float
            }

            return CatalogData(
                count = starCount,
                stringPool = stringPool,
                ra = ra,
                dec = dec,
                mag = mag,
                hip = hip,
                nameOffsets = nameOffsets,
                designationOffsets = designationOffsets,
                flags = flags,
                constellationIndices = conIndices,
                raRadians = raRad,
                decRadians = decRad,
                bandOffsets = bandOffsets,
                bandCounts = bandCounts,
                starIdsByBand = starIdsByBand,
                raByBand = raByBand,
            )
        }

        private fun normalizeRa(value: Double): Double {
            var ra = value % 360.0
            if (ra < 0.0) {
                ra += 360.0
            }
            return ra
        }

        private fun buildRaRanges(centerRa: Double, radius: Double): List<RaRange> {
            if (radius >= 180.0) {
                return listOf(RaRange(0f, 360f, coversAll = true))
            }
            val min = centerRa - radius
            val max = centerRa + radius
            if (max - min >= 360.0) {
                return listOf(RaRange(0f, 360f, coversAll = true))
            }
            val minNorm = normalizeRa(min).toFloat()
            val maxNorm = normalizeRa(max).toFloat()
            return when {
                min < 0.0 -> listOf(
                    RaRange(0f, maxNorm, coversAll = false),
                    RaRange(minNorm, 360f, coversAll = false),
                )
                max >= 360.0 -> listOf(
                    RaRange(0f, maxNorm, coversAll = false),
                    RaRange(minNorm, 360f, coversAll = false),
                )
                else -> listOf(RaRange(minNorm, maxNorm, coversAll = false))
            }
        }

        private fun lowerBound(array: FloatArray, start: Int, end: Int, target: Float): Int {
            var low = start
            var high = end
            while (low < high) {
                val mid = (low + high) ushr 1
                if (array[mid] < target) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

        private fun upperBound(array: FloatArray, start: Int, end: Int, target: Float): Int {
            var low = start
            var high = end
            while (low < high) {
                val mid = (low + high) ushr 1
                if (array[mid] <= target) {
                    low = mid + 1
                } else {
                    high = mid
                }
            }
            return low
        }

        private data class RaRange(val min: Float, val max: Float, val coversAll: Boolean)
    }

    internal data class CatalogData(
        val count: Int,
        val stringPool: ByteArray,
        val ra: FloatArray,
        val dec: FloatArray,
        val mag: FloatArray,
        val hip: IntArray,
        val nameOffsets: IntArray,
        val designationOffsets: IntArray,
        val flags: ShortArray,
        val constellationIndices: ShortArray,
        val raRadians: DoubleArray,
        val decRadians: DoubleArray,
        val bandOffsets: IntArray,
        val bandCounts: IntArray,
        val starIdsByBand: IntArray,
        val raByBand: FloatArray,
    ) {
        fun toStar(
            index: Int,
            decoder: (Int) -> String?,
            designationSplitter: (String?, String?) -> Pair<String?, String?>,
        ): Star {
            val hipId = hip[index]
            val id = if (hipId > 0) hipId else index
            val name = decoder(nameOffsets[index])
            val rawDesignation = decoder(designationOffsets[index])
            val constellation = constellationIndices[index].toInt()
                .takeIf { it >= 0 && it < CONSTELLATION_CODES.size }
                ?.let { CONSTELLATION_CODES[it] }
            val (bayer, flamsteed) = designationSplitter(rawDesignation, constellation)
            return Star(
                id = id,
                raDeg = ra[index],
                decDeg = dec[index],
                mag = mag[index],
                name = name,
                bayer = bayer,
                flamsteed = flamsteed,
                constellation = constellation,
            )
        }
    }
    public data class Metadata(
        val sizeBytes: Int,
        val starCount: Int,
        val stringPoolBytes: Int,
        val indexOffsetBytes: Int,
        val indexSizeBytes: Int,
        val payloadCrc32: Long,
        val bandEntryCount: Int,
    )
}

internal object BinaryStarCatalogMetrics {
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    private val totalQueries = AtomicLong(0)
    private val totalCandidates = AtomicLong(0)
    private val totalDurationNs = AtomicLong(0)

    fun record(durationNs: Long, candidates: Int) {
        if (!enabled) return
        totalQueries.incrementAndGet()
        totalCandidates.addAndGet(candidates.toLong())
        totalDurationNs.addAndGet(durationNs)
    }

    fun snapshot(): Snapshot = Snapshot(
        queries = totalQueries.get(),
        candidates = totalCandidates.get(),
        totalDurationNs = totalDurationNs.get(),
    )

    data class Snapshot(
        val queries: Long,
        val candidates: Long,
        val totalDurationNs: Long,
    )
}
