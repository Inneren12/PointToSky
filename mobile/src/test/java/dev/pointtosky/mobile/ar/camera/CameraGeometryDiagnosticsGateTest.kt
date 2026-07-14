package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-1g debug-visibility gate (§2): the diagnostic overlay must be enabled only
 * for the internal-distribution debug build (`internalDebug`), never for a release build and never
 * for the public-distribution flavor.
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
    fun `the gate object reflects the internalDebug BuildConfig this unit test variant runs as`() {
        assertTrue(CameraGeometryDiagnosticsGate.isEnabled)
    }
}
