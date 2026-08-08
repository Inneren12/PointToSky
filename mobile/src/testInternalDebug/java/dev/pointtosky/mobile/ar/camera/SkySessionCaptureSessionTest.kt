package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.skylog.parseSkySessionLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): generation isolation across a rebind.
 *
 * `SessionScopedCameraIntrinsicsResolver.resolveOnce` caches its first answer for the life of the
 * instance, so a screen that can rebind — different physical camera, different analysis resolution,
 * different exposure — must give each bind its own instance or project the second bind's frames
 * through the first bind's intrinsics. Worse, a header could pair the *new* camera id with the *old*
 * camera's calibration, and both halves would look plausible.
 *
 * These tests exercise the session directly with a fake [SkyIntrinsicsSource] per generation, which is
 * what makes the invariant checkable without a device.
 */
class SkySessionCaptureSessionTest {
    private val fixtures = SkySessionCaptureFixtures
    private val temporaryRoots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { it.deleteRecursively() }
    }

    private fun newRoot(): File =
        Files.createTempDirectory("sky_session_capture_test").toFile().also {
            temporaryRoots +=
                it
        }

    /** A [SkyIntrinsicsSource] that already has an answer, so no live `CameraInfo` is needed. */
    private class FakeIntrinsicsSource(
        override val publishedResolution: CameraIntrinsicsResolution?,
    ) : SkyIntrinsicsSource {
        override val calibrationDiagnostics: CameraCalibrationDiagnostics? = null

        override fun resolve(
            cameraInfo: CameraInfo,
            imageWidthPx: Int,
            imageHeightPx: Int,
            sensorToBufferTransform: SensorToBufferMatrix3?,
        ): CameraIntrinsicsResolution = checkNotNull(publishedResolution)
    }

    private class CountingScopeFactory(
        private val intrinsics: List<SkyIntrinsicsSource>,
    ) : () -> SkyCaptureScope {
        val created = mutableListOf<SkyCaptureScope>()

        override fun invoke(): SkyCaptureScope {
            val synchronizer = CameraTimestampSynchronizer()
            val scope =
                SkyCaptureScope(
                    synchronizer = synchronizer,
                    geometryProvider =
                        CameraSessionGeometryProvider(
                            maxAllowedPairDeltaNanos = synchronizer.maxAllowedDeltaNanos,
                        ),
                    intrinsics = intrinsics[created.size.coerceAtMost(intrinsics.lastIndex)],
                )
            created += scope
            return scope
        }
    }

    private val exposure =
        SkyResolvedExposure(exposureTimeNanos = 500_000_000L, sensitivityIso = 1600, frameDurationNanos = 500_000_000L)

    private fun configuration(
        cameraId: String = "3",
        widthPx: Int = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
        heightPx: Int = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
    ) = SkyCaptureConfiguration(
        physicalCameraId = cameraId,
        resolution = AnalysisResolutionRequest(widthPx, heightPx, AnalysisResolutionFamily.NEAR_4_3),
        exposure = exposure,
    )

    private val capability =
        SkyManualExposureCapability(
            supported = true,
            exposureTimeRangeNanos = 100_000L..4_000_000_000L,
            sensitivityRange = 50..12_800,
            maxFrameDurationNanos = 4_000_000_000L,
        )

    private fun session(
        root: File,
        factory: () -> SkyCaptureScope,
        nowEpochMillis: () -> Long = { 1_767_225_600_000L },
    ) = SkySessionCaptureSession(
        sessionsRoot = root,
        deviceModel = "Test Device",
        scopeFactory = factory,
        nowEpochMillis = nowEpochMillis,
    )

    // -----------------------------------------------------------------------------------------
    // Generation rotation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `each new bind epoch builds a completely new scope`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)

        assertTrue(session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null))
        assertTrue(session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null))

        assertEquals(2, factory.created.size, "a rebind must not reuse the previous bind's resolver")
        assertNotEquals(factory.created[0], factory.created[1])
        assertEquals(2L, session.currentEpoch)
    }

    @Test
    fun `contact from the live epoch does not rebuild the scope`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)

        session.onBind(1L, configuration(), cameraInfo = null)
        session.onBind(1L, configuration(), cameraInfo = null)
        session.onBind(1L, configuration(), cameraInfo = null)

        assertEquals(1, factory.created.size)
    }

    @Test
    fun `a stale bind callback is rejected and does not roll the generation back`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null)
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)

        assertFalse(session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null))

        assertEquals(2L, session.currentEpoch)
        assertEquals(2, factory.created.size, "a stale callback must not build a scope")
    }

    @Test
    fun `a frame from a stale generation is counted and discarded`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null)
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)

        val state =
            session.onFrame(
                epoch = 1L,
                configuration = configuration(cameraId = "3"),
                joined = fixtures.joinedFrame(),
                observer = fixtures.observer(),
                stars = emptyList(),
                previous = SkyCaptureUiState(),
            )

        assertEquals(1L, state.staleFrameCount)
        assertEquals(0L, state.analyzedFrameCount, "a stale frame must never reach the live scope")
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `a frame that is the first contact from a new bind rotates the scope itself`() {
        // onBind is delivered on the main thread while the analyzer is already running, so a frame can
        // legitimately arrive first. It must rotate rather than resolve into the previous generation.
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null)

        val state =
            session.onFrame(
                epoch = 2L,
                configuration = configuration(cameraId = "0"),
                joined = fixtures.joinedFrame(),
                observer = fixtures.observer(),
                stars = emptyList(),
                previous = SkyCaptureUiState(),
            )

        assertEquals(2, factory.created.size)
        assertEquals(2L, session.currentEpoch)
        assertEquals(1L, state.analyzedFrameCount)
        assertEquals(0L, state.staleFrameCount)
    }

    @Test
    fun `join drops from a stale generation are ignored`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)

        val state =
            session.onJoinDrops(
                epoch = 1L,
                drops = listOf(SkyJoinDrop(1L, SkyJoinDropReason.FRAME_TIMED_OUT)),
                previous = SkyCaptureUiState(),
            )

        assertEquals(0L, state.joinDropCount)
    }

    // -----------------------------------------------------------------------------------------
    // The header can never mix generations
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a session header pairs the live generation's camera id with that generation's intrinsics`() {
        val firstIntrinsics = fixtures.intrinsics()
        val secondIntrinsics =
            CameraIntrinsicsResolution.Resolved(
                firstIntrinsics.intrinsics.copy(horizontalFovDeg = 41.0, verticalFovDeg = 31.0),
            )
        val factory =
            CountingScopeFactory(listOf(FakeIntrinsicsSource(firstIntrinsics), FakeIntrinsicsSource(secondIntrinsics)))
        val root = newRoot()
        var now = 1_000L
        val session = session(root, factory) { now }

        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null)
        val first = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())
        session.stopRecording()

        now = 2_000L
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)
        val second = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())
        session.stopRecording()

        val firstHeader = assertNotNull(readHeaderAt(assertNotNull(first.sessionDirectoryPath)))
        val secondHeader = assertNotNull(readHeaderAt(assertNotNull(second.sessionDirectoryPath)))

        assertEquals("3", firstHeader.cameraId)
        assertEquals(66.0, firstHeader.intrinsics.horizontalFovDeg)
        assertEquals("0", secondHeader.cameraId)
        assertEquals(
            41.0,
            secondHeader.intrinsics.horizontalFovDeg,
            "the second header must never carry the first generation's intrinsics",
        )
    }

    @Test
    fun `rebinding while recording stops the session rather than continuing it under new intrinsics`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = null)
        session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())
        assertTrue(session.isRecording)

        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)

        assertFalse(session.isRecording, "a rebind invalidates the header the running session already wrote")
    }

    // -----------------------------------------------------------------------------------------
    // Recording lifecycle
    // -----------------------------------------------------------------------------------------

    @Test
    fun `start after stop creates a new session directory`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val root = newRoot()
        var now = 1_000L
        val session = session(root, factory) { now }
        session.onBind(1L, configuration(), cameraInfo = null)

        val first = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())
        session.stopRecording()
        now = 2_000L
        val second = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())
        session.stopRecording()

        assertNotEquals(first.sessionDirectoryPath, second.sessionDirectoryPath)
        assertNotNull(readHeaderAt(assertNotNull(first.sessionDirectoryPath)))
        assertNotNull(readHeaderAt(assertNotNull(second.sessionDirectoryPath)))
        assertEquals(2, assertNotNull(root.listFiles()).size)
    }

    @Test
    fun `recording is blocked when manual exposure is unsupported`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val root = newRoot()
        val session = session(root, factory)
        session.onBind(1L, configuration(), cameraInfo = null)
        val unsupported =
            SkyManualExposureCapability(
                supported = false,
                unsupportedReason = SkyManualExposureUnsupportedReason.MANUAL_SENSOR_CAPABILITY_ABSENT,
            )

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, unsupported, SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.MANUAL_SENSOR_CAPABILITY_ABSENT.name, state.lastFailureReason)
        assertNull(state.sessionDirectoryPath)
        assertFalse(session.isRecording)
        assertEquals(
            emptyList(),
            assertNotNull(root.listFiles()).toList(),
            "a blocked start must not create a directory",
        )
    }

    @Test
    fun `recording is blocked in auto-exposure mode`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        val state = session.startRecording(requested = null, capability = capability, previous = SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.AUTO_EXPOSURE_NOT_ALLOWED.name, state.lastFailureReason)
        assertFalse(session.isRecording)
    }

    @Test
    fun `recording is blocked until the intrinsics have resolved`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(publishedResolution = null)))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.INTRINSICS_NOT_RESOLVED.name, state.lastFailureReason)
        assertFalse(session.isRecording)
    }

    @Test
    fun `recording is blocked until the resolved exposure is the one actually bound`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        // Phase one of the two-phase bind: bound with no exposure at all.
        session.onBind(1L, configuration().copy(exposure = null), cameraInfo = null)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.EXPOSURE_NOT_APPLIED_YET.name, state.lastFailureReason)
    }

    @Test
    fun `dispose stops recording and clears the generation`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)
        session.startRecording(DEFAULT_SKY_EXPOSURE, capability, SkyCaptureUiState())

        session.dispose()

        assertFalse(session.isRecording)
        assertEquals(0L, session.currentEpoch)
    }

    private fun readHeaderAt(sessionDirectoryPath: String) =
        parseSkySessionLog(File(sessionDirectoryPath, SKY_SESSION_LOG_FILE_NAME).readText()).header
}
