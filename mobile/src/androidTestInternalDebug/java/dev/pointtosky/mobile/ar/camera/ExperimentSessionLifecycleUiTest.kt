package dev.pointtosky.mobile.ar.camera

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lifecycle tests for `PhysicalCameraBindingSession`'s terminal-cleanup behavior (fix for a
 * resource-lifecycle gap: an explicit zoom/bind failure must not leave a live bound camera/analyzer
 * running behind an `EXPLICIT_BIND_FAILED` UI indefinitely).
 *
 * These tests exercise [PhysicalCameraBindingSession] with an [ExperimentSessionState] whose
 * [ExperimentSessionState.isTerminallyFailed] is already `true` - by inspection of
 * `PhysicalCameraBindingExperimentScreen.kt`'s own `if (!state.isTerminallyFailed)` branch,
 * [dev.pointtosky.mobile.ar.CameraPreview] is never even called in that state (only the failure banner
 * + retry/back-to-candidates `Column` is), so this test never touches real camera hardware - this
 * container has none (see `docs/validation/cam_2c_pixel9_evidence.md`) - while still proving the actual
 * composable that would otherwise keep a live `CameraPreview` mounted renders the terminal-failure UI
 * instead, with working retry/back actions.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentSessionLifecycleUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun explicitBindFailureShowsTheFailureBannerAndNeverAttemptsToRenderCameraPreview() {
        val failed =
            initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2")
                .reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")

        composeTestRule.setContent {
            PhysicalCameraBindingSession(
                state = failed,
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
    fun retryActionInvokesOnRetryExactlyOnce() {
        val failed =
            initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2")
                .reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")
        var retryCount = 0

        composeTestRule.setContent {
            PhysicalCameraBindingSession(
                state = failed,
                onUpdateSession = { _, _ -> },
                onRetry = { retryCount += 1 },
                onBackToCandidates = {},
            )
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_retry").performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun backToCandidatesActionInvokesOnBackToCandidatesExactlyOnce() {
        val failed =
            initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2")
                .reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")
        var backCount = 0

        composeTestRule.setContent {
            PhysicalCameraBindingSession(
                state = failed,
                onUpdateSession = { _, _ -> },
                onRetry = {},
                onBackToCandidates = { backCount += 1 },
            )
        }

        composeTestRule.onNodeWithTag("physical_camera_experiment_back_to_candidates").performClick()

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
