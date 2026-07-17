package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [ExperimentUiModel]'s screen-level transitions - "add lifecycle tests covering...
 * retry candidate -> fresh session; previous attempt cannot overwrite the retry state". The per-attempt
 * reducer behavior itself (a terminal failure/superseded attemptId being a no-op) is
 * [ExperimentSessionStateTest]'s job; this file is about the layer above it - starting, retrying, and
 * abandoning attempts.
 */
class ExperimentUiModelTest {
    @Test
    fun `a fresh model has no session and an attemptId counter starting at zero`() {
        val model = ExperimentUiModel()

        assertNull(model.session)
        assertEquals(0L, model.nextAttemptId)
    }

    @Test
    fun `starting an attempt creates a session with attemptId 0 and advances the counter`() {
        val model = ExperimentUiModel().startAttempt("2")

        assertEquals(0L, model.session?.attemptId)
        assertEquals("2", model.session?.physicalCameraId)
        assertEquals(1L, model.nextAttemptId)
    }

    @Test
    fun `retry with no session yet is a no-op`() {
        val model = ExperimentUiModel()

        assertSame(model, model.retry())
    }

    @Test
    fun `retry candidate starts a fresh session with a brand-new attemptId for the same candidate`() {
        val started = ExperimentUiModel().startAttempt("2")
        val attemptId = started.session!!.attemptId
        val failed = started.updateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, "explicit_selector_zoom_failed") }
        assertTrue(failed.session!!.isTerminallyFailed)

        val retried = failed.retry()

        assertEquals("2", retried.session?.physicalCameraId)
        assertEquals(attemptId + 1, retried.session?.attemptId)
        assertFalse(retried.session!!.isTerminallyFailed)
        assertNull(retried.session?.bindingResolution)
        assertNull(retried.session?.latestFrame)
    }

    @Test
    fun `backToCandidates discards the current session but keeps the attemptId counter monotonic`() {
        val started = ExperimentUiModel().startAttempt("2")

        val back = started.backToCandidates()

        assertNull(back.session)
        assertEquals(1L, back.nextAttemptId)

        val restarted = back.startAttempt("3")
        assertEquals(1L, restarted.session?.attemptId)
        assertEquals("3", restarted.session?.physicalCameraId)
    }

    @Test
    fun `updateSession with no session yet is a no-op`() {
        val model = ExperimentUiModel()

        assertSame(model, model.updateSession(0L) { it })
    }

    @Test
    fun `updateSession applies the reducer only when attemptId matches the live session`() {
        val model = ExperimentUiModel().startAttempt("2")
        val attemptId = model.session!!.attemptId

        val updated = model.updateSession(attemptId) { it.reduceExplicitBindFailure(attemptId, "explicit_selector_zoom_failed") }

        assertTrue(updated.session!!.isTerminallyFailed)
    }

    @Test
    fun `a callback from a superseded attemptId never overwrites the retried session`() {
        // Task: "previous attempt cannot overwrite a later attempt for candidate B" / "previous attempt
        // cannot overwrite the retry state" - reproduced end-to-end through ExperimentUiModel itself,
        // not just the underlying ExperimentSessionState reducer guard.
        val started = ExperimentUiModel().startAttempt("2")
        val staleAttemptId = started.session!!.attemptId
        val failed =
            started.updateSession(staleAttemptId) { it.reduceExplicitBindFailure(staleAttemptId, "explicit_selector_zoom_failed") }
        val retried = failed.retry()

        // A late callback belonging to the already-failed, already-retried attempt fires after retry()
        // already replaced it with a fresh session.
        val afterStaleCallback =
            retried.updateSession(staleAttemptId) { it.reduceExplicitBindFailure(staleAttemptId, "late_stale_failure") }

        assertSame(retried.session, afterStaleCallback.session)
        assertFalse(afterStaleCallback.session!!.isTerminallyFailed)
        assertEquals(retried.session?.attemptId, afterStaleCallback.session?.attemptId)
    }
}
