package dev.pointtosky.wear.tile.tonight

/**
 * Снимок настроек тайла (используется провайдером и рендером).
 */
data class TonightConfig(
    val magLimit: Double = 2.0,      // предел звёздной величины
    val minAltDeg: Double = 15.0,    // минимальная высота объекта
    val maxItems: Int = 3,           // максимум элементов в тайле (2..3)
    val preferPlanets: Boolean = true
) {
    fun clamped(): TonightConfig =
        copy(maxItems = maxItems.coerceIn(2, 3))
}
