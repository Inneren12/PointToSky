package dev.pointtosky.mobile.ar.camera

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [SkyLocationPermissionAction] — the SKY-1 location-permission action.
 *
 * The action is exercised directly rather than through [SkySessionCaptureScreen], for the same reason
 * [FrameContentExperimentLiveOverlayUiTest] exercises its overlay directly: this container has no
 * camera to bind and no location provider to grant, and neither is what is under test. What is under
 * test is that an operator whose location permission is missing is *offered* the grant, that the offer
 * disappears once it is not needed, and that nothing requests a permission on its own.
 */
@RunWith(AndroidJUnit4::class)
class SkyLocationPermissionActionUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theGrantActionIsOfferedWhenLocationPermissionIsAbsent() {
        composeTestRule.setContent {
            SkyLocationPermissionAction(hasLocationPermission = false, onRequestLocationPermission = {})
        }

        composeTestRule.onNodeWithTag(TAG_SKY_REQUEST_LOCATION_PERMISSION).assertIsDisplayed()
    }

    @Test
    fun theGrantActionIsAbsentOnceLocationPermissionIsGranted() {
        composeTestRule.setContent {
            SkyLocationPermissionAction(hasLocationPermission = true, onRequestLocationPermission = {})
        }

        composeTestRule.onNodeWithTag(TAG_SKY_REQUEST_LOCATION_PERMISSION).assertDoesNotExist()
    }

    @Test
    fun nothingIsRequestedUntilTheOperatorTapsTheAction() {
        // A permission dialog thrown at an operator the instant the screen composes is both hostile and
        // unreliable; the request must be an explicit act.
        var requests = 0
        composeTestRule.setContent {
            SkyLocationPermissionAction(
                hasLocationPermission = false,
                onRequestLocationPermission = { requests += 1 },
            )
        }

        composeTestRule.waitForIdle()
        assertEquals(0, requests)

        composeTestRule.onNodeWithTag(TAG_SKY_REQUEST_LOCATION_PERMISSION).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, requests)
    }

    @Test
    fun theActionDisappearsWhenTheGrantArrives() {
        var granted by mutableStateOf(false)
        composeTestRule.setContent {
            SkyLocationPermissionAction(hasLocationPermission = granted, onRequestLocationPermission = { granted = true })
        }

        composeTestRule.onNodeWithTag(TAG_SKY_REQUEST_LOCATION_PERMISSION).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_SKY_REQUEST_LOCATION_PERMISSION).assertDoesNotExist()
    }
}
