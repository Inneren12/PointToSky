package dev.pointtosky.tools.catalog.constellation

import kotlin.math.abs
import kotlin.math.roundToLong

class ConstellationProcessor {
    fun prepare(parsed: List<ParsedConstellation>, epsilon: Double): PackedCatalog {
        val parsedMap = parsed.associateBy { it.code }
        val constellations = IAU_CODES.map { code ->
            val source = parsedMap[code]
            val polygons = if (source != null) {
                buildPolygons(source.edges)
                    .map { vertices -> simplify(vertices, epsilon) }
                    .map { vertices -> bridgeWrap(vertices) }
                    .map { vertices -> ensureClosed(vertices) }
                    .map { vertices -> removeRedundant(vertices) }
                    .map { vertices -> PackedPolygon(vertices, computeAabb(vertices)) }
                    .filter { it.vertices.size >= 4 }
            } else {
                emptyList()
            }

            val aabb = mergeAabb(polygons.map { it.aabb })
            PackedConstellation(code, polygons, aabb)
        }

        val polyCount = constellations.sumOf { it.polygons.size }
        val vertexCount = constellations.sumOf { constellation ->
            constellation.polygons.sumOf { it.vertices.size }
        }

        return PackedCatalog(constellations, polyCount, vertexCount)
    }

    private fun buildPolygons(edges: List<ParsedEdge>): List<List<Vertex>> {
        if (edges.isEmpty()) return emptyList()

        val converted = edges.map { edge ->
            val start = Vertex(edge.start.ra, edge.start.dec)
            val end = Vertex(edge.end.ra, edge.end.dec)
            Edge(start, end)
        }

        val adjacency = mutableMapOf<VertexKey, MutableList<Int>>()
        converted.forEachIndexed { index, edge ->
            adjacency.getOrPut(edge.start.key()) { mutableListOf() }.add(index)
            adjacency.getOrPut(edge.end.key()) { mutableListOf() }.add(index)
        }

        val remaining = BooleanArray(converted.size) { true }
        val polygons = mutableListOf<List<Vertex>>()

        for (i in converted.indices) {
            if (!remaining[i]) continue
            val path = mutableListOf<Vertex>()
            val currentEdgeIndex = i
            val current = converted[currentEdgeIndex]
            remaining[currentEdgeIndex] = false
            path += current.start
            path += current.end
            var currentKey = current.end.key()

            while (true) {
                val nextEdgeIndex = adjacency[currentKey]?.firstOrNull { remaining[it] }
                    ?: break
                val nextEdge = converted[nextEdgeIndex]
                remaining[nextEdgeIndex] = false
                val oriented = if (nextEdge.start.key() == currentKey) {
                    nextEdge
                } else {
                    nextEdge.reversed()
                }
                path += oriented.end
                currentKey = oriented.end.key()
            }

            if (path.size >= 3) {
                polygons += path
            }
        }

        return polygons
    }

    private fun Edge.reversed(): Edge = Edge(end, start)

    private fun simplify(vertices: List<Vertex>, epsilon: Double): List<Vertex> {
        if (vertices.size <= 3) return vertices
        val unwrapped = unwrap(vertices)
        val simplified = rdp(unwrapped, epsilon)
        return wrapBack(simplified)
    }

    private fun unwrap(vertices: List<Vertex>): List<Vertex> {
        if (vertices.isEmpty()) return vertices
        val result = ArrayList<Vertex>(vertices.size)
        var previousRa = vertices.first().ra
        result.add(vertices.first())
        for (i in 1 until vertices.size) {
            var ra = vertices[i].ra
            val diff = ra - previousRa
            if (diff > 180) {
                ra -= 360
            } else if (diff < -180) {
                ra += 360
            }
            result.add(Vertex(ra, vertices[i].dec))
            previousRa = ra
        }
        return result
    }

    private fun wrapBack(vertices: List<Vertex>): List<Vertex> = vertices.map { vertex ->
        val normalized = normalizeRa(vertex.ra)
        Vertex(normalized, vertex.dec)
    }

    private fun normalizeRa(value: Double): Double {
        var ra = value % 360.0
        if (ra < 0) ra += 360.0
        return if (ra == 360.0) 0.0 else ra
    }

    private fun rdp(points: List<Vertex>, epsilon: Double): List<Vertex> {
        if (points.size <= 2) return points
        val lastIndex = points.lastIndex
        var maxDistance = 0.0
        var index = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until lastIndex) {
            val distance = perpendicularDistance(points[i], start, end)
            if (distance > maxDistance) {
                index = i
                maxDistance = distance
            }
        }

        return if (maxDistance > epsilon) {
            val left = rdp(points.subList(0, index + 1), epsilon)
            val right = rdp(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(point: Vertex, start: Vertex, end: Vertex): Double {
        val dx = end.ra - start.ra
        val dy = end.dec - start.dec
        if (abs(dx) < 1e-9 && abs(dy) < 1e-9) {
            return distance(point, start)
        }
        val t = ((point.ra - start.ra) * dx + (point.dec - start.dec) * dy) / (dx * dx + dy * dy)
        val projection = Vertex(start.ra + t * dx, start.dec + t * dy)
        return distance(point, projection)
    }

    private fun distance(a: Vertex, b: Vertex): Double {
        val dx = a.ra - b.ra
        val dy = a.dec - b.dec
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun bridgeWrap(vertices: List<Vertex>): List<Vertex> {
        if (vertices.isEmpty()) return vertices
        val normalized = vertices.map { vertex -> vertex.copy(ra = normalizeRa(vertex.ra)) }
        val result = mutableListOf<Vertex>()
        val size = normalized.size
        for (i in 0 until size) {
            val current = normalized[i]
            val next = normalized[(i + 1) % size]
            appendSegment(result, current, next)
        }
        if (result.isNotEmpty()) {
            val first = result.first()
            val last = result.last()
            if (!last.isApproximately(first)) {
                result.add(first)
            }
        }
        return result
    }

    private fun appendSegment(result: MutableList<Vertex>, current: Vertex, next: Vertex) {
        if (result.isEmpty() || !result.last().isApproximately(current)) {
            result.add(current)
        }
        val startRa = current.ra
        val startDec = current.dec
        var targetRa = next.ra
        val targetDec = next.dec
        val diff = targetRa - startRa
        if (diff > 180) {
            targetRa -= 360
        } else if (diff < -180) {
            targetRa += 360
        }

        var currentRa = startRa
        var currentDec = startDec
        var adjustedTargetRa = targetRa
        val adjustedTargetDec = targetDec

        while (adjustedTargetRa > 360 || adjustedTargetRa < 0) {
            val boundary = if (adjustedTargetRa > 360) 360.0 else 0.0
            val t = if (adjustedTargetRa == currentRa) 0.0 else (boundary - currentRa) / (adjustedTargetRa - currentRa)
            val boundaryDec = currentDec + t * (adjustedTargetDec - currentDec)
            val boundaryVertex = Vertex(boundary, boundaryDec)
            if (!result.last().isApproximately(boundaryVertex)) {
                result.add(boundaryVertex)
            }
            val wrappedRa = if (boundary == 360.0) 0.0 else 360.0
            val wrappedVertex = Vertex(wrappedRa, boundaryDec)
            if (!result.last().isApproximately(wrappedVertex)) {
                result.add(wrappedVertex)
            }
            currentRa = wrappedVertex.ra
            currentDec = wrappedVertex.dec
            adjustedTargetRa = if (boundary == 360.0) adjustedTargetRa - 360 else adjustedTargetRa + 360
        }

        val finalVertex = Vertex(normalizeRa(adjustedTargetRa), adjustedTargetDec)
        if (!result.last().isApproximately(finalVertex)) {
            result.add(finalVertex)
        }
    }

    private fun ensureClosed(vertices: List<Vertex>): List<Vertex> {
        if (vertices.isEmpty()) return vertices
        val first = vertices.first()
        val last = vertices.last()
        return if (last.isApproximately(first)) vertices else vertices + first
    }

    private fun removeRedundant(vertices: List<Vertex>): List<Vertex> {
        if (vertices.size <= 2) return vertices
        val filtered = ArrayList<Vertex>(vertices.size)
        for (vertex in vertices) {
            if (filtered.isEmpty() || !filtered.last().isApproximately(vertex)) {
                filtered.add(vertex)
            }
        }
        return filtered
    }

    private fun computeAabb(vertices: List<Vertex>): Aabb {
        val decMin = vertices.minOfOrNull { it.dec } ?: 0.0
        val decMax = vertices.maxOfOrNull { it.dec } ?: 0.0
        val circular = computeCircularBounds(vertices.map { it.ra })
        return Aabb(circular.min, circular.max, decMin, decMax)
    }

    private fun mergeAabb(bounds: List<Aabb>): Aabb {
        if (bounds.isEmpty()) {
            return Aabb(0.0, 0.0, 0.0, 0.0)
        }
        val decMin = bounds.minOf { it.decMin }
        val decMax = bounds.maxOf { it.decMax }
        val circular = computeCircularBounds(bounds.flatMap { listOf(it.raMin, it.raMax) })
        return Aabb(circular.min, circular.max, decMin, decMax)
    }

    private fun computeCircularBounds(values: List<Double>): CircularBounds {
        if (values.isEmpty()) return CircularBounds(0.0, 0.0)
        val normalized = values.map { normalizeRa(it) }.sorted()
        var maxGap = -1.0
        var gapIndex = 0
        for (i in normalized.indices) {
            val current = normalized[i]
            val next = normalized[(i + 1) % normalized.size]
            val diff = if (i == normalized.lastIndex) next + 360.0 - current else next - current
            if (diff > maxGap) {
                maxGap = diff
                gapIndex = i
            }
        }
        val startIndex = (gapIndex + 1) % normalized.size
        val min = normalized[startIndex]
        val max = normalized[gapIndex]
        return CircularBounds(min, max)
    }
}

data class PackedCatalog(
    val constellations: List<PackedConstellation>,
    val polygonCount: Int,
    val vertexCount: Int,
)

data class PackedConstellation(
    val code: String,
    val polygons: List<PackedPolygon>,
    val aabb: Aabb,
)

data class PackedPolygon(
    val vertices: List<Vertex>,
    val aabb: Aabb,
)

data class Vertex(
    val ra: Double,
    val dec: Double,
) {
    fun key(): VertexKey = VertexKey(quantize(ra), quantize(dec))

    fun isApproximately(other: Vertex): Boolean {
        return abs(ra - other.ra) < 1e-6 && abs(dec - other.dec) < 1e-6
    }
}

data class Edge(
    val start: Vertex,
    val end: Vertex,
)

data class VertexKey(
    val ra: Double,
    val dec: Double,
)

data class Aabb(
    val raMin: Double,
    val raMax: Double,
    val decMin: Double,
    val decMax: Double,
)

data class CircularBounds(
    val min: Double,
    val max: Double,
)

private fun quantize(value: Double): Double = (value * 1_000_000.0).roundToLong() / 1_000_000.0

private val IAU_CODES = listOf(
    "AND", "ANT", "APS", "AQL", "AQR", "ARA", "ARI", "AUR", "BOO",
    "CAE", "CAM", "CAP", "CAR", "CAS", "CEN", "CEP", "CET",
    "CHA", "CIR", "CMA", "CMI", "CNC", "COL", "COM", "CRA",
    "CRB", "CRT", "CRU", "CRV", "CVN", "CYG", "DEL", "DOR",
    "DRA", "EQU", "ERI", "FOR", "GEM", "GRU", "HER", "HOR",
    "HYA", "HYI", "IND", "LAC", "LEO", "LEP", "LIB", "LMI",
    "LUP", "LYN", "LYR", "MEN", "MIC", "MON", "MUS", "NOR",
    "OCT", "OPH", "ORI", "PAV", "PEG", "PER", "PHE", "PIC",
    "PSA", "PSC", "PUP", "PYX", "RET", "SCL", "SCO", "SCT",
    "SER", "SEX", "SGE", "SGR", "TAU", "TEL", "TRA", "TRI",
    "TUC", "UMA", "UMI", "VEL", "VIR", "VOL", "VUL",
)
