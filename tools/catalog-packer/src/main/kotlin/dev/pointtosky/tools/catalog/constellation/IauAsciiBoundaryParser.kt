package dev.pointtosky.tools.catalog.constellation

class IauAsciiBoundaryParser {
    private val numberRegex = Regex("[-+]?\\d+(?:\\.\\d+)?")

    fun parse(lines: List<String>): List<ParsedConstellation> {
        val edgesByConstellation = linkedMapOf<String, MutableList<ParsedEdge>>()
        val raSamples = mutableListOf<Double>()

        for (line in lines) {
            val trimmed = line.substringBefore('#').substringBefore("//").replace(';', ' ').replace(',', ' ').trim()
            if (trimmed.isEmpty()) continue

            val code = parseCode(trimmed) ?: continue
            val numbers = numberRegex.findAll(trimmed).map { it.value.toDouble() }.toList()
            if (numbers.size < 4) {
                error("Line must contain at least four numbers: '$line'")
            }

            val start = ParsedVertex(numbers[0], numbers[1])
            val end = ParsedVertex(numbers[2], numbers[3])
            raSamples += start.ra
            raSamples += end.ra

            edgesByConstellation.getOrPut(code) { mutableListOf() }
                .add(ParsedEdge(start, end))
        }

        if (edgesByConstellation.isEmpty()) {
            return emptyList()
        }

        val maxRa = raSamples.maxOrNull() ?: 0.0
        val raMultiplier = if (maxRa <= 24.1) 15.0 else 1.0

        return edgesByConstellation.map { (code, edges) ->
            ParsedConstellation(
                code = code,
                edges = edges.map { edge ->
                    edge.copy(
                        start = edge.start.multiplyRa(raMultiplier),
                        end = edge.end.multiplyRa(raMultiplier),
                    )
                },
            )
        }
    }

    private fun parseCode(line: String): String? {
        if (line.length < 3) return null
        val head = line.substring(0, 3)
        val lettersOnly = head.all { it.isLetter() }
        return if (lettersOnly) head.uppercase() else null
    }
}

data class ParsedConstellation(
    val code: String,
    val edges: List<ParsedEdge>,
)

data class ParsedEdge(
    val start: ParsedVertex,
    val end: ParsedVertex,
)

data class ParsedVertex(
    val ra: Double,
    val dec: Double,
) {
    fun multiplyRa(multiplier: Double): ParsedVertex = copy(ra = ra * multiplier)
}
