package dev.pointtosky.mobile.catalog

import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugInfo
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugSnapshot
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [RealStarVisibilityDebugProvider]'s state-publishing
 * behavior (VF-1f), driven directly through [RealStarVisibilityDebugProvider.applySnapshot]
 * so no Android [android.content.Context] or PTSKCAT0 asset is required. The
 * underlying VF-1a..d pipeline/probe logic is covered separately by
 * `RealStarVisibilityDebugProbeTest` in `core:catalog`.
 */
class RealStarVisibilityDebugProviderTest {

    private val sampleInfo =
        RealStarVisibilityDebugInfo(
            catalogCount = 42123,
            catalogMagLimit = 8.0,
            limitingMagnitude = 5.6,
            visibleCount = 12345,
        )

    @Test
    fun `success snapshot publishes Success state`() {
        RealStarVisibilityDebugProvider.applySnapshot { RealStarVisibilityDebugSnapshot.Success(sampleInfo) }

        val state = RealStarVisibilityDebugProvider.state.value
        assertTrue(state is RealStarVisibilityDebugUiState.Success)
        assertEquals(sampleInfo, (state as RealStarVisibilityDebugUiState.Success).info)
    }

    @Test
    fun `failure snapshot publishes Failure state with message`() {
        RealStarVisibilityDebugProvider.applySnapshot {
            RealStarVisibilityDebugSnapshot.Failure("boom: asset missing")
        }

        val state = RealStarVisibilityDebugProvider.state.value
        assertTrue(state is RealStarVisibilityDebugUiState.Failure)
        assertEquals("boom: asset missing", (state as RealStarVisibilityDebugUiState.Failure).message)
    }

    @Test
    fun `unexpected exception from compute is caught and published as Failure state`() {
        RealStarVisibilityDebugProvider.applySnapshot {
            throw IllegalStateException("kaboom")
        }

        val state = RealStarVisibilityDebugProvider.state.value
        assertTrue(state is RealStarVisibilityDebugUiState.Failure)
        val message = (state as RealStarVisibilityDebugUiState.Failure).message
        assertTrue(message.contains("kaboom"))
        assertTrue(message.contains("IllegalStateException"))
    }

    @Test
    fun `CancellationException from compute propagates instead of publishing Failure state`() {
        // applySnapshot runs inside scope's coroutine; folding cancellation into a
        // Failure state would break structured concurrency for whoever cancelled it.
        RealStarVisibilityDebugProvider.applySnapshot { RealStarVisibilityDebugSnapshot.Success(sampleInfo) }

        assertThrows(CancellationException::class.java) {
            RealStarVisibilityDebugProvider.applySnapshot { throw CancellationException("cancelled") }
        }

        val state = RealStarVisibilityDebugProvider.state.value
        assertTrue(state is RealStarVisibilityDebugUiState.Success)
        assertEquals(sampleInfo, (state as RealStarVisibilityDebugUiState.Success).info)
    }

    // Not directly unit-testable: applySnapshot() wraps compute(), the _state
    // publish, and the MobileLog.realStarVisibilityDebug(Failed) calls in one
    // try/catch, so a logging failure is exactly as non-fatal as a compute()
    // failure. MobileLog isn't injectable (LogBus.event is a singleton that
    // no-ops when no writer is installed, as in this JVM test), so a
    // logging-specific throw can't be simulated here; the test above already
    // exercises the same outer catch that would handle it.
}
