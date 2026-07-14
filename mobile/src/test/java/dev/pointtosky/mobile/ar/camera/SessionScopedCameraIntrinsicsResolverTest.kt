package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException

/**
 * JVM tests for [SessionScopedCameraIntrinsicsResolver] (CAM-1f): once-per-session resolution,
 * fallback-reason retention, dimension validation, and `CancellationException` propagation. Uses a
 * fake [CameraIntrinsicsProvider] and a `java.lang.reflect.Proxy`-backed [CameraInfo] whose methods
 * are never actually invoked, so this needs no CameraX mocking framework and no real camera.
 */
class SessionScopedCameraIntrinsicsResolverTest {
    private fun fakeCameraInfo(): CameraInfo =
        Proxy.newProxyInstance(
            CameraInfo::class.java.classLoader,
            arrayOf(CameraInfo::class.java),
        ) { _, _, _ ->
            error("CameraInfo methods must never be invoked by a fake CameraIntrinsicsProvider")
        } as CameraInfo

    private val calibrated =
        CameraIntrinsics(
            horizontalFovDeg = 60.0,
            verticalFovDeg = 45.0,
            focalLengthMm = 4.25,
            sensorWidthMm = 5.76,
            sensorHeightMm = 4.29,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    private class CountingFakeProvider(
        private val result: () -> CameraIntrinsicsResolution,
    ) : CameraIntrinsicsProvider {
        var callCount: Int = 0
            private set
        var lastImageWidthPx: Int? = null
            private set
        var lastImageHeightPx: Int? = null
            private set

        override fun resolve(
            cameraInfo: CameraInfo,
            imageWidthPx: Int?,
            imageHeightPx: Int?,
        ): CameraIntrinsicsResolution {
            callCount++
            lastImageWidthPx = imageWidthPx
            lastImageHeightPx = imageHeightPx
            return result()
        }
    }

    @Test
    fun `resolveOnce calls the underlying provider exactly once for a session, even across repeated calls`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val cameraInfo = fakeCameraInfo()

        val first = resolver.resolveOnce(cameraInfo, 1920, 1080)
        val second = resolver.resolveOnce(cameraInfo, 1920, 1080)
        val third = resolver.resolveOnce(cameraInfo, 1920, 1080)

        assertEquals(1, provider.callCount)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `resolveOnce passes the exact real dimensions through to the underlying provider`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)

        resolver.resolveOnce(fakeCameraInfo(), imageWidthPx = 1280, imageHeightPx = 720)

        assertEquals(1280, provider.lastImageWidthPx)
        assertEquals(720, provider.lastImageHeightPx)
    }

    @Test
    fun `resolveOnce rejects a zero or negative imageWidthPx`() {
        val resolver = SessionScopedCameraIntrinsicsResolver(CountingFakeProvider { CameraIntrinsicsResolution(calibrated) })

        assertFailsWith<IllegalArgumentException> { resolver.resolveOnce(fakeCameraInfo(), 0, 1080) }
        assertFailsWith<IllegalArgumentException> { resolver.resolveOnce(fakeCameraInfo(), -1920, 1080) }
    }

    @Test
    fun `resolveOnce rejects a zero or negative imageHeightPx`() {
        val resolver = SessionScopedCameraIntrinsicsResolver(CountingFakeProvider { CameraIntrinsicsResolution(calibrated) })

        assertFailsWith<IllegalArgumentException> { resolver.resolveOnce(fakeCameraInfo(), 1920, 0) }
        assertFailsWith<IllegalArgumentException> { resolver.resolveOnce(fakeCameraInfo(), 1920, -1080) }
    }

    @Test
    fun `a resolved result maps to the core Resolved type, preserving the intrinsics`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)

        val result = resolver.resolveOnce(fakeCameraInfo(), 1920, 1080)

        val resolved = assertIs<CoreCameraIntrinsicsResolution.Resolved>(result)
        assertEquals(calibrated, resolved.intrinsics)
    }

    @Test
    fun `a fallback result maps to the core LegacyFallback type and retains the fallback reason`() {
        val fallbackIntrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)
        val provider =
            CountingFakeProvider {
                CameraIntrinsicsResolution(fallbackIntrinsics, fallbackReason = "no_valid_focal_length")
            }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)

        val result = resolver.resolveOnce(fakeCameraInfo(), 1920, 1080)

        val legacyFallback = assertIs<CoreCameraIntrinsicsResolution.LegacyFallback>(result)
        assertEquals(fallbackIntrinsics, legacyFallback.intrinsics)
        assertEquals("no_valid_focal_length", legacyFallback.reason)
    }

    @Test
    fun `CancellationException from the underlying provider propagates instead of being swallowed`() {
        val provider =
            object : CameraIntrinsicsProvider {
                override fun resolve(
                    cameraInfo: CameraInfo,
                    imageWidthPx: Int?,
                    imageHeightPx: Int?,
                ): CameraIntrinsicsResolution = throw CancellationException("cancelled")
            }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)

        assertFailsWith<CancellationException> { resolver.resolveOnce(fakeCameraInfo(), 1920, 1080) }
    }

    @Test
    fun `camera-session re-entry - a fresh resolver instance - always attempts a fresh resolution`() {
        // A single shared provider stands in for the underlying Camera2 lookup; two distinct
        // resolver instances stand in for two AR sessions (e.g. leaving and re-entering AR).
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val cameraInfo = fakeCameraInfo()

        val resolverA = SessionScopedCameraIntrinsicsResolver(provider)
        resolverA.resolveOnce(cameraInfo, 1920, 1080)
        resolverA.resolveOnce(cameraInfo, 1920, 1080) // cached within resolverA's own session - no extra call

        val resolverB = SessionScopedCameraIntrinsicsResolver(provider) // a new session's own owner
        resolverB.resolveOnce(cameraInfo, 1920, 1080)

        // One fresh resolution per resolver instance (session), not one total, and not one per call.
        assertEquals(2, provider.callCount)
    }
}
