package dev.pointtosky.mobile.ar

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM tests for [infoPanelVisibility] (legacy-target-identity-leak fix): a pure function, no Compose
 * host or `OverlayData` needed. Proves the nearest-object text and the watch-target action button are
 * driven by the exact same [PredictedStarDebugControlsState.showLegacyOverlay] flag, so a future change
 * can't gate one without the other - the bug this fix closes (the button alone stayed visible, still
 * naming and still able to act on a hidden legacy-catalog object, while the text line was already
 * correctly gated).
 */
class InfoPanelVisibilityTest {
    @Test
    fun `legacy overlay on shows both the nearest-object text and the target action`() {
        val visibility = infoPanelVisibility(showLegacyOverlay = true)

        assertEquals(InfoPanelVisibility(showNearestObject = true, showTargetAction = true), visibility)
    }

    @Test
    fun `legacy overlay off hides both the nearest-object text and the target action`() {
        val visibility = infoPanelVisibility(showLegacyOverlay = false)

        assertEquals(InfoPanelVisibility(showNearestObject = false, showTargetAction = false), visibility)
    }

    @Test
    fun `showNearestObject and showTargetAction never diverge for either input`() {
        listOf(true, false).forEach { showLegacyOverlay ->
            val visibility = infoPanelVisibility(showLegacyOverlay)

            assertEquals(
                visibility.showNearestObject,
                visibility.showTargetAction,
                "showNearestObject and showTargetAction must never diverge; was $visibility for showLegacyOverlay=$showLegacyOverlay",
            )
        }
    }
}
