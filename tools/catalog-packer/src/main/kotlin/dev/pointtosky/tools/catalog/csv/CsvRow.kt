package dev.pointtosky.tools.catalog.csv

internal class CsvRow(private val row: Map<String, String>) {
    private val normalized = row.mapKeys { it.key.trim().lowercase() }

    fun value(vararg keys: String): String? {
        for (key in keys) {
            val normalizedKey = key.trim().lowercase()
            val hit = normalized[normalizedKey]
            if (hit != null && hit.isNotBlank()) {
                return hit.trim()
            }
        }
        return null
    }

    fun string(vararg keys: String): String? = value(*keys)

    fun double(vararg keys: String): Double? = value(*keys)?.toDoubleOrNull()

    fun int(vararg keys: String): Int? = value(*keys)?.toIntOrNull()
}
