package dev.pointtosky.wear.complication

import com.google.common.truth.Truth.assertThat
import dev.pointtosky.wear.complication.config.TonightPrefs
import dev.pointtosky.wear.tile.tonight.TonightIcon
import dev.pointtosky.wear.tile.tonight.TonightTarget
import org.junit.Test

class TonightTargetSelectorTest {

    private val brightStar = TonightTarget(
        id = "STAR:VEGA",
        title = "Vega",
        subtitle = "mag 0.0",
        icon = TonightIcon.STAR,
    )

    private val dimStar = TonightTarget(
        id = "STAR:DIM",
        title = "Dim Star",
        subtitle = "mag 6.2",
        icon = TonightIcon.STAR,
    )

    private val moon = TonightTarget(
        id = "MOON",
        title = "Moon",
        subtitle = "mag -12.3",
        icon = TonightIcon.MOON,
    )

    @Test
    fun preferPlanetsTrue_movesPlanetToTop() {
        val prefs = TonightPrefs(magLimit = 5.5f, preferPlanets = true)

        val result = TonightTargetSelector.selectTargets(listOf(brightStar, moon), prefs)

        assertThat(result.first().id).isEqualTo("MOON")
    }

    @Test
    fun preferPlanetsFalse_keepsOriginalOrder() {
        val prefs = TonightPrefs(magLimit = 5.5f, preferPlanets = false)

        val result = TonightTargetSelector.selectTargets(listOf(brightStar, moon), prefs)

        assertThat(result.map { it.id }).containsExactly("STAR:VEGA", "MOON").inOrder()
    }

    @Test
    fun magnitudeLimit_filtersDimTargets() {
        val prefs = TonightPrefs(magLimit = 5.0f, preferPlanets = false)

        val result = TonightTargetSelector.selectTargets(listOf(brightStar, dimStar), prefs)

        assertThat(result).containsExactly(brightStar)
    }
}
