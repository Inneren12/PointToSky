package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [CameraSessionLifecycle] (CAM-1c bind/dispose-race fix). No CameraX type
 * appears anywhere in this suite - only recording fake callbacks - so these exercise the ordering
 * and idempotency guarantees without a real camera, `ImageAnalysis`, or `ProcessCameraProvider`.
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
}
