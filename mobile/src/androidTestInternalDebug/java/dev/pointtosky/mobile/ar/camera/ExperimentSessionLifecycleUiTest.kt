package dev.pointtosky.mobile.ar.camera

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lifecycle tests for `PhysicalCameraBindingSession`'s terminal-cleanup behavior (fix for a
 * resource-lifecycle gap: an explicit zoom/bind failure must not leave a live bound camera/analyzer
 * running behind an `EXPLICIT_BIND_FAILED` UI indefinitely) **and** for a terminal-UI reachability defect
 * a review found afterward: a prior revision rendered Retry/Back as the *first* child of a plain `Box`,
 * then rendered a fullscreen, scrollable report layer as the *second* child - later `Box` children draw
 * on top, so that report layer visually covered Retry/Back and intercepted every pointer event over the
 * whole screen, making them physically unreachable despite existing in the semantics tree. Fixed:
 * `PhysicalCameraBindingSession` now renders exactly one scrollable `Column` while
 * [ExperimentSessionState.isTerminallyFailed] - failure banner, report text, Retry, Back to candidates,
 * as real siblings in one layout, never two independently-`fillMaxSize`d layers stacked in a `Box`. The
 * tests below scroll and click through real Compose UI test APIs - `performScrollToNode`/`performClick`
 * - never merely asserting a node exists in the semantics tree, which the prior (undetected) defect would
 * still have passed.
 *
 * These tests exercise [PhysicalCameraBindingSession] with an [ExperimentSessionState] whose
 * [ExperimentSessionState.isTerminallyFailed] is already `true` - by inspection of
 * `PhysicalCameraBindingExperimentScreen.kt`'s own `if (state.isTerminallyFailed)` early-return branch,
 * [dev.pointtosky.mobile.ar.CameraPreview] is never even called in that state, so this test never touches
 * real camera hardware - this container has none (see `docs/validation/cam_2c_pixel9_evidence.md`) -
 * while still proving the actual composable that would otherwise keep a live `CameraPreview` mounted
 * renders the terminal-failure UI instead, with reachable, working Retry/Back actions.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentSessionLifecycleUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun failedState() =
        initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2")
            .reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")

    /** Constrains [PhysicalCameraBindingSession] to a small, fixed viewport - well under a typical
     * device screen - so a real scroll is required to reach every node below, regardless of the actual
     * emulator/device screen size a `connectedInternalDebugAndroidTest` run happens to use. */
    @Composable
    private fun SmallScreenHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.size(320.dp, 480.dp)) {
            content()
        }
    }

    @Test
    fun explicitBindFailureShowsTheFailureBannerAndReportAndNeverAttemptsToRenderCameraPreview() {
        composeTestRule.setContent {
            PhysicalCameraBindingSession(
                state = failedState(),
                onUpdateSession = { _, _ -> error("a terminally failed session must never report a further update") },
                onRetry = {},
                onBackToCandidates = {},
            )
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_failed_banner")
            .assert(hasText("explicit_selector_zoom_failed", substring = true))
        composeTestRule.onNodeWithTag("physical_camera_experiment_report")
            .assert(hasText("status=EXPLICIT_BIND_FAILED", substring = true))
    }

    @Test
    fun retryAndBackAreBothReachableAndTappableAlongsideTheReportOnASmallScreen() {
        // Task: "Retry and Back must remain visible and tappable with long report content and on a
        // small screen" + "verifies the report remains available without covering the controls" - a
        // deliberately small viewport (well under a typical device height) forces every node below to
        // require a real scroll to reach, which the prior Box-stacked layout could never have passed:
        // the report layer covered the controls at any viewport size, small or large.
        composeTestRule.setContent {
            SmallScreenHost {
                PhysicalCameraBindingSession(
                    state = failedState(),
                    onUpdateSession = { _, _ -> },
                    onRetry = {},
                    onBackToCandidates = {},
                )
            }
        }

        // The scrollable container itself is reachable and carries a real scroll action - proof this is
        // one coherent scrollable layout, not a static screen an overlay could still cover.
        composeTestRule.onNodeWithTag("physical_camera_experiment_terminal_scroll").assert(hasScrollAction())

        // Scroll to, then assert displayed: the banner, the report, and both controls - all as real
        // siblings a test can navigate to individually, never blocked by a layer drawn on top.
        val scroll = composeTestRule.onNodeWithTag("physical_camera_experiment_terminal_scroll")
        scroll.performScrollToNode(hasTestTag("physical_camera_experiment_failed_banner"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_failed_banner").assertIsDisplayed()

        scroll.performScrollToNode(hasTestTag("physical_camera_experiment_report"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_report")
            .assertIsDisplayed()
            .assert(hasText("status=EXPLICIT_BIND_FAILED", substring = true))

        scroll.performScrollToNode(hasTestTag("physical_camera_experiment_retry"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_retry").assertIsDisplayed()

        scroll.performScrollToNode(hasTestTag("physical_camera_experiment_back_to_candidates"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_back_to_candidates").assertIsDisplayed()
    }

    @Test
    fun retryActionInvokesOnRetryExactlyOnceAfterScrollingToReachIt() {
        var retryCount = 0

        composeTestRule.setContent {
            SmallScreenHost {
                PhysicalCameraBindingSession(
                    state = failedState(),
                    onUpdateSession = { _, _ -> },
                    onRetry = { retryCount += 1 },
                    onBackToCandidates = { error("retry must never also invoke onBackToCandidates") },
                )
            }
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_terminal_scroll")
            .performScrollToNode(hasTestTag("physical_camera_experiment_retry"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_retry")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun backToCandidatesActionInvokesOnBackToCandidatesExactlyOnceAfterScrollingToReachIt() {
        var backCount = 0

        composeTestRule.setContent {
            SmallScreenHost {
                PhysicalCameraBindingSession(
                    state = failedState(),
                    onUpdateSession = { _, _ -> },
                    onRetry = { error("back must never also invoke onRetry") },
                    onBackToCandidates = { backCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_terminal_scroll")
            .performScrollToNode(hasTestTag("physical_camera_experiment_back_to_candidates"))
        composeTestRule.onNodeWithTag("physical_camera_experiment_back_to_candidates")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun aRetriedAttemptStartsCleanWithNoBindingNoFrameAndNoLongerTerminallyFailed() {
        // Task: "retry candidate -> fresh session" - proven here via the exact same ExperimentUiModel
        // production code PhysicalCameraBindingExperimentScreen itself calls (see
        // ExperimentUiModelTest for the pure-logic coverage of this same guarantee).
        val started = ExperimentUiModel().startAttempt("2")
        val attemptId = started.session!!.attemptId
        val failed = started.updateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, "explicit_selector_zoom_failed") }

        val retried = failed.retry()

        val retriedSession = retried.session!!
        assertEquals(false, retriedSession.isTerminallyFailed)
        assertEquals(null, retriedSession.bindingResolution)
        assertEquals(null, retriedSession.latestFrame)
        assertEquals(attemptId + 1, retriedSession.attemptId)
    }
}
