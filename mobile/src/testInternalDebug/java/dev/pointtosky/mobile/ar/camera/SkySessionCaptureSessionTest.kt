package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockRelationship
import dev.pointtosky.core.astro.projection.camera.skylog.parseSkySessionLog
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val first = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()

        now = 2_000L
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = null)
        val second = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
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
        session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
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

        val first = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()
        now = 2_000L
        val second = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
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

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, unsupported, fixtures.observer(), SkyCaptureUiState())

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

        val state = session.startRecording(
                requested = null,
                capability = capability,
                observer = fixtures.observer(),
                previous = SkyCaptureUiState(),
            )

        assertEquals(SkyRecordingBlockedReason.AUTO_EXPOSURE_NOT_ALLOWED.name, state.lastFailureReason)
        assertFalse(session.isRecording)
    }

    @Test
    fun `recording is blocked until the intrinsics have resolved`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(publishedResolution = null)))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.INTRINSICS_NOT_RESOLVED.name, state.lastFailureReason)
        assertFalse(session.isRecording)
    }

    @Test
    fun `recording is blocked until the resolved exposure is the one actually bound`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        // Phase one of the two-phase bind: bound with no exposure at all.
        session.onBind(1L, configuration().copy(exposure = null), cameraInfo = null)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())

        assertEquals(SkyRecordingBlockedReason.EXPOSURE_NOT_APPLIED_YET.name, state.lastFailureReason)
    }

    @Test
    fun `recording is blocked while there is no observing context`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val root = newRoot()
        val session = session(root, factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        val gate = session.recordingGate(DEFAULT_SKY_EXPOSURE, capability, observer = null)
        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, observer = null, previous = SkyCaptureUiState())

        assertEquals(
            SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE,
            assertIs<SkyRecordingGate.Blocked>(gate).reason,
        )
        assertEquals(SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE.name, state.lastFailureReason)
        assertFalse(session.isRecording)
        assertEquals(
            emptyList(),
            assertNotNull(root.listFiles()).toList(),
            "a session with no observer must not even create its directory",
        )
    }

    @Test
    fun `the gate opens as soon as an observing context exists`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        assertIs<SkyRecordingGate.Blocked>(session.recordingGate(DEFAULT_SKY_EXPOSURE, capability, observer = null))
        assertIs<SkyRecordingGate.Allowed>(
            session.recordingGate(DEFAULT_SKY_EXPOSURE, capability, observer = fixtures.observer()),
        )
    }

    @Test
    fun `an observer that disappears mid-session does not get persisted as a valid frame`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val root = newRoot()
        val session =
            SkySessionCaptureSession(
                sessionsRoot = root,
                deviceModel = "Test Device",
                scopeFactory = factory,
                nowEpochMillis = { 1_767_225_600_000L },
                timestampSourceProbe = { SkyCameraTimestampSource.REALTIME },
            )
        session.onBind(1L, configuration(), cameraInfo = fakeCameraInfo())
        session.onViewportChanged(
            SkySessionCaptureFixtures.VIEWPORT_WIDTH_PX,
            SkySessionCaptureFixtures.VIEWPORT_HEIGHT_PX,
        )
        val goodTimestamp = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS
        val lostTimestamp = goodTimestamp + 100_000_000L
        listOf(goodTimestamp, lostTimestamp).forEach { timestampNanos ->
            session.onRotationSample(
                TimedRotationSample(timestampNanos = timestampNanos, rotationMatrix = fixtures.rotationMatrix()),
            )
        }

        val started = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        // A frame while the fix is present, so the drop below can only be about the observer: this same
        // pipeline demonstrably records.
        val recorded =
            session.onFrame(
                epoch = 1L,
                configuration = configuration(),
                joined = fixtures.joinedFrame(timestampNanos = goodTimestamp),
                observer = fixtures.observer(),
                stars = fixtures.starDirections(),
                previous = started,
            )
        // Then the fix goes away - permission revoked from Settings, or the provider stops - and the
        // next frame arrives with nothing to project through.
        val dropped =
            session.onFrame(
                epoch = 1L,
                configuration = configuration(),
                joined = fixtures.joinedFrame(timestampNanos = lostTimestamp),
                observer = null,
                stars = fixtures.starDirections(),
                previous = recorded,
            )
        session.stopRecording()

        assertEquals(SkyRecordOutcome.RECORDED, recorded.lastOutcome, "the harness must be able to record at all")
        assertEquals(SkyRecordOutcome.OBSERVER_CONTEXT_UNAVAILABLE, dropped.lastOutcome)
        assertEquals(1L, dropped.recordedFrameCount, "only the frame that had an observer may be recorded")
        assertEquals(1L, dropped.droppedFrameCount)

        val sessionDirectory = File(assertNotNull(started.sessionDirectoryPath))
        val document = parseSkySessionLog(File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME).readText())
        assertEquals(1, document.records.size, "the observer-less frame must not be in the log")
        assertNotNull(document.records.single().observer)
        assertEquals(
            1,
            File(sessionDirectory, SKY_SESSION_FRAMES_DIRECTORY_NAME).listFiles()?.size,
            "nor may its pixels be left on disk",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Clock provenance travels with the generation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a session with no bound CameraInfo cannot claim its clocks are comparable`() {
        // No CameraInfo means SENSOR_INFO_TIMESTAMP_SOURCE was never read, so nothing is proven.
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()

        val header = assertNotNull(readHeaderAt(assertNotNull(state.sessionDirectoryPath)))
        assertEquals(SkyClockRelationship.UNKNOWN, header.clockAlignment.relationship)
        assertNull(
            header.clockAlignment.poseToFrameOffsetNanos,
            "an unprovable session must not write an offset it never measured",
        )
    }

    @Test
    fun `a camera reporting a REALTIME timestamp source writes a proven-comparable header`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val cameraInfo = fakeCameraInfo()
        val session =
            SkySessionCaptureSession(
                sessionsRoot = newRoot(),
                deviceModel = "Test Device",
                scopeFactory = factory,
                nowEpochMillis = { 1_767_225_600_000L },
                timestampSourceProbe = { SkyCameraTimestampSource.REALTIME },
            )
        session.onBind(1L, configuration(), cameraInfo = cameraInfo)

        val state = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()

        val header = assertNotNull(readHeaderAt(assertNotNull(state.sessionDirectoryPath)))
        assertEquals(SkyClockRelationship.SOURCE_PROVEN_COMPARABLE, header.clockAlignment.relationship)
    }

    @Test
    fun `a rebind onto a camera with an unknown timestamp source drops the previous proven claim`() {
        // Switching physical camera can switch timestamp source; carrying the old claim forward would
        // put a proven-comparable header on a session whose camera never made that claim.
        val factory =
            CountingScopeFactory(
                listOf(FakeIntrinsicsSource(fixtures.intrinsics()), FakeIntrinsicsSource(fixtures.intrinsics())),
            )
        val sources = mutableListOf(SkyCameraTimestampSource.REALTIME, SkyCameraTimestampSource.UNKNOWN)
        var now = 1_000L
        val session =
            SkySessionCaptureSession(
                sessionsRoot = newRoot(),
                deviceModel = "Test Device",
                scopeFactory = factory,
                nowEpochMillis = { now },
                timestampSourceProbe = { sources.removeAt(0) },
            )

        session.onBind(1L, configuration(cameraId = "3"), cameraInfo = fakeCameraInfo())
        val first = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()

        now = 2_000L
        session.onBind(2L, configuration(cameraId = "0"), cameraInfo = fakeCameraInfo())
        val second = session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())
        session.stopRecording()

        assertEquals(
            SkyClockRelationship.SOURCE_PROVEN_COMPARABLE,
            assertNotNull(readHeaderAt(assertNotNull(first.sessionDirectoryPath))).clockAlignment.relationship,
        )
        assertEquals(
            SkyClockRelationship.UNKNOWN,
            assertNotNull(readHeaderAt(assertNotNull(second.sessionDirectoryPath))).clockAlignment.relationship,
            "the second generation's header must describe the second generation's camera",
        )
    }

    @Test
    fun `dispose stops recording and clears the generation`() {
        val factory = CountingScopeFactory(listOf(FakeIntrinsicsSource(fixtures.intrinsics())))
        val session = session(newRoot(), factory)
        session.onBind(1L, configuration(), cameraInfo = null)
        session.startRecording(DEFAULT_SKY_EXPOSURE, capability, fixtures.observer(), SkyCaptureUiState())

        session.dispose()

        assertFalse(session.isRecording)
        assertEquals(0L, session.currentEpoch)
    }

    /**
     * A distinct `CameraInfo` identity and nothing more.
     *
     * The session only ever compares it by identity and hands it to the injected timestamp probe, so a
     * proxy is enough — and is the only way to get one at all, `CameraInfo` being a wide CameraX
     * interface with no test double and no constructor.
     */
    private fun fakeCameraInfo(): CameraInfo =
        Proxy.newProxyInstance(CameraInfo::class.java.classLoader, arrayOf(CameraInfo::class.java)) { proxy, method, _ ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                "toString" -> "fakeCameraInfo"
                else -> error("unexpected CameraInfo call: ${method.name}")
            }
        } as CameraInfo

    private fun readHeaderAt(sessionDirectoryPath: String) =
        parseSkySessionLog(File(sessionDirectoryPath, SKY_SESSION_LOG_FILE_NAME).readText()).header
}
