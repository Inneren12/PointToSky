package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.mobile.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-1g debug-visibility gate (§2): the diagnostic overlay must be enabled only
 * for the internal-distribution debug build (`internalDebug`), never for a release build and never
 * for the public-distribution flavor.
 *
 * This class lives in the shared `src/test` source set, so it runs as both
 * `testInternalDebugUnitTest` and `testPublicDebugUnitTest` — `BuildConfig.FLAVOR` is `"internal"`
 * in the first and `"public"` in the second. The gate-object assertion below is therefore
 * variant-aware (computed from the same `BuildConfig` fields the gate itself reads) rather than
 * hardcoding an expectation that only holds for one of the two variants this class actually runs
 * under.
 */
class CameraGeometryDiagnosticsGateTest {
    @Test
    fun `debug internal build enables diagnostics`() {
        assertTrue(isDiagnosticsEnabled(debug = true, flavor = "internal"))
    }

    @Test
    fun `debug public build does not enable diagnostics`() {
        assertFalse(isDiagnosticsEnabled(debug = true, flavor = "public"))
    }

    @Test
    fun `release internal build does not enable diagnostics`() {
        assertFalse(isDiagnosticsEnabled(debug = false, flavor = "internal"))
    }

    @Test
    fun `release public build does not enable diagnostics`() {
        assertFalse(isDiagnosticsEnabled(debug = false, flavor = "public"))
    }

    @Test
    fun `the gate object reflects the active BuildConfig variant`() {
        val expected =
            isDiagnosticsEnabled(
                debug = BuildConfig.DEBUG,
                flavor = BuildConfig.FLAVOR,
            )

        assertEquals(expected, CameraGeometryDiagnosticsGate.isEnabled)
    }
}
