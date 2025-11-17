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
