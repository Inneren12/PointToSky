package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [InfoPanel]'s presentation (legacy-target-identity-leak fix): with
 * `showLegacyOverlay` off, the nearest-object text AND the watch-target action button must both be
 * absent - not merely the text, which is the exact bug this fix closes (the button alone kept naming and
 * acting on a hidden legacy-catalog object). Alt/Az and RA/Dec are the reticle's own aim readout and must
 * remain present either way.
 *
 * Calls [InfoPanel] directly with plain [Horizontal]/[Equatorial] literals - never the internal
 * `OverlayData`/`OverlayObject` types, which are not exposed publicly solely for testing (see
 * [InfoPanel]'s own KDoc for why its signature was refactored to make this possible).
 *
 * Deliberately does **not** call `infoPanelVisibility(showLegacyOverlay)` (the production presentation
 * policy, `internal` in `main`): this `androidTest` source set is compiled as a separate Kotlin module
 * per build variant and is not guaranteed friend-module access to `main`'s `internal` declarations -
 * unlike the JVM `test` source set, which the Kotlin Gradle plugin does associate with `main` as a friend
 * compilation by default. `infoPanelVisibility`'s own two-case truth table (`showLegacyOverlay` -> both
 * `showNearestObject` and `showTargetAction`) is exercised directly, with no Compose host needed, by
 * `InfoPanelVisibilityTest` in the `test` source set instead. Here, the two presentation values are
 * passed as the explicit literals that policy is documented to produce, so this file proves [InfoPanel]'s
 * own rendering decision without depending on `main`'s internal API surface at all.
 */
@RunWith(AndroidJUnit4::class)
class InfoPanelPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val reticleHorizontal = Horizontal(azDeg = 120.0, altDeg = 45.0)
    private val reticleEquatorial = Equatorial(raDeg = 83.0, decDeg = 22.0)

    @Test
    fun legacyOverlayOffHidesNearestObjectAndTargetActionButKeepsAltAzAndRaDec() {
        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = "32 Persei",
                nearestObjectSeparationDeg = 1.5,
                targetLabel = "32 Persei",
                // Explicit literals mirroring infoPanelVisibility(showLegacyOverlay = false) - see this
                // class's own KDoc for why this file calls that function's documented output directly
                // rather than the function itself.
                showNearestObject = false,
                showTargetAction = false,
                onSetTarget = {},
            )
        }

        composeTestRule.onNodeWithTag(AR_INFO_PANEL_ALT_AZ_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_RA_DEC_TEST_TAG).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(AR_INFO_PANEL_NEAREST_OBJECT_TEST_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun legacyOverlayOnShowsNearestObjectAndTargetActionAlongsideAltAzAndRaDec() {
        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = "32 Persei",
                nearestObjectSeparationDeg = 1.5,
                targetLabel = "32 Persei",
                // Explicit literals mirroring infoPanelVisibility(showLegacyOverlay = true).
                showNearestObject = true,
                showTargetAction = true,
                onSetTarget = {},
            )
        }

        composeTestRule.onNodeWithTag(AR_INFO_PANEL_ALT_AZ_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_RA_DEC_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_NEAREST_OBJECT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun targetActionClickInvokesOnSetTargetOnlyWhenVisible() {
        var clicked = false

        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = "32 Persei",
                nearestObjectSeparationDeg = 1.5,
                targetLabel = "32 Persei",
                showNearestObject = true,
                showTargetAction = true,
                onSetTarget = { clicked = true },
            )
        }

        composeTestRule.onNodeWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).performClick()

        assertTrue(clicked)
    }

    @Test
    fun noNearestObjectHidesNearestObjectTextEvenWhenLegacyOverlayIsOn() {
        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = null,
                nearestObjectSeparationDeg = null,
                targetLabel = "Reticle",
                showNearestObject = true,
                showTargetAction = true,
                onSetTarget = {},
            )
        }

        composeTestRule.onAllNodesWithTag(AR_INFO_PANEL_NEAREST_OBJECT_TEST_TAG).assertCountEquals(0)
        // The target action's underlying coordinates (ArScreen's `target`/`ArTarget.raDeg`/`decDeg`) are
        // always the reticle's own aim direction, independent of nearestLabel - a `null` nearest object
        // never blocks the button from existing (it falls back to a generic label, see ArScreen's own
        // `targetLabel` computation). But in the current legacy AR presentation the button's rendered
        // label/action is still legacy-derived content by construction (targetLabel defaults to
        // `overlay?.nearestLabel?.title` whenever a nearest object *does* exist), so this component
        // renders it here only because showTargetAction was passed true directly - production code always
        // hides it whenever legacy isolation is enabled, regardless of whether a nearest object exists
        // (see infoPanelVisibility - both showNearestObject and showTargetAction come from the same flag).
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).assertIsDisplayed()
    }
}
