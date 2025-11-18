package dev.pointtosky.core.astro.catalog

import android.content.res.AssetManager
import android.util.Log
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAGIC = "PTSKCAT4"
internal const val VERSION = 4

private data class SectionDir(
    val fourcc: String,
    val offset: Int,
    val length: Int,
    val count: Int,
)

private data class StarRecordBin(
    val id: StarId,
    val ra: Float,
    val dec: Float,
    val mag: Float,
    val constellationId: ConstellationId,
    val flags: Int,
    val nameId: PtskStringId,
)

private data class AsterismRecordBin(
    val constellationId: ConstellationId,
    val flags: Int,
    val nameId: PtskStringId,
    val polyStart: Int,
    val polyCount: Int,
    val labelStarId: StarId,
    val polylines: List<AsterismPoly>,
)

private data class PolyRecordBin(
    val nodeStart: Int,
    val nodeCount: Int,
    val style: Int,
)

private data class ArtOverlayBin(
    val constellationId: ConstellationId,
    val flags: Int,
    val artKeyId: PtskStringId,
    val anchorA: StarId,
    val anchorB: StarId,
)

private class StringPool(private val data: ByteArray) {
    fun get(id: PtskStringId): String {
        val offset = id.offset
        require(offset in 0 until data.size) { "String offset out of bounds: $offset" }
        var end = offset
        while (end < data.size && data[end].toInt() != 0) {
            end++
        }
        require(end <= data.size) { "Unterminated string starting at offset $offset" }
        return String(data, offset, end - offset, StandardCharsets.UTF_8)
    }
}

private class AstroCatalogImpl(
    private val constellations: List<ConstellationMeta>,
    private val stars: List<StarRecord>,
    private val asterisms: List<Asterism>,
    private val overlays: List<ArtOverlay>,
) : AstroCatalog {
    private val constellationById = constellations.associateBy { it.id }
    private val starsById: Map<Int, StarRecord> = stars.associateBy { it.id.raw }
    private val starsByConstellation = stars.groupBy { it.constellationId }
    private val asterismsByConstellation = asterisms.groupBy { it.constellationId }
    private val overlaysByConstellation = overlays.groupBy { it.constellationId }

    override fun getConstellationMeta(id: ConstellationId): ConstellationMeta =
        constellationById[id] ?: error("Unknown constellation id: ${id.index}")

    override fun allStars(): List<StarRecord> = stars

    override fun starById(raw: Int): StarRecord? = starsById[raw]

    override fun starsByConstellation(id: ConstellationId): List<StarRecord> =
        starsByConstellation[id].orEmpty()

    override fun asterismsByConstellation(id: ConstellationId): List<Asterism> =
        asterismsByConstellation[id].orEmpty()

    override fun artOverlaysByConstellation(id: ConstellationId): List<ArtOverlay> =
        overlaysByConstellation[id].orEmpty()
}

class PtskCatalogLoader(
    private val assetManager: AssetManager,
    private val assetPath: String = "catalog/star.bin",
) {
    private val parser = PtskCatalogParser()

    suspend fun load(): AstroCatalog? {
        parser.cached?.let { return it }
        val catalog = withContext(Dispatchers.IO) {
            try {
                assetManager.open(assetPath).use { input ->
                    val bytes = input.readBytes()
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    parser.parse(buffer)
                }
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Catalog asset not found at $assetPath", e)
                null
            }
        }
        if (catalog != null) {
            parser.cached = catalog
        }
        return catalog
    }

    private companion object {
        private const val TAG = "PtskCatalogLoader"
    }
}

internal class PtskCatalogParser {
    var cached: AstroCatalog? = null

    fun parse(buffer: ByteBuffer): AstroCatalog {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(8)
        buffer.get(magicBytes)
        val magic = String(magicBytes, StandardCharsets.US_ASCII)
        require(magic == MAGIC) { "Unexpected magic: $magic" }
        val version = buffer.int
        require(version == VERSION) { "Unsupported version: $version" }
        val sectionCount = buffer.int
        require(sectionCount >= 1) { "Section count must be positive" }
        val sections = (0 until sectionCount).map {
            val fourccBytes = ByteArray(4)
            buffer.get(fourccBytes)
            val fourcc = String(fourccBytes, StandardCharsets.US_ASCII)
            SectionDir(fourcc, buffer.int, buffer.int, buffer.int)
        }.associateBy { it.fourcc }

        fun requireSection(name: String, recordSize: Int? = null): SectionDir {
            val section = sections[name] ?: error("Missing section $name")
            recordSize?.let { size ->
                if (section.count > 0) {
                    val expected = size * section.count
                    require(section.length >= expected) {
                        "Section $name too small: ${section.length}, expected at least $expected"
                    }
                }
            }
            return section
        }

        val strSection = requireSection("STR0")
        val stringPool = ByteArray(strSection.length)
        buffer.position(strSection.offset)
        buffer.get(stringPool)
        val strings = StringPool(stringPool)

        val constSection = requireSection("CST0", 16)
        val constellations = parseConstellations(buffer, constSection, strings)

        // STAR-запись: id(4) + ra(4) + dec(4) + mag(4) + const(2) + flags(2) + nameId(4) = 24 байта
        val starSection = requireSection("STAR", 24)
        val starsBin = parseStars(buffer, starSection)

        val asterSection = requireSection("ASTR", 20)
        val polySection = requireSection("APLY", 12)
        val nodeSection = requireSection("ASTN", 4)
        val asterisms = parseAsterisms(buffer, asterSection, polySection, nodeSection, strings)

        val artSection = requireSection("ART0", 16)
        val overlays = parseOverlays(buffer, artSection, strings)

        val catalog = AstroCatalogImpl(
            constellations = constellations,
            stars = starsBin.map { it.toRecord(strings) },
            asterisms = asterisms.map { it.toAsterism(strings) },
            overlays = overlays.map { it.toOverlay(strings) },
        )
        cached = catalog
        return catalog
    }

    private fun parseConstellations(
        buffer: ByteBuffer,
        section: SectionDir,
        strings: StringPool,
    ): List<ConstellationMeta> {
        buffer.position(section.offset)
        val constellations = mutableListOf<ConstellationMeta>()
        repeat(section.count) { index ->
            val abbrId = PtskStringId(buffer.int)
            val nameId = PtskStringId(buffer.int)
            buffer.int // first_line reserved
            buffer.int // line_count reserved
            constellations += ConstellationMeta(
                id = ConstellationId(index),
                abbreviation = strings.get(abbrId),
                name = strings.get(nameId),
            )
        }
        return constellations
    }

    private fun parseStars(buffer: ByteBuffer, section: SectionDir): List<StarRecordBin> {
        buffer.position(section.offset)
        return List(section.count) {
            val id = StarId(buffer.int)
            val ra = buffer.float
            val dec = buffer.float
            val mag = buffer.float
            val constIndex = buffer.short.toInt() and 0xFFFF
            val flags = buffer.short.toInt() and 0xFFFF
            val nameId = PtskStringId(buffer.int)
            val constellationId = ConstellationId(constIndex)
            require(id.cc() == constellationId.index) {
                "Star id constellation ${id.cc()} does not match record const index ${constellationId.index}"
            }
            StarRecordBin(id, ra, dec, mag, constellationId, flags, nameId)
        }
    }

    private fun parseAsterisms(
        buffer: ByteBuffer,
        asterSection: SectionDir,
        polySection: SectionDir,
        nodeSection: SectionDir,
        strings: StringPool,
    ): List<AsterismRecordBin> {
        val polylines = parsePolylines(buffer, polySection)
        val nodes = parseNodes(buffer, nodeSection)

        buffer.position(asterSection.offset)
        return List(asterSection.count) {
            val constId = ConstellationId(buffer.short.toInt() and 0xFFFF)
            val flags = buffer.short.toInt() and 0xFFFF
            val nameId = PtskStringId(buffer.int)
            val polyStart = buffer.int
            val polyCount = buffer.short.toInt() and 0xFFFF
            buffer.short // reserved
            val labelId = StarId(buffer.int)

            require(polyStart + polyCount <= polylines.size) { "Asterism poly range out of bounds" }
            val polySlice = polylines.subList(polyStart, polyStart + polyCount)
            val expandedPolys = polySlice.map { poly ->
                require(poly.nodeStart + poly.nodeCount <= nodes.size) { "Polyline node range out of bounds" }
                val starIds = nodes.subList(poly.nodeStart, poly.nodeStart + poly.nodeCount)
                AsterismPoly(poly.style, starIds.map { StarId(it) })
            }

            AsterismRecordBin(
                constellationId = constId,
                flags = flags,
                nameId = nameId,
                polyStart = polyStart,
                polyCount = polyCount,
                labelStarId = labelId,
                polylines = expandedPolys,
            )
        }
    }

    private fun parsePolylines(buffer: ByteBuffer, section: SectionDir): List<PolyRecordBin> {
        buffer.position(section.offset)
        return List(section.count) {
            val nodeStart = buffer.int
            val nodeCount = buffer.short.toInt() and 0xFFFF
            val style = buffer.short.toInt() and 0xFFFF
            buffer.int // reserved
            PolyRecordBin(nodeStart, nodeCount, style)
        }
    }

    private fun parseNodes(buffer: ByteBuffer, section: SectionDir): List<Int> {
        buffer.position(section.offset)
        return List(section.count) { buffer.int }
    }

    private fun parseOverlays(
        buffer: ByteBuffer,
        section: SectionDir,
        strings: StringPool,
    ): List<ArtOverlayBin> {
        buffer.position(section.offset)
        return List(section.count) {
            val constId = ConstellationId(buffer.short.toInt() and 0xFFFF)
            val flags = buffer.short.toInt() and 0xFFFF
            val artKeyId = PtskStringId(buffer.int)
            val anchorA = StarId(buffer.int)
            val anchorB = StarId(buffer.int)
            ArtOverlayBin(constId, flags, artKeyId, anchorA, anchorB)
        }
    }

    private fun StarRecordBin.toRecord(strings: StringPool): StarRecord =
        StarRecord(
            id = id,
            rightAscensionDeg = ra,
            declinationDeg = dec,
            magnitude = mag,
            constellationId = constellationId,
            flags = flags,
            name = strings.get(nameId).ifEmpty { null },
        )

    private fun AsterismRecordBin.toAsterism(strings: StringPool): Asterism =
        Asterism(
            constellationId = constellationId,
            flags = flags,
            name = strings.get(nameId),
            polylines = polylines,
            labelStarId = labelStarId,
        )

    private fun ArtOverlayBin.toOverlay(strings: StringPool): ArtOverlay =
        ArtOverlay(
            constellationId = constellationId,
            flags = flags,
            artKey = strings.get(artKeyId),
            anchorStarA = anchorA,
            anchorStarB = anchorB,
        )
}
