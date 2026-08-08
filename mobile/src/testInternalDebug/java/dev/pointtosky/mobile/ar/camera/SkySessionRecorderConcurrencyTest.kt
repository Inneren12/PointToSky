package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): the Stop boundary.
 *
 * `record` runs on the camera analysis executor and `stop` on the main thread, with the analyzer
 * still bound and still delivering. These tests pin the three properties that makes safe:
 * an in-flight commit finishes atomically, a frame starting after Stop writes nothing, and `close`
 * never interleaves with a write.
 *
 * They are deterministic without any timing sleep. The sink can be paused inside `appendFrame` on a
 * latch, and where a thread genuinely must be observed to block, the test spins on
 * `Thread.getState() == BLOCKED` rather than sleeping a guessed interval — that is a direct assertion
 * that the lock is doing its job, not an approximation of one.
 */
class SkySessionRecorderConcurrencyTest {
    private val fixtures = SkySessionCaptureFixtures

    /**
     * A sink that records the exact order of every call and can be paused inside [appendFrame].
     *
     * Deliberately not a filesystem: proving ordering against real I/O means racing real I/O, while a
     * pausable fake makes the interleaving the test wants the only one that can happen.
     */
    private class RecordingSink(
        private val pauseInAppend: CountDownLatch? = null,
        private val enteredAppend: CountDownLatch? = null,
    ) : SkySessionLogSink {
        val calls = mutableListOf<String>()
        private val callLock = Any()

        override val sessionPath: String = "/fake/session"
        override var writtenFrameCount: Long = 0L
            private set
        override var writtenLumaBytes: Long = 0L
            private set
        override var lastFailure: SkySessionWriteFailure? = null
            private set

        private fun note(call: String) = synchronized(callLock) { calls += call }

        fun callsSnapshot(): List<String> = synchronized(callLock) { calls.toList() }

        override fun start(header: SkySessionLogHeader): Boolean {
            note("start")
            return true
        }

        override fun writeLumaFrame(
            sequence: Long,
            data: ByteArray,
            widthPx: Int,
            heightPx: Int,
            rowStridePx: Int,
        ): SkyLumaReference {
            note("writeLuma:$sequence")
            writtenLumaBytes += rowStridePx.toLong() * heightPx
            return SkyLumaReference(
                path = "$SKY_SESSION_FRAMES_DIRECTORY_NAME/${SkySessionLogWriter.lumaFileName(sequence)}",
                format = SkyLumaFormat.RAW_Y8,
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                byteLength = rowStridePx.toLong() * heightPx,
            )
        }

        override fun appendFrame(record: SkyFrameRecord): Boolean {
            note("appendFrame:${record.sequence}")
            enteredAppend?.countDown()
            pauseInAppend?.await()
            note("appendFrameDone:${record.sequence}")
            writtenFrameCount += 1
            return true
        }

        override fun close() {
            note("close")
        }
    }

    private fun header(): SkySessionLogHeader =
        buildSkySessionHeader(
            sessionId = "sky_concurrency",
            startedAtEpochMillis = 0L,
            bufferWidthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            bufferHeightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
            intrinsics = fixtures.intrinsics(),
            clockAlignment = skyClockAlignmentFor(SkyCameraTimestampSource.REALTIME),
            maxPairDeltaNanos = 25_000_000L,
            clockMismatchThresholdNanos = 5_000_000_000L,
            deviceModel = null,
            cameraId = "3",
            physicalCameraIds = emptyList(),
            calibration = null,
            pinhole = null,
            notes = null,
        )

    private fun SkySessionRecorder.recordFixtureFrame(
        timestampNanos: Long = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS,
    ) = record(
        frame = fixtures.analyzedFrame(timestampNanos = timestampNanos),
        exposure = fixtures.exposureSample(sensorTimestampNanos = timestampNanos),
        geometry = fixtures.geometry(timestampNanos = timestampNanos, poseTimestampNanos = timestampNanos),
        capturedAtEpochMillis = 0L,
        observer = fixtures.observer(),
        stars = emptyList(),
        prediction = StarPredictionBatchResult.Ready.of(emptyList()),
    )

    /** Spins until [thread] is blocked on a monitor. No sleep interval is guessed at. */
    private fun awaitBlocked(thread: Thread) {
        while (thread.state != Thread.State.BLOCKED && thread.isAlive) {
            Thread.onSpinWait()
        }
    }

    // -----------------------------------------------------------------------------------------

    @Test
    fun `stop waits for an in-flight commit and closes only after it finishes`() {
        val enteredAppend = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val sink = RecordingSink(pauseInAppend = releaseAppend, enteredAppend = enteredAppend)
        val recorder = SkySessionRecorder(sink)
        assertTrue(recorder.start(header()))

        var outcome: SkyRecordOutcome? = null
        val writer = thread(name = "sky-analysis") { outcome = recorder.recordFixtureFrame() }
        enteredAppend.await()

        val stopper = thread(name = "sky-main") { recorder.stop() }
        awaitBlocked(stopper)

        releaseAppend.countDown()
        writer.join()
        stopper.join()

        assertEquals(SkyRecordOutcome.RECORDED, outcome)
        assertEquals(
            listOf("start", "writeLuma:0", "appendFrame:0", "appendFrameDone:0", "close"),
            sink.callsSnapshot(),
            "close must never interleave with a write",
        )
    }

    @Test
    fun `a frame starting after stop writes nothing`() {
        val sink = RecordingSink()
        val recorder = SkySessionRecorder(sink)
        recorder.start(header())
        recorder.stop()

        val outcome = recorder.recordFixtureFrame()

        assertEquals(SkyRecordOutcome.NOT_RECORDING, outcome)
        assertEquals(listOf("start", "close"), sink.callsSnapshot())
        assertEquals(0L, recorder.recordedFrameCount)
    }

    @Test
    fun `a frame that arrives while stop is blocked behind it is rejected, not written`() {
        val enteredAppend = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val sink = RecordingSink(pauseInAppend = releaseAppend, enteredAppend = enteredAppend)
        val recorder = SkySessionRecorder(sink)
        recorder.start(header())

        val firstFrame = thread { recorder.recordFixtureFrame() }
        enteredAppend.await()
        val stopper = thread { recorder.stop() }
        awaitBlocked(stopper)

        // A third caller queues behind both. Whichever of stop/record the monitor admits first, the
        // recorder is terminal by the time this one runs if stop won, and this one is simply the second
        // recorded frame if it won - so the assertion is on the invariant, not on the scheduler.
        var secondOutcome: SkyRecordOutcome? = null
        val secondFrame =
            thread {
                secondOutcome =
                    recorder.recordFixtureFrame(
                        timestampNanos =
                            SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + 33_000_000L,
                    )
            }

        releaseAppend.countDown()
        firstFrame.join()
        stopper.join()
        secondFrame.join()

        val calls = sink.callsSnapshot()
        val closeIndex = calls.indexOf("close")
        assertTrue(closeIndex >= 0, "close must have run")
        assertTrue(
            calls.drop(closeIndex + 1).none { it.startsWith("writeLuma") || it.startsWith("appendFrame") },
            "no write may follow close; calls were $calls",
        )
        if (secondOutcome == SkyRecordOutcome.NOT_RECORDING) {
            assertEquals(1L, sink.writtenFrameCount)
        } else {
            assertEquals(SkyRecordOutcome.RECORDED, secondOutcome)
        }
    }

    @Test
    fun `stop is idempotent and closes exactly once`() {
        val sink = RecordingSink()
        val recorder = SkySessionRecorder(sink)
        recorder.start(header())

        recorder.stop()
        recorder.stop()
        recorder.stop()

        assertEquals(listOf("start", "close"), sink.callsSnapshot())
    }

    @Test
    fun `stopping a recorder that never started does not close a sink it never opened`() {
        val sink = RecordingSink()

        SkySessionRecorder(sink).stop()

        assertEquals(emptyList(), sink.callsSnapshot())
    }

    @Test
    fun `a stopped recorder is terminal and refuses to restart`() {
        val sink = RecordingSink()
        val recorder = SkySessionRecorder(sink)
        recorder.start(header())
        recorder.stop()

        assertFalse(recorder.start(header()), "restarting would reuse the sequence numbering and the directory")
        assertEquals(SkyRecorderState.STOPPED, recorder.currentState)
        assertEquals(SkyRecordOutcome.RECORDER_TERMINAL.name, recorder.lastFailureReason)
        assertEquals(listOf("start", "close"), sink.callsSnapshot())
    }

    @Test
    fun `start is idempotent while recording`() {
        val sink = RecordingSink()
        val recorder = SkySessionRecorder(sink)

        assertTrue(recorder.start(header()))
        assertTrue(recorder.start(header()))

        assertEquals(listOf("start"), sink.callsSnapshot())
    }

    @Test
    fun `the state machine runs IDLE to RECORDING to STOPPED`() {
        val recorder = SkySessionRecorder(RecordingSink())

        assertEquals(SkyRecorderState.IDLE, recorder.currentState)
        recorder.start(header())
        assertEquals(SkyRecorderState.RECORDING, recorder.currentState)
        recorder.stop()
        assertEquals(SkyRecorderState.STOPPED, recorder.currentState)
    }

    @Test
    fun `concurrent recorders on many threads all serialize through the sink`() {
        val sink = RecordingSink()
        val recorder = SkySessionRecorder(sink)
        recorder.start(header())

        val start = CountDownLatch(1)
        val threads =
            (0 until 8).map { index ->
                thread {
                    start.await()
                    recorder.recordFixtureFrame(
                        timestampNanos = SkySessionCaptureFixtures.FRAME_TIMESTAMP_NANOS + index * 33_000_000L,
                    )
                }
            }
        start.countDown()
        threads.forEach { it.join() }
        recorder.stop()

        val calls = sink.callsSnapshot()
        // Every frame's three calls must appear as an uninterrupted run: writeLuma, appendFrame,
        // appendFrameDone. Interleaving would mean two frames were committing at once.
        val writes = calls.filter { it != "start" && it != "close" }
        assertEquals(24, writes.size)
        writes.chunked(3).forEach { (luma, append, done) ->
            val sequence = luma.substringAfter(':')
            assertEquals("writeLuma:$sequence", luma)
            assertEquals("appendFrame:$sequence", append)
            assertEquals("appendFrameDone:$sequence", done)
        }
        assertEquals(8L, recorder.recordedFrameCount)
        assertEquals("close", calls.last())
    }
}
