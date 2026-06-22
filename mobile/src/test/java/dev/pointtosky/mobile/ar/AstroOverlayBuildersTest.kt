package dev.pointtosky.mobile.ar

import dev.pointtosky.core.astro.catalog.Asterism
import dev.pointtosky.core.astro.catalog.AsterismPoly
import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.ConstellationMeta
import dev.pointtosky.core.astro.catalog.StarFlags
import dev.pointtosky.core.astro.catalog.StarId
import dev.pointtosky.core.astro.catalog.StarRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AstroOverlayBuildersTest {
    @Test
    fun `buildConstellationSkeletonLines chains nodes by pp and ss`() {
        val constellation = ConstellationId(1)
        val stars =
            listOf(
                starRecord(10102, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10101, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10103, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10201, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10203, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10202, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10001, constellation),
            )

        val segments = buildConstellationSkeletonLines(stars)

        assertEquals(4, segments.size)
        assertEquals(10101, segments[0].start.id.raw)
        assertEquals(10102, segments[0].end.id.raw)
        assertEquals(10102, segments[1].start.id.raw)
        assertEquals(10103, segments[1].end.id.raw)
        assertEquals(10201, segments[2].start.id.raw)
        assertEquals(10202, segments[2].end.id.raw)
        assertEquals(10202, segments[3].start.id.raw)
        assertEquals(10203, segments[3].end.id.raw)
    }

    @Test
    fun `buildConstellationSkeletonLines ignores bulk stars without LINE_NODE`() {
        val constellation = ConstellationId(1)
        val stars =
            listOf(
                starRecord(10102, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10101, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10103, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10201, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10203, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10202, constellation, flags = StarFlags.LINE_NODE),
                starRecord(10001, constellation),
                // A2 scenario: dense bulk star spilled into a high pp slot, but flags = 0.
                starRecord(10301, constellation, flags = 0),
            )

        val segments = buildConstellationSkeletonLines(stars)

        assertEquals(4, segments.size)
    }

    @Test
    fun `buildConstellationSkeletonLines includes LINE_NODE AUX_ONLY nodes as geometry`() {
        val constellation = ConstellationId(3)
        val stars = listOf(
            starRecord(30101, constellation, flags = StarFlags.LINE_NODE or StarFlags.AUX_ONLY),
            starRecord(30102, constellation, flags = StarFlags.LINE_NODE or StarFlags.AUX_ONLY),
            starRecord(30103, constellation, flags = StarFlags.LINE_NODE or StarFlags.AUX_ONLY),
        )

        val segments = buildConstellationSkeletonLines(stars)

        assertEquals(2, segments.size)
        assertEquals(30101, segments[0].start.id.raw)
        assertEquals(30102, segments[0].end.id.raw)
        assertEquals(30102, segments[1].start.id.raw)
        assertEquals(30103, segments[1].end.id.raw)
    }

    @Test
    fun `buildAsterismSegments follows polyline order`() {
        val constellation = ConstellationId(2)
        val stars =
            listOf(
                starRecord(20101, constellation),
                starRecord(20102, constellation),
                starRecord(20103, constellation),
            )
        val asterism =
            Asterism(
                constellationId = constellation,
                flags = 0,
                name = "Test Asterism",
                polylines = listOf(AsterismPoly(style = 0, nodes = stars.map(StarRecord::id))),
                labelStarId = stars.first().id,
            )

        val catalog = FakeAstroCatalog(stars = stars, asterisms = listOf(asterism))
        val segments = buildAsterismSegments(asterism, catalog)

        assertEquals(2, segments.size)
        assertTrue(segments.all { it.start.constellationId == constellation })
        assertEquals(20101, segments[0].start.id.raw)
        assertEquals(20102, segments[0].end.id.raw)
        assertEquals(20102, segments[1].start.id.raw)
        assertEquals(20103, segments[1].end.id.raw)
    }

    private fun starRecord(id: Int, constellation: ConstellationId, flags: Int = 0): StarRecord =
        StarRecord(
            id = StarId(id),
            rightAscensionDeg = 0f,
            declinationDeg = 0f,
            magnitude = 0f,
            constellationId = constellation,
            flags = flags,
            name = null,
        )
}

private class FakeAstroCatalog(
    private val stars: List<StarRecord>,
    private val asterisms: List<Asterism>,
) : AstroCatalog {
    override fun getConstellationMeta(id: ConstellationId): ConstellationMeta =
        ConstellationMeta(id = id, abbreviation = "C${id.index}", name = "Constellation ${id.index}")

    override fun allStars(): List<StarRecord> = stars

    override fun starById(raw: Int): StarRecord? = stars.firstOrNull { it.id.raw == raw }

    override fun starsByConstellation(id: ConstellationId): List<StarRecord> =
        stars.filter { it.constellationId == id }

    override fun asterismsByConstellation(id: ConstellationId): List<Asterism> =
        asterisms.filter { it.constellationId == id }

    override fun artOverlaysByConstellation(id: ConstellationId) = emptyList<dev.pointtosky.core.astro.catalog.ArtOverlay>()
}
