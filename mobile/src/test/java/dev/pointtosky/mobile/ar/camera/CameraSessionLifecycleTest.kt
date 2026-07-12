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

    @Test
    fun `successful session cleanup clears analyzer and unbinds before shutting down the executor`() {
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

    private companion object {
        const val RACE_ITERATIONS = 2000
    }
}
