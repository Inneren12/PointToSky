package dev.pointtosky.mobile.ar

import dev.pointtosky.core.astro.catalog.Asterism
import dev.pointtosky.core.astro.catalog.AsterismPoly
import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.StarRecord

/**
 * Собирает отрезки для отображения астризмов без дополнительных аллокаций карт на каждый кадр.
 * Ожидается, что [AstroCatalog.starById] использует уже подготовленный кеш звёзд по id.
 */
internal fun buildAsterismSegments(
    asterism: Asterism,
    catalog: AstroCatalog,
): List<StarLineSegment> {
    val capacity = asterism.polylines.sumOf { (it.nodes.size - 1).coerceAtLeast(0) }
    if (capacity == 0) return emptyList()

    val segments = ArrayList<StarLineSegment>(capacity)
    asterism.polylines.forEach { polyline ->
        appendSegments(polyline, catalog, segments)
    }
    return segments
}

private fun appendSegments(
    polyline: AsterismPoly,
    catalog: AstroCatalog,
    out: MutableList<StarLineSegment>,
) {
    if (polyline.nodes.size <= 1) return

    var previous = catalog.starById(polyline.nodes.first().raw) ?: return
    for (index in 1 until polyline.nodes.size) {
        val next = catalog.starById(polyline.nodes[index].raw) ?: continue
        out += StarLineSegment(start = previous, end = next, style = polyline.style)
        previous = next
    }
}

internal data class StarLineSegment(
    val start: StarRecord,
    val end: StarRecord,
    val style: Int,
)
import androidx.compose.ui.geometry.Offset
import dev.pointtosky.core.astro.catalog.Asterism
import dev.pointtosky.core.astro.catalog.AsterismPoly
import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.ArtOverlay
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.catalog.StarId

typealias AsterismId = String

data class AsterismSummary(
    val id: AsterismId,
    val name: String,
    val constellationId: ConstellationId,
)

data class AsterismUiState(
    val isEnabled: Boolean,
    val highlighted: AsterismId?,
    val available: List<AsterismSummary>,
)

data class StarLineSegment(
    val start: StarRecord,
    val end: StarRecord,
)

data class AstroCatalogState(
    val catalog: AstroCatalog,
    val starsById: Map<Int, StarRecord>,
    val constellationByAbbr: Map<String, ConstellationId>,
    val skeletonLines: List<StarLineSegment>,
)

data class ScreenLineSegment(
    val start: Offset,
    val end: Offset,
    val highlighted: Boolean,
)

data class AsterismLabelOverlay(
    val position: Offset,
    val text: String,
    val highlighted: Boolean,
)

data class ConstellationArtOverlay(
    val key: String,
    val anchorA: Offset,
    val anchorB: Offset,
)

internal fun buildConstellationSkeletonLines(stars: List<StarRecord>): List<StarLineSegment> {
    val grouped =
        stars
            .groupBy { star -> star.constellationId to star.id.pp() }
            .filterKeys { (_, pp) -> pp != 0 }

    return grouped.values.flatMap { group ->
        val sorted = group.sortedBy { it.id.ss() }
        sorted.zipWithNext().map { (start, end) -> StarLineSegment(start, end) }
    }
}

internal fun buildAsterismSegments(asterism: Asterism, catalog: AstroCatalog): List<StarLineSegment> {
    val starMap = catalog.allStars().associateBy { it.id.raw }
    return asterism.polylines.flatMap { poly -> buildSegmentsForPolyline(poly, starMap) }
}

private fun buildSegmentsForPolyline(
    poly: AsterismPoly,
    starMap: Map<Int, StarRecord>,
): List<StarLineSegment> {
    val nodes = poly.nodes.mapNotNull { starMap[it.raw] }
    if (nodes.size < 2) return emptyList()
    return nodes.zipWithNext().map { (start, end) -> StarLineSegment(start, end) }
}

class ConstellationArtRenderer {
    fun render(
        overlays: List<ArtOverlay>,
        projectStar: (StarId) -> Offset?,
    ): List<ConstellationArtOverlay> = overlays.mapNotNull { overlay ->
        val anchorA = projectStar(overlay.anchorStarA) ?: return@mapNotNull null
        val anchorB = projectStar(overlay.anchorStarB) ?: return@mapNotNull null
        ConstellationArtOverlay(key = overlay.artKey, anchorA = anchorA, anchorB = anchorB)
    }

    fun drawOrionSilhouette(anchorA: Offset, anchorB: Offset): ConstellationArtOverlay =
        ConstellationArtOverlay(key = ORION_SILHOUETTE_KEY, anchorA = anchorA, anchorB = anchorB)

    companion object {
        const val ORION_SILHOUETTE_KEY = "orion_silhouette_v1"
    }
}
