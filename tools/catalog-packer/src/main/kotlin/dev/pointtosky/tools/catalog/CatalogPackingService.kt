package dev.pointtosky.tools.catalog

import dev.pointtosky.tools.catalog.csv.BscCatalogParser
import dev.pointtosky.tools.catalog.csv.CatalogCsvParser
import dev.pointtosky.tools.catalog.csv.HygCatalogParser
import dev.pointtosky.tools.catalog.model.CatalogMeta
import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.IndexSummary
import dev.pointtosky.tools.catalog.model.PackRequest
import dev.pointtosky.tools.catalog.model.PackResult
import dev.pointtosky.tools.catalog.model.StarInput
import dev.pointtosky.tools.catalog.model.StringPoolBuilder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class CatalogPackingService(
    private val parserFactory: (CatalogSource) -> CatalogCsvParser = { source ->
        when (source) {
            CatalogSource.BSC -> BscCatalogParser()
            CatalogSource.HYG -> HygCatalogParser()
        }
    },
) {
    fun pack(request: PackRequest): PackResult {
        val parser = parserFactory(request.source)
        val stars = parser.read(request.input, request.magLimit)
        if (stars.isEmpty()) {
            throw IllegalStateException("No stars matched the filters (mag <= ${request.magLimit})")
        }

        val stringPool = StringPoolBuilder()
        val recordsBytes = buildStarRecords(stars, stringPool, request)
        val stringPoolBytes = stringPool.toByteArray()

        val index = buildIndex(stars)
        val indexBytes = buildIndexBytes(index)

        val dataStream = ByteArrayOutputStream()
        dataStream.write(stringPoolBytes)
        val stringPoolSize = stringPoolBytes.size
        dataStream.write(recordsBytes)
        val indexOffset = stringPoolSize + recordsBytes.size
        dataStream.write(indexBytes)
        val dataBytes = dataStream.toByteArray()

        val crc32 = CRC32().apply { update(dataBytes) }.value.toInt()

        val header = buildHeader(
            starCount = stars.size,
            stringPoolSize = stringPoolSize,
            indexOffset = indexOffset,
            indexSize = indexBytes.size,
            crc32 = crc32,
        )

        val binary = ByteArrayOutputStream(header.size + dataBytes.size).apply {
            write(header)
            write(dataBytes)
        }.toByteArray()

        val meta = CatalogMeta(
            source = request.source,
            inputPath = request.input.toString(),
            magnitudeLimit = request.magLimit,
            starCount = stars.size,
            stringPoolSize = stringPoolSize,
            indexOffset = indexOffset,
            indexSize = indexBytes.size,
            crc32 = crc32,
            bandCount = index.bands.size,
            indexEntryCount = index.totalEntries,
            summary = index.summary,
            rdpEpsilon = request.rdpEpsilon,
        )

        return PackResult(binary = binary, meta = meta)
    }

    private fun buildStarRecords(
        stars: List<StarInput>,
        stringPool: StringPoolBuilder,
        request: PackRequest,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(stars.size * STAR_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (star in stars) {
            val ra = star.raDeg.toFloat()
            val dec = star.decDeg.toFloat()
            val mag = star.mag.toFloat()
            val hip = star.hip
            val nameOffset = stringPool.offsetOrZero(star.name)
            val designation = buildDesignation(star)
            val designationOffset = stringPool.offsetOrZero(designation)
            val flags = buildFlags(star, designation)
            val conIndex = if (request.withConCodes) Constellations.indexOf(star.constellation) else -1

            buffer.putFloat(ra)
            buffer.putFloat(dec)
            buffer.putFloat(mag)
            buffer.putFloat(0.0f) // placeholder for BV colour index
            buffer.putInt(hip)
            buffer.putInt(nameOffset)
            buffer.putInt(designationOffset)
            buffer.putShort(flags.toShort())
            buffer.putShort(conIndex.toShort())
        }
        buffer.flip()
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)
        return bytes
    }

    private fun buildFlags(star: StarInput, designation: String?): Int {
        var flags = 0
        if (!star.name.isNullOrBlank()) flags = flags or FLAG_HAS_NAME
        if (!designation.isNullOrBlank()) flags = flags or FLAG_HAS_DESIGNATION
        if (star.hip > 0) flags = flags or FLAG_HAS_HIP
        flags = flags or when (star.source) {
            CatalogSource.BSC -> FLAG_SOURCE_BSC
            CatalogSource.HYG -> FLAG_SOURCE_HYG
        }
        return flags
    }

    private fun buildDesignation(star: StarInput): String? {
        val parts = buildList {
            if (!star.bayer.isNullOrBlank()) add(star.bayer.trim())
            if (!star.flamsteed.isNullOrBlank()) add(star.flamsteed.trim())
        }
        if (parts.isEmpty()) return null
        val suffix = star.constellation?.takeIf { it.isNotBlank() }
        return if (suffix != null && parts.size == 1 && star.flamsteed != null && star.bayer == null) {
            "${parts.first()} $suffix"
        } else {
            if (suffix != null && parts.isNotEmpty()) {
                (parts + suffix).joinToString(" ")
            } else {
                parts.joinToString(" ")
            }
        }
    }

    private fun buildIndex(stars: List<StarInput>): BandIndex {
        val buckets = Array(180) { mutableListOf<StarBucketEntry>() }
        var minMag = Double.POSITIVE_INFINITY
        var maxMag = Double.NEGATIVE_INFINITY
        var minRa = Double.POSITIVE_INFINITY
        var maxRa = Double.NEGATIVE_INFINITY
        var minDec = Double.POSITIVE_INFINITY
        var maxDec = Double.NEGATIVE_INFINITY

        stars.forEachIndexed { index, star ->
            val band = ((kotlin.math.floor(star.decDeg).toInt()).coerceIn(-90, 89) + 90)
            val clampedRa = star.raDeg.mod(360.0)
            buckets[band].add(StarBucketEntry(index, clampedRa.toFloat()))

            minMag = minOf(minMag, star.mag)
            maxMag = maxOf(maxMag, star.mag)
            minRa = minOf(minRa, clampedRa)
            maxRa = maxOf(maxRa, clampedRa)
            minDec = minOf(minDec, star.decDeg)
            maxDec = maxOf(maxDec, star.decDeg)
        }

        val sortedBuckets = buckets.mapIndexed { idx, entries ->
            entries.sortBy { it.ra }
            BandEntry(
                bandId = idx - 90,
                entries = entries.toList(),
            )
        }

        val totalEntries = sortedBuckets.sumOf { it.entries.size }
        val summary = IndexSummary(
            minMagnitude = minMag,
            maxMagnitude = maxMag,
            minRa = minRa,
            maxRa = maxRa,
            minDec = minDec,
            maxDec = maxDec,
        )

        return BandIndex(sortedBuckets, totalEntries, summary)
    }

    private fun buildIndexBytes(index: BandIndex): ByteArray {
        val bandTableSize = index.bands.size * (Short.SIZE_BYTES + Int.SIZE_BYTES * 2)
        val idsSize = index.totalEntries * Int.SIZE_BYTES
        val rasSize = index.totalEntries * java.lang.Float.BYTES
        val summaryFloats = 8 // bandCount, entryCount, min/max mag, min/max RA, min/max Dec
        val summarySize = summaryFloats * java.lang.Float.BYTES

        val buffer = ByteBuffer.allocate(bandTableSize + idsSize + rasSize + summarySize)
            .order(ByteOrder.LITTLE_ENDIAN)

        var cursor = 0
        index.bands.forEach { band ->
            buffer.putShort(band.bandId.toShort())
            buffer.putInt(cursor)
            buffer.putInt(band.entries.size)
            cursor += band.entries.size
        }

        index.bands.forEach { band ->
            band.entries.forEach { buffer.putInt(it.starIndex) }
        }

        index.bands.forEach { band ->
            band.entries.forEach { buffer.putFloat(it.ra) }
        }

        buffer.putFloat(index.bands.size.toFloat())
        buffer.putFloat(index.totalEntries.toFloat())
        buffer.putFloat(index.summary.minMagnitude.toFloat())
        buffer.putFloat(index.summary.maxMagnitude.toFloat())
        buffer.putFloat(index.summary.minRa.toFloat())
        buffer.putFloat(index.summary.maxRa.toFloat())
        buffer.putFloat(index.summary.minDec.toFloat())
        buffer.putFloat(index.summary.maxDec.toFloat())

        buffer.flip()
        val bytes = ByteArray(buffer.limit())
        buffer.get(bytes)
        return bytes
    }

    private fun buildHeader(
        starCount: Int,
        stringPoolSize: Int,
        indexOffset: Int,
        indexSize: Int,
        crc32: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC_BYTES)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(0) // reserved
        buffer.putInt(starCount)
        buffer.putInt(stringPoolSize)
        buffer.putInt(indexOffset)
        buffer.putInt(indexSize)
        buffer.putInt(crc32)
        buffer.flip()
        return buffer.array()
    }

    private data class StarBucketEntry(val starIndex: Int, val ra: Float)

    private data class BandEntry(val bandId: Int, val entries: List<StarBucketEntry>)

    private data class BandIndex(
        val bands: List<BandEntry>,
        val totalEntries: Int,
        val summary: IndexSummary,
    )

    companion object {
        const val STAR_RECORD_SIZE: Int = 32
        const val HEADER_SIZE: Int = 32
        const val VERSION: Int = 1
        val MAGIC_BYTES: ByteArray = "PTSKSTAR".toByteArray(Charsets.US_ASCII)

        private const val FLAG_HAS_NAME = 0x01
        private const val FLAG_HAS_DESIGNATION = 0x02
        private const val FLAG_HAS_HIP = 0x04
        private const val FLAG_SOURCE_BSC = 0x0100
        private const val FLAG_SOURCE_HYG = 0x0200
    }
}
