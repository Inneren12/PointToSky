package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for [CameraSessionIntrinsicsCoordinator] (CAM-1f; CAM-2c fix §4): order-independent
 * CameraInfo/first-frame coordination, the coherent-input gate (a usable sensor-to-buffer transform
 * from the SAME frame as the dimensions used), once-per-session resolution, fallback aspect-ratio
 * correctness, and disposal races. Uses a real [SessionScopedCameraIntrinsicsResolver] backed by a
 * fake [CameraIntrinsicsProvider], and a `java.lang.reflect.Proxy`-backed [CameraInfo] whose
 * methods are never actually invoked — no CameraX mocking framework, no real camera.
 */
class CameraSessionIntrinsicsCoordinatorTest {
    private fun fakeCameraInfo(): CameraInfo =
        Proxy.newProxyInstance(
            CameraInfo::class.java.classLoader,
            arrayOf(CameraInfo::class.java),
        ) { _, _, _ ->
            error("CameraInfo methods must never be invoked by a fake CameraIntrinsicsProvider")
        } as CameraInfo

    /** A plain axis-aligned, always-usable sensor-to-buffer matrix - the default for most tests below. */
    private fun usableTransform() = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** A single-axis mirror - classifies as MIRRORED, one of the four *unsupported* classes. */
    private fun unsupportedTransform() = SensorToBufferMatrix3(-1.0, 0.0, 100.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun frame(
        widthPx: Int,
        heightPx: Int,
        timestampNanos: Long = 1_000L,
        sensorToBufferTransform: SensorToBufferMatrix3? = usableTransform(),
    ) = CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = widthPx,
        bufferHeightPx = heightPx,
        rotationDegrees = 0,
        sensorToBufferTransform = sensorToBufferTransform,
    )

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
        var lastSensorToBufferTransform: SensorToBufferMatrix3? = null
            private set

        override fun resolve(
            cameraInfo: CameraInfo,
            imageWidthPx: Int?,
            imageHeightPx: Int?,
            sensorToBufferTransform: SensorToBufferMatrix3?,
        ): CameraIntrinsicsResolution {
            callCount++
            lastImageWidthPx = imageWidthPx
            lastImageHeightPx = imageHeightPx
            lastSensorToBufferTransform = sensorToBufferTransform
            return result()
        }
    }

    private val fallbackProvider =
        object : CameraIntrinsicsProvider {
            override fun resolve(
                cameraInfo: CameraInfo,
                imageWidthPx: Int?,
                imageHeightPx: Int?,
                sensorToBufferTransform: SensorToBufferMatrix3?,
            ): CameraIntrinsicsResolution =
                CameraIntrinsicsResolution(
                    legacyFallbackCameraIntrinsics(imageWidthPx = imageWidthPx, imageHeightPx = imageHeightPx),
                    fallbackReason = "no_valid_focal_length",
                )
        }

    // --- Callback ordering ------------------------------------------------------------------

    @Test
    fun `CameraInfo first, then frame - no resolution after CameraInfo alone, one resolution with the frame's dimensions`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        assertEquals(0, provider.callCount)
        assertNull(published)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_FRAME, coordinator.state)

        coordinator.onFrameMetadata(frame(1920, 1080))

        assertEquals(1, provider.callCount)
        assertEquals(1920, provider.lastImageWidthPx)
        assertEquals(1080, provider.lastImageHeightPx)
        assertIs<CoreCameraIntrinsicsResolution.Resolved>(published)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.RESOLVED, coordinator.state)
    }

    @Test
    fun `frame first, then CameraInfo - no resolution after the frame alone, one resolution with that frame's dimensions`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onFrameMetadata(frame(1280, 720))
        assertEquals(0, provider.callCount)
        assertNull(published)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_CAMERA_INFO, coordinator.state)

        coordinator.onCameraInfo(fakeCameraInfo())

        assertEquals(1, provider.callCount)
        assertEquals(1280, provider.lastImageWidthPx)
        assertEquals(720, provider.lastImageHeightPx)
        assertIs<CoreCameraIntrinsicsResolution.Resolved>(published)
    }

    @Test
    fun `repeated frames before CameraInfo - provider invoked once, using the first accepted frame's dimensions`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onFrameMetadata(frame(1920, 1080))
        coordinator.onFrameMetadata(frame(1280, 720)) // must not override the first usable frame
        coordinator.onFrameMetadata(frame(640, 480))
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(100, 100)) // after resolution already claimed - must not re-trigger

        assertEquals(1, provider.callCount)
        assertEquals(1920, provider.lastImageWidthPx)
        assertEquals(1080, provider.lastImageHeightPx)
    }

    @Test
    fun `repeated CameraInfo callbacks - provider invoked once only`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))
        coordinator.onCameraInfo(fakeCameraInfo()) // after resolution already claimed

        assertEquals(1, provider.callCount)
    }

    // --- Preview-only semantics --------------------------------------------------------------

    @Test
    fun `CameraInfo without any frame metadata never triggers resolution`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onCameraInfo(fakeCameraInfo())

        assertEquals(0, provider.callCount)
        assertNull(published)
    }

    // --- Resolved characteristics path -------------------------------------------------------

    @Test
    fun `a resolved CAMERA_CHARACTERISTICS result is unaffected by which frame dimensions triggered it`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(640, 480))

        assertEquals(1, provider.callCount)
        val resolved = assertIs<CoreCameraIntrinsicsResolution.Resolved>(published)
        assertEquals(calibrated, resolved.intrinsics)
    }

    // --- Fallback aspect correctness ---------------------------------------------------------

    @Test
    fun `fallback intrinsics reflect the real analyzed frame's 16x9 aspect ratio, not a square default`() {
        val resolver = SessionScopedCameraIntrinsicsResolver(fallbackProvider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))

        val fallback = assertIs<CoreCameraIntrinsicsResolution.LegacyFallback>(published)
        val expected16x9 = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)
        val squareDefault = legacyFallbackCameraIntrinsics(imageWidthPx = null, imageHeightPx = null)

        assertEquals(expected16x9.horizontalFovDeg, fallback.intrinsics.horizontalFovDeg, 1e-9)
        assertTrue(
            kotlin.math.abs(fallback.intrinsics.horizontalFovDeg - squareDefault.horizontalFovDeg) > 1e-6,
            "16:9-derived horizontal FOV must differ from the square/default fallback",
        )
    }

    @Test
    fun `the fallback resolution published to CameraSessionGeometryProvider reflects the real frame dimensions`() {
        val resolver = SessionScopedCameraIntrinsicsResolver(fallbackProvider)
        val geometryProvider = CameraSessionGeometryProvider()
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver, onResolved = geometryProvider::onIntrinsicsResolved)
        val f = frame(1920, 1080)

        geometryProvider.onViewportChanged(1080, 1920)
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(f)

        val sample = TimedRotationSample(timestampNanos = f.timestampNanos, rotationMatrix = FloatArray(9))
        val pairing =
            assertIs<FrameRotationPairingResult.Paired>(
                pairFrameToNearestRotation(
                    frame = f,
                    samples = listOf(sample),
                    maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                    clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
                ),
            )
        geometryProvider.onPairedFrame(f, pairing)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(geometryProvider.state.value)
        val expected16x9 = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)
        assertEquals(expected16x9.horizontalFovDeg, ready.geometry.intrinsics.intrinsics.horizontalFovDeg, 1e-9)
    }

    // --- CAM-2c fix §4: the coherent-input gate ------------------------------------------------

    @Test
    fun `a null transform on the first frame does not resolve - a usable transform on the second frame does, using that frame's own dimensions`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = null))
        assertEquals(0, provider.callCount)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_USABLE_SENSOR_TO_BUFFER_TRANSFORM, coordinator.state)

        coordinator.onFrameMetadata(frame(1280, 720, sensorToBufferTransform = usableTransform()))

        assertEquals(1, provider.callCount)
        assertEquals(1280, provider.lastImageWidthPx)
        assertEquals(720, provider.lastImageHeightPx)
        assertEquals(usableTransform(), provider.lastSensorToBufferTransform)
    }

    @Test
    fun `an unsupported-class transform on the first frame is skipped - a supported transform on the second frame is used`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = unsupportedTransform()))
        assertEquals(0, provider.callCount)

        coordinator.onFrameMetadata(frame(1280, 720, sensorToBufferTransform = usableTransform()))

        assertEquals(1, provider.callCount)
        assertEquals(1280, provider.lastImageWidthPx)
        assertEquals(720, provider.lastImageHeightPx)
    }

    @Test
    fun `no usable transform before the bound falls back to publishing with a null transform, using the most recent frame's dimensions`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver, maxFramesWaitingForUsableTransform = 2) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = null))
        assertEquals(0, provider.callCount)

        coordinator.onFrameMetadata(frame(1280, 720, sensorToBufferTransform = unsupportedTransform()))

        // The bound (2 frames) is reached with no usable transform ever found - resolves with the
        // most recent frame's dimensions and a null transform, exactly like the pre-CAM-2c CAM-1b path.
        assertEquals(1, provider.callCount)
        assertEquals(1280, provider.lastImageWidthPx)
        assertEquals(720, provider.lastImageHeightPx)
        assertNull(provider.lastSensorToBufferTransform)
    }

    @Test
    fun `dispose while waiting for a usable transform prevents any later publication`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver, maxFramesWaitingForUsableTransform = 10) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = null))
        coordinator.onFrameMetadata(frame(1280, 720, sensorToBufferTransform = unsupportedTransform()))
        assertEquals(0, provider.callCount)

        coordinator.dispose()
        // A later, usable frame arriving after dispose() must never resolve or publish.
        coordinator.onFrameMetadata(frame(640, 480, sensorToBufferTransform = usableTransform()))

        assertEquals(0, provider.callCount)
        assertNull(published)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.DISPOSED, coordinator.state)
    }

    // --- Observable state -----------------------------------------------------------------------

    @Test
    fun `state reflects WAITING_FOR_CAMERA_INFO before anything arrives`() {
        val resolver = SessionScopedCameraIntrinsicsResolver(CountingFakeProvider { CameraIntrinsicsResolution(calibrated) })
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}
        assertEquals(CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_CAMERA_INFO, coordinator.state)
    }

    // --- Disposal / races ----------------------------------------------------------------------

    @Test
    fun `dispose before either input - callbacks are no-ops`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.dispose()
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))

        assertEquals(0, provider.callCount)
        assertNull(published)
    }

    @Test
    fun `dispose after CameraInfo but before the frame - the later frame callback is a no-op`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.dispose()
        coordinator.onFrameMetadata(frame(1920, 1080))

        assertEquals(0, provider.callCount)
        assertNull(published)
    }

    @Test
    fun `dispose after the frame but before CameraInfo - the later CameraInfo callback is a no-op`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onFrameMetadata(frame(1920, 1080))
        coordinator.dispose()
        coordinator.onCameraInfo(fakeCameraInfo())

        assertEquals(0, provider.callCount)
        assertNull(published)
    }

    @Test
    fun `callbacks after a full resolution and disposal remain no-ops`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        var published: CoreCameraIntrinsicsResolution? = null
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))
        assertEquals(1, provider.callCount)

        coordinator.dispose()
        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(640, 480))

        assertEquals(1, provider.callCount) // unchanged
    }

    @Test
    fun `dispose is idempotent`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.dispose()
        coordinator.dispose()
        coordinator.dispose()

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))
        assertEquals(0, provider.callCount)
    }

    @Test
    fun `resolution racing disposal cannot publish intrinsics after disposal completes`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val resolveEntered = CountDownLatch(1)
            val allowResolveToFinish = CountDownLatch(1)
            val provider =
                object : CameraIntrinsicsProvider {
                    override fun resolve(
                        cameraInfo: CameraInfo,
                        imageWidthPx: Int?,
                        imageHeightPx: Int?,
                        sensorToBufferTransform: SensorToBufferMatrix3?,
                    ): CameraIntrinsicsResolution {
                        resolveEntered.countDown()
                        allowResolveToFinish.await(5, TimeUnit.SECONDS)
                        return CameraIntrinsicsResolution(calibrated)
                    }
                }
            val resolver = SessionScopedCameraIntrinsicsResolver(provider)
            var published: CoreCameraIntrinsicsResolution? = null
            val coordinator = CameraSessionIntrinsicsCoordinator(resolver) { published = it }

            coordinator.onCameraInfo(fakeCameraInfo())
            val frameFuture = executor.submit { coordinator.onFrameMetadata(frame(1920, 1080)) }

            // Resolution is now blocked inside provider.resolve(). Dispose completes fully before
            // resolution is allowed to finish and attempt to publish.
            assertTrue(resolveEntered.await(5, TimeUnit.SECONDS), "resolution did not start in time")
            coordinator.dispose()
            allowResolveToFinish.countDown()

            frameFuture.get(5, TimeUnit.SECONDS)

            assertNull(published, "a resolution that completes after disposal must never publish")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `dispose blocks until an in-progress onResolved publication finishes, and never begins one afterward`() {
        // Targets the window the barrier-based test above cannot: resolveOnce has already
        // returned and publication is in progress (holding the coordinator lock) when dispose()
        // is called concurrently. The only legal outcomes are "publication finishes, then
        // dispose() returns" (proven here) or "dispose() returns and publication never begins"
        // (proven by the preceding test) - never "dispose() returns, then publication begins".
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)

        val onResolvedEntered = CountDownLatch(1)
        val releaseOnResolved = CountDownLatch(1)
        val onResolvedCompleted = CountDownLatch(1)
        val coordinator =
            CameraSessionIntrinsicsCoordinator(resolver) {
                onResolvedEntered.countDown()
                releaseOnResolved.await(5, TimeUnit.SECONDS)
                onResolvedCompleted.countDown()
            }

        val executor = Executors.newFixedThreadPool(2)
        try {
            coordinator.onCameraInfo(fakeCameraInfo())
            val frameFuture = executor.submit { coordinator.onFrameMetadata(frame(1920, 1080)) }

            assertTrue(onResolvedEntered.await(5, TimeUnit.SECONDS), "onResolved was not entered in time")

            // onResolved is now blocked while holding the coordinator lock. dispose() must not be
            // able to acquire that lock - and therefore must not return - until onResolved finishes.
            val disposeFuture = executor.submit { coordinator.dispose() }
            assertFailsWith<TimeoutException>("dispose() must not return while onResolved is still in progress") {
                disposeFuture.get(200, TimeUnit.MILLISECONDS)
            }

            releaseOnResolved.countDown()

            assertTrue(onResolvedCompleted.await(5, TimeUnit.SECONDS), "onResolved never completed")
            disposeFuture.get(5, TimeUnit.SECONDS) // now unblocked, must complete promptly
            frameFuture.get(5, TimeUnit.SECONDS)

            // Once dispose() has returned, no callback may begin a new publication.
            coordinator.onCameraInfo(fakeCameraInfo())
            coordinator.onFrameMetadata(frame(1280, 720))
            assertEquals(1, provider.callCount)
        } finally {
            executor.shutdown()
        }
    }

    // --- CAM-2c runtime integration fix §2/§3: diagnosticState and frame counters ---------------

    @Test
    fun `frameCounters tracks analyzed, transform-present, transform-null and usable-transform frames, even after resolution`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = null))
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = unsupportedTransform()))
        coordinator.onFrameMetadata(frame(1920, 1080, sensorToBufferTransform = usableTransform()))
        // Resolution has now been claimed by the usable-transform frame above - a further frame must
        // still advance the running counters (unlike coordinatorFramesWaited, which freezes).
        coordinator.onFrameMetadata(frame(1280, 720, sensorToBufferTransform = usableTransform()))

        val counters = coordinator.frameCounters
        assertEquals(4L, counters.framesAnalyzed)
        assertEquals(3L, counters.framesWithTransform)
        assertEquals(1L, counters.framesWithNullTransform)
        assertEquals(2L, counters.framesWithUsableTransform)
        assertEquals(3, counters.coordinatorFramesWaited) // frozen at the frame that claimed resolution
        assertEquals(usableTransform(), counters.latestFrameTransform)
        assertEquals(SensorToBufferTransformClass.AXIS_ALIGNED_0, counters.latestFrameTransformClass)
    }

    @Test
    fun `frameCounters stop advancing after dispose`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))
        coordinator.dispose()
        coordinator.onFrameMetadata(frame(1280, 720))

        assertEquals(1L, coordinator.frameCounters.framesAnalyzed)
    }

    @Test
    fun `diagnosticState preserves the CAM-2c attempt reason even when a CAM-1b fallback is published`() {
        val attempt =
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                cameraId = "0",
                physicalCameraIdsForDiagnostics = setOf("1", "2"),
            )
        val snapshot = CameraCharacteristicsSnapshot(availableFocalLengthsMm = floatArrayOf(3.6f), sensorPhysicalWidthMm = 6.4f, sensorPhysicalHeightMm = 4.8f, cameraId = "0")
        val provider =
            object : CameraIntrinsicsProvider {
                override fun resolve(
                    cameraInfo: CameraInfo,
                    imageWidthPx: Int?,
                    imageHeightPx: Int?,
                    sensorToBufferTransform: SensorToBufferMatrix3?,
                ): CameraIntrinsicsResolution =
                    CameraIntrinsicsResolution(
                        calibrated,
                        analysisBufferAttempt = attempt,
                        cameraCharacteristicsSnapshot = snapshot,
                    )
            }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        coordinator.onCameraInfo(fakeCameraInfo())
        coordinator.onFrameMetadata(frame(1920, 1080))

        val diagnosticState = coordinator.diagnosticState
        assertEquals(attempt, diagnosticState.analysisBufferAttempt)
        assertEquals(snapshot, diagnosticState.cameraCharacteristicsSnapshot)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.RESOLVED, diagnosticState.coordinatorState)
        val published = assertIs<CoreCameraIntrinsicsResolution.Resolved>(diagnosticState.publishedIntrinsicsResolution)
        assertEquals(calibrated, published.intrinsics)
    }

    @Test
    fun `diagnosticState reflects an unresolved coordinator with a null attempt and no published resolution`() {
        val provider = CountingFakeProvider { CameraIntrinsicsResolution(calibrated) }
        val resolver = SessionScopedCameraIntrinsicsResolver(provider)
        val coordinator = CameraSessionIntrinsicsCoordinator(resolver) {}

        val diagnosticState = coordinator.diagnosticState

        assertNull(diagnosticState.analysisBufferAttempt)
        assertNull(diagnosticState.publishedIntrinsicsResolution)
        assertEquals(CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_CAMERA_INFO, diagnosticState.coordinatorState)
    }
}
