package dev.pointtosky.core.astro.catalog

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PtskCatalogLoaderTest {

    @Test
    fun `parser loads catalog header and sections`() {
        val parser = PtskCatalogParser()
        val catalogBytes = TestCatalogBuilder().build()
        val buffer = ByteBuffer.wrap(catalogBytes).order(ByteOrder.LITTLE_ENDIAN)

        val catalog = parser.parse(buffer)

        val orion = catalog.getConstellationMeta(ConstellationId(0))
        val lyra = catalog.getConstellationMeta(ConstellationId(1))
        assertEquals("Orion", orion.name)
        assertEquals("Lyra", lyra.name)
        assertEquals(5, catalog.starsByConstellation(ConstellationId(0)).size)

        val betelgeuse = assertNotNull(catalog.starById(1))
        assertEquals("Betelgeuse", betelgeuse.name)
    }

    @Test
    fun `asterisms and overlays contain required data`() {
        val parser = PtskCatalogParser()
        val buffer = ByteBuffer.wrap(TestCatalogBuilder().build()).order(ByteOrder.LITTLE_ENDIAN)

        val catalog = parser.parse(buffer)
        val orionId = ConstellationId(0)
        val lyraId = ConstellationId(1)

        val orionAsterisms = catalog.asterismsByConstellation(orionId)
        val lyraAsterisms = catalog.asterismsByConstellation(lyraId)

        val belt = orionAsterisms.firstOrNull { it.name == "Orion's Belt" }
        val rectangle = orionAsterisms.firstOrNull { it.name == "Orion Rectangle" }
        val triangle = lyraAsterisms.firstOrNull { it.name == "Lyra Triangle" }

        assertNotNull(belt)
        assertNotNull(rectangle)
        assertNotNull(triangle)

        assertEquals(1, belt.polylines.size)
        val beltNodes = belt.polylines.single().nodes
        val beltStarNames = beltNodes.map { id ->
            catalog.allStars().first { it.id == id }.name
        }
        assertEquals(listOf("Alnitak", "Alnilam", "Mintaka"), beltStarNames)

        val overlay = catalog.artOverlaysByConstellation(orionId).single()
        assertEquals("orion_silhouette_v1", overlay.artKey)

        val betelgeuse = catalog.allStars().first { it.name == "Betelgeuse" }
        val rigel = catalog.allStars().first { it.name == "Rigel" }
        assertEquals(betelgeuse.id, overlay.anchorStarA)
        assertEquals(rigel.id, overlay.anchorStarB)
    }
}

private class TestCatalogBuilder {
    private val stringPool = StringPoolBuilder()

    private val constellationCount = 88

    fun build(): ByteArray {
        val strSection = buildStringSection()
        val constSection = buildConstellations()
        val starSection = buildStars()
        val polySection = buildPolylines()
        val nodeSection = buildNodes()
        val asterSection = buildAsterisms()
        val artSection = buildOverlays()

        val sections = listOf(
            Section("STR0", strSection),
            Section("CST0", constSection, count = constellationCount),
            Section("STAR", starSection, count = starDefs.size),
            Section("ASTR", asterSection, count = asterismDefs.size),
            Section("APLY", polySection, count = polyDefs.size),
            Section("ASTN", nodeSection, count = nodes.size),
            Section("ART0", artSection, count = overlayDefs.size),
        )

        val headerSize = 16 + sections.size * 16
        val totalSize = headerSize + sections.sumOf { it.data.size }
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buffer.putInt(VERSION)
        buffer.putInt(sections.size)

        var offset = headerSize
        sections.forEach { section ->
            buffer.put(section.fourcc.toByteArray(StandardCharsets.US_ASCII))
            buffer.putInt(offset)
            buffer.putInt(section.data.size)
            buffer.putInt(section.count)
            offset += section.data.size
        }

        sections.forEach { buffer.put(it.data) }
        return buffer.array()
    }

    private fun buildStringSection(): ByteArray {
        listOf(
            "Ori" to "Orion",
            "Lyr" to "Lyra",
            "Orion's Belt" to "Orion Rectangle",
            "Lyra Triangle" to "orion_silhouette_v1",
            "Betelgeuse" to "Rigel",
            "Alnitak" to "Alnilam",
            "Mintaka" to "Vega",
            "Sheliak" to "Sulafat",
        ).forEach { (a, b) ->
            stringPool.id(a)
            stringPool.id(b)
        }
        repeat(constellationCount - 2) { index ->
            stringPool.id("C${index + 2}")
            stringPool.id("Constellation${index + 2}")
        }
        return stringPool.build()
    }

    private fun buildConstellations(): ByteArray {
        val buffer = ByteBuffer.allocate(constellationCount * 16).order(ByteOrder.LITTLE_ENDIAN)
        repeat(constellationCount) { index ->
            val abbr = when (index) {
                0 -> "Ori"
                1 -> "Lyr"
                else -> "C${index}"
            }
            val name = when (index) {
                0 -> "Orion"
                1 -> "Lyra"
                else -> "Constellation${index}"
            }
            buffer.putInt(stringPool.id(abbr))
            buffer.putInt(stringPool.id(name))
            buffer.putInt(0)
            buffer.putInt(0)
        }
        return buffer.array()
    }

    private val starDefs = listOf(
        StarDef(StarId(1), 88.8f, 7.4f, 0.5f, 0, "Betelgeuse"),
        StarDef(StarId(2), 78.6f, -8.2f, 0.3f, 0, "Rigel"),
        StarDef(StarId(101), 85.2f, -1.9f, 1.7f, 0, "Alnitak"),
        StarDef(StarId(102), 84.1f, -1.2f, 1.7f, 0, "Alnilam"),
        StarDef(StarId(103), 83.0f, -0.3f, 2.2f, 0, "Mintaka"),
        StarDef(StarId(10001), 279.2f, 38.8f, 0.0f, 1, "Vega"),
        StarDef(StarId(10101), 284.7f, 33.4f, 3.3f, 1, "Sheliak"),
        StarDef(StarId(10102), 281.0f, 32.7f, 3.3f, 1, "Sulafat"),
    )

    private fun buildStars(): ByteArray {
        val buffer = ByteBuffer.allocate(starDefs.size * 28).order(ByteOrder.LITTLE_ENDIAN)
        starDefs.forEach { star ->
            buffer.putInt(star.id.raw)
            buffer.putFloat(star.ra)
            buffer.putFloat(star.dec)
            buffer.putFloat(star.mag)
            buffer.putShort(star.constIndex.toShort())
            buffer.putShort(star.flags.toShort())
            buffer.putInt(stringPool.id(star.name))
        }
        return buffer.array()
    }

    private val polyDefs = listOf(
        PolyDef(nodeStart = 0, nodeCount = 3, style = 0),
        PolyDef(nodeStart = 3, nodeCount = 4, style = 0),
        PolyDef(nodeStart = 7, nodeCount = 3, style = 0),
    )

    private val nodes = listOf(
        101, 102, 103, // Orion's Belt
        1, 2, 103, 101, // Orion Rectangle
        10001, 10101, 10102, // Lyra Triangle
    )

    private fun buildPolylines(): ByteArray {
        val buffer = ByteBuffer.allocate(polyDefs.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        polyDefs.forEach { poly ->
            buffer.putInt(poly.nodeStart)
            buffer.putShort(poly.nodeCount.toShort())
            buffer.putShort(poly.style.toShort())
            buffer.putInt(0)
        }
        return buffer.array()
    }

    private fun buildNodes(): ByteArray {
        val buffer = ByteBuffer.allocate(nodes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        nodes.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private val asterismDefs = listOf(
        AsterismDef(
            constellationIndex = 0,
            flags = 0,
            name = "Orion's Belt",
            polyStart = 0,
            polyCount = 1,
            labelStarId = 102,
        ),
        AsterismDef(
            constellationIndex = 0,
            flags = 0,
            name = "Orion Rectangle",
            polyStart = 1,
            polyCount = 1,
            labelStarId = 1,
        ),
        AsterismDef(
            constellationIndex = 1,
            flags = 0,
            name = "Lyra Triangle",
            polyStart = 2,
            polyCount = 1,
            labelStarId = 10001,
        ),
    )

    private fun buildAsterisms(): ByteArray {
        val buffer = ByteBuffer.allocate(asterismDefs.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        asterismDefs.forEach { asterism ->
            buffer.putShort(asterism.constellationIndex.toShort())
            buffer.putShort(asterism.flags.toShort())
            buffer.putInt(stringPool.id(asterism.name))
            buffer.putInt(asterism.polyStart)
            buffer.putShort(asterism.polyCount.toShort())
            buffer.putShort(0)
            buffer.putInt(asterism.labelStarId)
        }
        return buffer.array()
    }

    private val overlayDefs = listOf(
        OverlayDef(constellationIndex = 0, artKey = "orion_silhouette_v1", anchorA = 1, anchorB = 2),
    )

    private fun buildOverlays(): ByteArray {
        val buffer = ByteBuffer.allocate(overlayDefs.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        overlayDefs.forEach { overlay ->
            buffer.putShort(overlay.constellationIndex.toShort())
            buffer.putShort(overlay.flags.toShort())
            buffer.putInt(stringPool.id(overlay.artKey))
            buffer.putInt(overlay.anchorA)
            buffer.putInt(overlay.anchorB)
        }
        return buffer.array()
    }
}

private data class Section(
    val fourcc: String,
    val data: ByteArray,
    val count: Int = 0,
)

private class StringPoolBuilder {
    private val buffer = ByteArrayOutputStream()
    private val offsets = mutableMapOf<String, Int>()

    fun id(value: String): Int {
        return offsets.getOrPut(value) {
            val offset = buffer.size()
            buffer.write(value.toByteArray(StandardCharsets.UTF_8))
            buffer.write(0)
            offset
        }
    }

    fun build(): ByteArray = buffer.toByteArray()
}

private data class StarDef(
    val id: StarId,
    val ra: Float,
    val dec: Float,
    val mag: Float,
    val constIndex: Int,
    val name: String,
    val flags: Int = 0,
)

private data class PolyDef(
    val nodeStart: Int,
    val nodeCount: Int,
    val style: Int,
)

private data class AsterismDef(
    val constellationIndex: Int,
    val flags: Int,
    val name: String,
    val polyStart: Int,
    val polyCount: Int,
    val labelStarId: Int,
)

private data class OverlayDef(
    val constellationIndex: Int,
    val artKey: String,
    val anchorA: Int,
    val anchorB: Int,
    val flags: Int = 0,
)
