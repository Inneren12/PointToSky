package dev.pointtosky.wear.complication

import androidx.annotation.VisibleForTesting
import dev.pointtosky.wear.complication.config.TonightPrefs
import dev.pointtosky.wear.tile.tonight.TonightIcon
import dev.pointtosky.wear.tile.tonight.TonightTarget

/**
 * Helper that applies complication preferences to the Tonight target list.
 */
internal object TonightTargetSelector {
    fun selectTargets(
        items: List<TonightTarget>,
        prefs: TonightPrefs,
    ): List<TonightTarget> {
        val limit = prefs.magLimit.toDouble()
        val filtered =
            items.filter { target ->
                val magnitude = parseMagnitude(target)
                magnitude?.let { it <= limit } ?: true
            }
        if (!prefs.preferPlanets) {
            return filtered
        }
        return filtered
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<TonightTarget>> { indexed ->
                    if (isPlanet(indexed.value)) 1 else 0
                }.thenBy { indexed -> indexed.index },
            ).map { it.value }
    }

    @VisibleForTesting
    internal fun isPlanet(target: TonightTarget): Boolean =
        when (target.icon) {
            TonightIcon.SUN,
            TonightIcon.MOON,
            TonightIcon.JUPITER,
            TonightIcon.SATURN,
            -> true
            else -> false
        }

    private fun parseMagnitude(target: TonightTarget): Double? {
        val regex = Regex("mag\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        return sequenceOf(target.subtitle, target.title, target.id)
            .filterNotNull()
            .mapNotNull { value ->
                regex
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
            }.firstOrNull()
    }
}
