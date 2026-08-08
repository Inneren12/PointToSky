package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockAlignment
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockRelationship
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplaySkipReason
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.parseSkySessionLog
import dev.pointtosky.core.astro.projection.camera.skylog.replaySkySessionLog
import dev.pointtosky.core.astro.projection.camera.skylog.toStarProjectionContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): the whole on-device capture path below the CameraX bind, exercised on
 * a plain JVM against a temporary directory — analyzed frame in, session directory out, parsed and
 * replayed back to the numbers the projection math produces.
 *
 * No camera, no sensor, no Android framework class is involved, which is the point: the capture
 * mechanism has to be verifiable without the hardware it is built to point at the sky.
 */
class SkySessionRecorderTest {
    private val fixtures = SkySessionCaptureFixtures
    private val temporaryDirectories = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaryDirectories.forEach { it.deleteRecursively() }
    }

    private fun newSessionDirectory(): File =
        Files.createTempDirectory("sky_session_test").toFile().also { temporaryDirectories += it }

    private fun header(
        sessionId: String = "sky_test",
        clockAlignment: SkyClockAlignment = skyClockAlignmentFor(SkyCameraTimestampSource.REALTIME),
    ): SkySessionLogHeader =
        buildSkySessionHeader(
            sessionId = sessionId,
            startedAtEpochMillis = 1_767_225_600_000L,
            bufferWidthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            bufferHeightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
            intrinsics = fixtures.intrinsics(),
            clockAlignment = clockAlignment,
            maxPairDeltaNanos = 25_000_000L,
            clockMismatchThresholdNanos = 5_000_000_000L,
            deviceModel = "Test Device",
            cameraId = "3",
            physicalCameraIds = listOf("2", "3"),
            calibration = null,
            pinhole = null,
            notes = "unit test",
        )

    private fun recorderIn(directory: File): SkySessionRecorder =
        SkySessionRecorder(SkySessionLogWriter(File(directory, "session")))

    /** The matched exposure for a frame at [timestampNanos], as the join would have produced it. */
    private fun exposureFor(timestampNanos: Long = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS) =
        fixtures.exposureSample(sensorTimestampNanos = timestampNanos)

    // -----------------------------------------------------------------------------------------

    @Test
    fun `a recorded session writes a header, a frame line and a luma file per frame`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        assertTrue(recorder.start(header()))

        repeat(3) { index ->
            val timestampNanos = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + index * 33_000_000L
            val outcome =
                recorder.record(
                    frame = fixtures.analyzedFrame(timestampNanos = timestampNanos, seed = index),
                    exposure = fixtures.exposureSample(sensorTimestampNanos = timestampNanos),
                    geometry = fixtures.geometry(timestampNanos = timestampNanos, poseTimestampNanos = timestampNanos),
                    capturedAtEpochMillis = 1_767_225_600_000L + index,
                    observer = fixtures.observer(),
                    stars = emptyList(),
                    prediction = StarPredictionBatchResult.Ready.of(emptyList()),
                )
            assertEquals(SkyRecordOutcome.RECORDED, outcome)
        }
        recorder.stop()

        val sessionDirectory = File(directory, "session")
        val document = parseSkySessionLog(File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME).readText())

        assertEquals("sky_test", assertNotNull(document.header).sessionId)
        assertEquals(3, document.records.size)
        assertEquals(emptyList(), document.unreadable)
        assertEquals(listOf(0L, 1L, 2L), document.records.map { it.sequence })
        assertEquals(3, File(sessionDirectory, SKY_SESSION_FRAMES_DIRECTORY_NAME).listFiles()?.size)
    }

    @Test
    fun `each luma file on disk is exactly the length and content its log line claims`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())
        val frame = fixtures.analyzedFrame(seed = 7)
        recorder.record(
            frame = frame,
            exposure = fixtures.exposureSample(),
            geometry = fixtures.geometry(),
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val sessionDirectory = File(directory, "session")
        val record = parseSkySessionLog(File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME).readText()).records.single()
        val lumaFile = File(sessionDirectory, record.luma.path)

        assertTrue(lumaFile.isFile, "the log line must reference a file that exists")
        assertEquals(record.luma.byteLength, lumaFile.length())
        assertEquals(SkyLumaFormat.RAW_Y8, record.luma.format)
        assertEquals(SkySessionCaptureFixtures.BUFFER_WIDTH_PX, record.luma.widthPx)
        assertEquals(SkySessionCaptureFixtures.BUFFER_HEIGHT_PX, record.luma.heightPx)
        assertEquals(SkySessionCaptureFixtures.BUFFER_WIDTH_PX, record.luma.rowStridePx)
        assertContentEquals(
            frame.lumaData,
            lumaFile.readBytes(),
            "the stored plane must be the analyzed plane, byte for byte",
        )
    }

    @Test
    fun `a padded row stride is stored and described exactly, not silently repacked`() {
        val stride = SkySessionCaptureFixtures.BUFFER_WIDTH_PX + 16
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())
        recorder.record(
            frame = fixtures.analyzedFrame(rowStridePx = stride),
            exposure = fixtures.exposureSample(),
            geometry = fixtures.geometry(),
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val sessionDirectory = File(directory, "session")
        val record = parseSkySessionLog(File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME).readText()).records.single()

        assertEquals(stride, record.luma.rowStridePx)
        assertEquals(stride.toLong() * SkySessionCaptureFixtures.BUFFER_HEIGHT_PX, record.luma.byteLength)
        assertEquals(record.luma.byteLength, File(sessionDirectory, record.luma.path).length())
        assertTrue(record.luma.byteLength >= record.luma.minimumByteLength)
    }

    @Test
    fun `the log line references its pixels by a path relative to the session directory`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())
        recorder.record(
            frame = fixtures.analyzedFrame(),
            exposure = fixtures.exposureSample(),
            geometry = fixtures.geometry(),
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val record =
            parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).records.single()

        assertEquals("$SKY_SESSION_FRAMES_DIRECTORY_NAME/frame_000000.y", record.luma.path)
    }

    @Test
    fun `pixels whose geometry belongs to a different frame are refused, not mispaired`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(timestampNanos = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS),
                exposure = fixtures.exposureSample(),
                geometry =
                    fixtures.geometry(
                        timestampNanos =
                            SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + 33_000_000L,
                    ),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.GEOMETRY_FRAME_MISMATCH, outcome)
        assertEquals(0L, recorder.recordedFrameCount)
        assertEquals(1L, recorder.droppedFrameCount)
        assertEquals(
            0,
            parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).records.size,
        )
    }

    @Test
    fun `recording before start is refused rather than silently discarded`() {
        val recorder = recorderIn(newSessionDirectory())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = fixtures.exposureSample(),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )

        assertEquals(SkyRecordOutcome.NOT_RECORDING, outcome)
    }

    @Test
    fun `the exposure read for a frame is written to that frame's own line`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())
        recorder.record(
            frame = fixtures.analyzedFrame(),
            exposure = fixtures.exposureSample(),
            geometry = fixtures.geometry(),
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val record =
            parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).records.single()
        val exposure = assertNotNull(record.exposure)

        assertEquals(500_000_000L, exposure.exposureTimeNanos)
        assertEquals(1600, exposure.sensitivityIso)
        assertEquals("OFF", exposure.aeMode)
        assertEquals(SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS, exposure.sensorTimestampNanos)
    }

    @Test
    fun `a frame whose matched exposure has no exposure time is refused, not recorded as null`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = exposureFor().copy(exposureTimeNanos = null),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.EXPOSURE_TIME_MISSING, outcome)
        assertEquals(
            0,
            parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).records.size,
        )
        assertEquals(1L, recorder.droppedFrameCount)
    }

    @Test
    fun `a frame whose matched exposure was auto-exposed is refused`() {
        val recorder = recorderIn(newSessionDirectory())
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = exposureFor().copy(aeMode = "ON"),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.EXPOSURE_AE_NOT_OFF, outcome)
        assertEquals(0L, recorder.recordedFrameCount)
    }

    @Test
    fun `a frame whose matched exposure belongs to another frame is refused`() {
        val recorder = recorderIn(newSessionDirectory())
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = exposureFor(SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + 1L),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.EXPOSURE_TIMESTAMP_MISMATCH, outcome)
    }

    @Test
    fun `a frame whose matched exposure has no sensitivity is refused`() {
        val recorder = recorderIn(newSessionDirectory())
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = exposureFor().copy(sensitivityIso = null),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.EXPOSURE_SENSITIVITY_MISSING, outcome)
    }

    // -----------------------------------------------------------------------------------------
    // The whole loop: capture -> disk -> parse -> replay.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a captured session replays offline to exactly the predictions it recorded`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        val sessionHeader = header()
        recorder.start(sessionHeader)

        val stars = fixtures.starDirections()
        val observer = fixtures.observer()
        val context = assertNotNull(observer.toStarProjectionContext())

        repeat(4) { index ->
            val timestampNanos = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + index * 33_000_000L
            val geometry = fixtures.geometry(timestampNanos = timestampNanos, poseTimestampNanos = timestampNanos)
            val prediction = projectStars(stars = stars, context = context, geometry = geometry)
            assertIs<StarPredictionBatchResult.Ready>(prediction)
            assertEquals(
                SkyRecordOutcome.RECORDED,
                recorder.record(
                    frame = fixtures.analyzedFrame(timestampNanos = timestampNanos, seed = index),
                    exposure = fixtures.exposureSample(sensorTimestampNanos = timestampNanos),
                    geometry = geometry,
                    capturedAtEpochMillis = 1_767_225_600_000L + index,
                    observer = observer,
                    stars = stars,
                    prediction = prediction,
                ),
            )
        }
        recorder.stop()

        val document = parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText())
        val report = assertNotNull(replaySkySessionLog(document))

        assertEquals(4, report.readyFrames.size)
        assertEquals(emptyList(), report.skippedFrames)
        assertEquals(
            0.0,
            assertNotNull(report.maxImageResidualPx),
            "an offline replay must reproduce the recorded pixels exactly",
        )
        report.readyFrames.forEach { frame ->
            assertEquals(stars.size, frame.residuals.size)
            assertEquals(0, frame.classificationMismatchCount)
        }
    }

    @Test
    fun `the recorded pose is the display-remapped matrix the math consumed`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())
        val geometry = fixtures.geometry()
        recorder.record(
            frame = fixtures.analyzedFrame(),
            exposure = fixtures.exposureSample(),
            geometry = geometry,
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val record =
            parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).records.single()

        assertEquals(geometry.pairedRotation.timestampNanos, record.pose.timestampNanos)
        assertEquals(geometry.frameRotationDeltaNanos, record.pose.frameToPoseRawDeltaNanos)
        geometry.pairedRotation.rotationMatrix.forEachIndexed { index, value ->
            assertEquals(value.toDouble(), record.pose.rotationMatrix[index], absoluteTolerance = 1e-6)
        }
    }

    @Test
    fun `a frame with no observing context is dropped rather than written with a null observer`() {
        // The start gate normally makes this unreachable; this is the mid-session backstop for a fix
        // that goes away (permission revoked from Settings, provider stops) while recording runs.
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = fixtures.exposureSample(),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = null,
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        val sessionDirectory = File(directory, "session")
        assertEquals(SkyRecordOutcome.OBSERVER_CONTEXT_UNAVAILABLE, outcome)
        assertEquals(0L, recorder.recordedFrameCount)
        assertEquals(1L, recorder.droppedFrameCount)
        assertEquals(
            0,
            parseSkySessionLog(File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME).readText()).records.size,
            "an observer-less frame must never reach the log",
        )
        assertEquals(
            0,
            File(sessionDirectory, SKY_SESSION_FRAMES_DIRECTORY_NAME).listFiles()?.size,
            "and must not leave its pixels behind either - the drop happens before any byte is written",
        )
    }

    @Test
    fun `a frame whose observing context has no magnetic declination is dropped too`() {
        // It would replay as MAGNETIC_DECLINATION_UNAVAILABLE: a location with no declination cannot
        // produce a star projection context, so it is no more usable than no location at all.
        val recorder = recorderIn(newSessionDirectory())
        recorder.start(header())

        val outcome =
            recorder.record(
                frame = fixtures.analyzedFrame(),
                exposure = fixtures.exposureSample(),
                geometry = fixtures.geometry(),
                capturedAtEpochMillis = 0L,
                observer = fixtures.observer().copy(magneticDeclinationDeg = null),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )
        recorder.stop()

        assertEquals(SkyRecordOutcome.OBSERVER_CONTEXT_UNAVAILABLE, outcome)
        assertEquals(0L, recorder.recordedFrameCount)
    }

    // -----------------------------------------------------------------------------------------
    // Clock provenance in the header
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a camera that reports a REALTIME timestamp source writes a source-proven alignment`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header(clockAlignment = skyClockAlignmentFor(SkyCameraTimestampSource.REALTIME)))
        recorder.stop()

        val alignment =
            assertNotNull(
                parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText()).header,
            ).clockAlignment

        assertEquals(SkyClockRelationship.SOURCE_PROVEN_COMPARABLE, alignment.relationship)
        assertEquals(SkyClock.CAMERA_SENSOR_NANOS, alignment.frameClock)
        assertEquals(SkyClock.SENSOR_EVENT_NANOS, alignment.poseClock)
        assertNull(
            alignment.poseToFrameOffsetNanos,
            "a proven-comparable session must not claim a measured offset it never measured",
        )
    }

    @Test
    fun `a camera that will not state its timestamp source writes an unalignable session`() {
        val directory = newSessionDirectory()
        val recorder = recorderIn(directory)
        recorder.start(header(clockAlignment = skyClockAlignmentFor(SkyCameraTimestampSource.UNKNOWN)))
        recorder.record(
            frame = fixtures.analyzedFrame(),
            exposure = fixtures.exposureSample(),
            geometry = fixtures.geometry(),
            capturedAtEpochMillis = 0L,
            observer = fixtures.observer(),
            stars = emptyList(),
            prediction = StarPredictionBatchResult.Ready.of(emptyList()),
        )
        recorder.stop()

        val document = parseSkySessionLog(File(File(directory, "session"), SKY_SESSION_LOG_FILE_NAME).readText())
        val report = assertNotNull(replaySkySessionLog(document))

        assertEquals(
            SkyClockRelationship.UNKNOWN,
            assertNotNull(document.header).clockAlignment.relationship,
        )
        assertEquals(
            listOf(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED),
            report.skippedFrames.map { it.reason },
            "an unproven session must replay as skipped, never as a zero-offset projection",
        )
    }
}
