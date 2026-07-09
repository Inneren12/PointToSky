package dev.pointtosky.mobile.catalog

import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugInfo
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugSnapshot
import org.junit.Assert.assertEquals
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
    fun `unexpected exception is caught and published as Failure state`() {
        RealStarVisibilityDebugProvider.applySnapshot {
            throw IllegalStateException("kaboom")
        }

        val state = RealStarVisibilityDebugProvider.state.value
        assertTrue(state is RealStarVisibilityDebugUiState.Failure)
        val message = (state as RealStarVisibilityDebugUiState.Failure).message
        assertTrue(message.contains("kaboom"))
        assertTrue(message.contains("IllegalStateException"))
    }
}
