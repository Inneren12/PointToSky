package dev.pointtosky.mobile.ar.camera

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pure JVM tests for [CameraSessionLifecycle] (CAM-1c bind/dispose-race fix). No CameraX type
 * appears anywhere in this suite - only recording fake callbacks - so these exercise the ordering,
 * idempotency, and race-safety guarantees without a real camera, `ImageAnalysis`, or
 * `ProcessCameraProvider`.
 */
class CameraSessionLifecycleTest {
    @Test
    fun `isDisposed starts false`() {
        val session = CameraSessionLifecycle()
        assertFalse(session.isDisposed)
    }

    // --- Scenario A: combined Preview + ImageAnalysis bind succeeds --------------------------

    @Test
    fun `scenario A - successful session cleanup clears analyzer and unbinds before shutting down the executor`() {
        val events = mutableListOf<String>()
        val session = CameraSessionLifecycle()

        val boundSessionIsActive =
            session.confirmBound {
                events += "clearAnalyzer"
                events += "unbind"
            }
        session.cleanupAndShutdown { events += "shutdownExecutor" }

        assertTrue(boundSessionIsActive)
        assertEquals(listOf("clearAnalyzer", "unbind", "shutdownExecutor"), events)
    }

    @Test
    fun `owned use cases are unbound when the composable is disposed after a normal bind`() {
        var unbound = false
        val session = CameraSessionLifecycle()

        session.confirmBound { unbound = true }
        session.markDisposed()
        session.cleanupAndShutdown {}

        assertTrue(unbound)
    }

    @Test
    fun `disposal before the coroutine attempts to bind is visible via isDisposed`() {
        // Mirrors CameraPreview's own early-exit guard, checked right after the suspending
        // ProcessCameraProvider resolves and before any CameraX bind call is made - a disposed
        // session here means the coroutine returns without ever calling confirmBound.
        val session = CameraSessionLifecycle()

        session.markDisposed()

        assertTrue(session.isDisposed)
    }

    @Test
    fun `a session disposed before the coroutine bailed never leaves cleanupAndShutdown with stale work`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        session.markDisposed()
        // confirmBound() is intentionally never called - the coroutine already bailed out.
        session.cleanupAndShutdown { shutdownCalls++ }

        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `disposal racing immediately after a successful bind triggers immediate cleanup`() {
        var cleanedUpImmediately = false
        val session = CameraSessionLifecycle()

        // Disposal happens in the window between bindToLifecycle() returning and confirmBound()
        // being called.
        session.markDisposed()
        val boundSessionIsActive = session.confirmBound { cleanedUpImmediately = true }

        assertFalse(boundSessionIsActive)
        assertTrue(cleanedUpImmediately)
    }

    @Test
    fun `a late-disposed session does not run its cleanup a second time on actual dispose`() {
        var cleanupCalls = 0
        val session = CameraSessionLifecycle()

        session.markDisposed()
        session.confirmBound { cleanupCalls++ } // runs the cleanup immediately (late-dispose path)
        session.cleanupAndShutdown {} // must not invoke the same cleanup again

        assertEquals(1, cleanupCalls)
    }

    @Test
    fun `a failed bind never registers a cleanup, so it cannot publish an active session`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        // Simulates CameraPreview's bind-failure path: it clears the analyzer itself inline and
        // returns without ever calling confirmBound(), so no session is ever registered as bound.
        session.cleanupAndShutdown { shutdownCalls++ }

        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `cleanupAndShutdown is idempotent - no double unbind, no double shutdown`() {
        var unbindCalls = 0
        var shutdownCalls = 0
        val session = CameraSessionLifecycle()

        session.confirmBound { unbindCalls++ }
        session.cleanupAndShutdown { shutdownCalls++ }
        session.cleanupAndShutdown { shutdownCalls++ }
        session.cleanupAndShutdown { shutdownCalls++ }

        assertEquals(1, unbindCalls)
        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `markDisposed is itself idempotent and safe to call multiple times`() {
        val session = CameraSessionLifecycle()

        session.markDisposed()
        session.markDisposed()
        session.markDisposed()

        assertTrue(session.isDisposed)
    }

    @Test
    fun `cleanup exception still shuts down exactly once via finally`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        session.confirmBound { throw IllegalStateException("boom") }

        assertFailsWith<IllegalStateException> {
            session.cleanupAndShutdown { shutdownCalls++ }
        }
        assertEquals(1, shutdownCalls)

        // The cleanedUp transition was already committed before the throwing cleanup ran, so a
        // second call - despite the first one throwing - must not run shutdown again.
        session.cleanupAndShutdown { shutdownCalls++ }
        assertEquals(1, shutdownCalls)
    }

    @Test
    fun `a second successful confirmBound fails fast instead of silently replacing the first cleanup`() {
        val session = CameraSessionLifecycle()
        var firstCleanupCalls = 0

        val first = session.confirmBound { firstCleanupCalls++ }
        assertTrue(first)

        assertFailsWith<IllegalStateException> {
            session.confirmBound { fail("second cleanup closure must never be registered or invoked") }
        }

        // The first registered cleanup - not a silently-overwritten second one - is still what
        // runs on dispose.
        session.cleanupAndShutdown {}
        assertEquals(1, firstCleanupCalls)
    }

    @Test
    fun `deterministic bind vs dispose race never leaves an active session uncleaned or double-cleaned`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ITERATIONS) { iteration ->
                val session = CameraSessionLifecycle()
                val barrier = CyclicBarrier(2)
                val cleanupCount = AtomicInteger(0)
                val shutdownCount = AtomicInteger(0)
                var confirmResult = false

                val bindFuture =
                    executor.submit {
                        barrier.await()
                        confirmResult = session.confirmBound { cleanupCount.incrementAndGet() }
                    }
                val disposeFuture =
                    executor.submit {
                        barrier.await()
                        session.markDisposed()
                        session.cleanupAndShutdown { shutdownCount.incrementAndGet() }
                    }

                bindFuture.get(5, TimeUnit.SECONDS)
                disposeFuture.get(5, TimeUnit.SECONDS)

                // Whichever side wins the race - bind registration (confirmResult == true, cleanup
                // runs later via disposal) or disposal (confirmResult == false, confirmBound runs
                // the cleanup itself immediately) - cleanup and shutdown each run exactly once.
                // This is the invariant the fix restores: the forbidden outcome (confirmResult ==
                // true, shutdown completed, but cleanup never ran) cannot occur if both hold.
                assertEquals(1, cleanupCount.get(), "iteration $iteration: confirmResult=$confirmResult")
                assertEquals(1, shutdownCount.get(), "iteration $iteration: confirmResult=$confirmResult")
            }
        } finally {
            executor.shutdown()
        }
    }

    // --- Scenario B: combined bind throws IllegalArgumentException -------------------------
    // (analyzer clearing and the Preview-only retry itself are CameraPreview-level CameraX calls;
    // the CameraSessionLifecycle-level property under test is the executor shutdown.)

    @Test
    fun `scenario B - executor shutdown triggered early by a rejected combined bind is not repeated by the later onDispose call`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        // Mirrors CameraPreview: IllegalArgumentException handling shuts the executor down right
        // away, before any Preview-only fallback is attempted.
        session.shutdownExecutorOnce { shutdownCalls++ }
        assertEquals(1, shutdownCalls)

        // onDispose still runs later (e.g. the fallback then succeeds and is eventually disposed,
        // or the fallback also fails) - either way cleanupAndShutdown must not shut down again.
        session.cleanupAndShutdown { shutdownCalls++ }
        assertEquals(1, shutdownCalls)
    }

    // --- Scenario C: Preview-only fallback succeeds -----------------------------------------

    @Test
    fun `scenario C - preview-only fallback registers a preview-only cleanup and never double-shuts-down the executor`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0
        var previewUnbound = false
        var imageAnalysisUnbound = false

        // The executor was already shut down early, as part of handling the rejected combined
        // bind (scenario B), before the Preview-only fallback was even attempted.
        session.shutdownExecutorOnce { shutdownCalls++ }

        // Preview-only bind succeeds; its cleanup closure touches Preview only.
        val previewSessionIsActive =
            session.confirmBound {
                previewUnbound = true
            }
        assertTrue(previewSessionIsActive)

        // onDispose later claims and runs that cleanup, then attempts shutdown again.
        session.cleanupAndShutdown { shutdownCalls++ }

        assertTrue(previewUnbound)
        assertFalse(imageAnalysisUnbound) // never referenced/unbound by the fallback cleanup
        assertEquals(1, shutdownCalls) // not double-shut-down despite two shutdownExecutorOnce-routed calls
    }

    // --- Scenario D: combined bind throws IllegalStateException, no fallback attempted -----

    @Test
    fun `scenario D - a combined-bind IllegalStateException shuts the executor down once with nothing left to clean up`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        // CameraPreview never calls confirmBound() on this path - no fallback is attempted for
        // IllegalStateException - so there is nothing registered when onDispose eventually runs.
        session.shutdownExecutorOnce { shutdownCalls++ }
        session.cleanupAndShutdown { shutdownCalls++ }

        assertEquals(1, shutdownCalls)
    }

    // --- Scenario E: both the combined bind and the Preview-only fallback fail -------------

    @Test
    fun `scenario E - combined bind and fallback both failing leaves no active session and shuts down exactly once`() {
        val session = CameraSessionLifecycle()
        var shutdownCalls = 0

        // Combined bind rejected -> early shutdown; Preview-only fallback also throws -> confirmBound
        // is never called on either attempt, so no session is ever registered as active.
        session.shutdownExecutorOnce { shutdownCalls++ }

        session.markDisposed() // eventual onDispose
        session.cleanupAndShutdown { shutdownCalls++ }

        assertEquals(1, shutdownCalls)
    }

    // --- Scenario F: disposal before the Preview-only fallback bind is attempted -----------

    @Test
    fun `scenario F - isDisposed observed before the fallback bind attempt reflects disposal that happened during combined-bind failure handling`() {
        val session = CameraSessionLifecycle()

        // CameraPreview re-checks session.isDisposed after clearing the analyzer and shutting
        // down the executor for a rejected combined bind, immediately before calling
        // bindToLifecycle(..., preview) for the Preview-only fallback - skipping that call
        // entirely if this is already true.
        session.shutdownExecutorOnce {}
        session.markDisposed()

        assertTrue(session.isDisposed)
    }

    // --- Scenario G: disposal races a successful Preview-only fallback bind ----------------

    @Test
    fun `scenario G - disposal racing a successful preview-only bind unbinds Preview immediately and exactly once`() {
        val session = CameraSessionLifecycle()
        var previewUnbindCalls = 0

        // Disposal happens in the window between the Preview-only bindToLifecycle() returning and
        // confirmBound() being called - the same late-dispose window as the combined-bind case,
        // now exercised with a Preview-only cleanup closure.
        session.markDisposed()
        val previewSessionIsActive = session.confirmBound { previewUnbindCalls++ }

        assertFalse(previewSessionIsActive)
        assertEquals(1, previewUnbindCalls)
    }

    private companion object {
        const val RACE_ITERATIONS = 2000
    }
}
