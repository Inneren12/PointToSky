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
 */
@RunWith(AndroidJUnit4::class)
class InfoPanelPresentationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val reticleHorizontal = Horizontal(azDeg = 120.0, altDeg = 45.0)
    private val reticleEquatorial = Equatorial(raDeg = 83.0, decDeg = 22.0)

    @Test
    fun legacyOverlayOffHidesNearestObjectAndTargetActionButKeepsAltAzAndRaDec() {
        val visibility = infoPanelVisibility(showLegacyOverlay = false)

        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = "32 Persei",
                nearestObjectSeparationDeg = 1.5,
                targetLabel = "32 Persei",
                showNearestObject = visibility.showNearestObject,
                showTargetAction = visibility.showTargetAction,
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
        val visibility = infoPanelVisibility(showLegacyOverlay = true)

        composeTestRule.setContent {
            InfoPanel(
                reticleHorizontal = reticleHorizontal,
                reticleEquatorial = reticleEquatorial,
                nearestObjectTitle = "32 Persei",
                nearestObjectSeparationDeg = 1.5,
                targetLabel = "32 Persei",
                showNearestObject = visibility.showNearestObject,
                showTargetAction = visibility.showTargetAction,
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
        // The target action itself is independent of whether a nearest object exists - it always
        // targets the reticle's own aim direction (see ArScreen's `target`/`ArTarget` construction).
        composeTestRule.onNodeWithTag(AR_INFO_PANEL_TARGET_ACTION_TEST_TAG).assertIsDisplayed()
    }
}
