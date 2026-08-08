package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockAlignment
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPinholeRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPoseSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPredictedStar
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkyFrameLine
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkySessionHeaderLine
import dev.pointtosky.core.astro.projection.camera.skylog.toSkyIntrinsicsRecord
import java.io.File

/**
 * Builds a session directory on disk in exactly the layout
 * `dev.pointtosky.mobile.ar.camera.SkySessionLogWriter` produces — `session.jsonl` plus
 * `frames/frame_NNNNNN.y` — using only the public SKY-1 encoders, so a fixture can never drift from
 * the wire format the codec defines.
 *
 * The pixels are synthetic and the recorded `imageXPx`/`imageYPx` are the positions they were rendered
 * at, which is what makes a detection rate measurable without a device. Those coordinates are *not*
 * claimed to be a real projection's output: `SkySessionLogReplayTest` in `:core:astro-core` is what
 * verifies the projection, and these tests measure only how close the detector's centroids come to
 * given positions.
 */
internal object SyntheticSession {
    const val BUFFER_WIDTH_PX = 640
    const val BUFFER_HEIGHT_PX = 480

    /** Padded rows, as a real camera plane usually has, so every read has to honour the stride. */
    const val ROW_STRIDE_PX = 704

    const val VIEWPORT_WIDTH_PX = 1080
    const val VIEWPORT_HEIGHT_PX = 2400

    const val FIRST_FRAME_TIMESTAMP_NANOS = 1_000_000_000L
    const val FRAME_PERIOD_NANOS = 33_000_000L
    const val STARTED_AT_EPOCH_MILLIS = 1_767_225_600_000L

    /** A 30 degree rotation about the world Z axis — orthonormal, and not a degenerate identity. */
    val rotationMatrix: List<Double> =
        listOf(0.8660254037844387, -0.5, 0.0, 0.5, 0.8660254037844387, 0.0, 0.0, 0.0, 1.0)

    /**
     * What a device whose camera reports `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` records: two
     * differently-named clocks the platform documents onto one time base, so replay can align them.
     */
    val provenClockAlignment: SkyClockAlignment =
        SkyClockAlignment.sourceProvenComparable(
            frameClock = SkyClock.CAMERA_SENSOR_NANOS,
            poseClock = SkyClock.SENSOR_EVENT_NANOS,
        )

    /** What a device that could not name its clocks records. Replay must refuse to align this. */
    val unknownClockAlignment: SkyClockAlignment =
        SkyClockAlignment.unknown(frameClock = SkyClock.UNKNOWN, poseClock = SkyClock.UNKNOWN)

    fun observer(magneticDeclinationDeg: Double? = 11.5): SkyObserverContext =
        SkyObserverContext(
            latitudeDeg = 50.4501,
            longitudeDeg = 30.5234,
            utcEpochMillis = STARTED_AT_EPOCH_MILLIS,
            horizontalAccuracyM = 8.5,
            magneticDeclinationDeg = magneticDeclinationDeg,
        )

    fun header(clockAlignment: SkyClockAlignment = provenClockAlignment): SkySessionLogHeader =
        SkySessionLogHeader(
            sessionId = "sky-20260808-201500",
            startedAtEpochMillis = STARTED_AT_EPOCH_MILLIS,
            bufferWidthPx = BUFFER_WIDTH_PX,
            bufferHeightPx = BUFFER_HEIGHT_PX,
            intrinsics =
                CameraIntrinsicsResolution
                    .Resolved(
                        CameraIntrinsics(
                            horizontalFovDeg = 66.0,
                            verticalFovDeg = 52.0,
                            focalLengthMm = 4.38,
                            sensorWidthMm = 5.76,
                            sensorHeightMm = 4.29,
                            principalPointXPx = null,
                            principalPointYPx = null,
                            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                            reference =
                                CameraIntrinsicsReference.AnalysisBuffer(
                                    widthPx = BUFFER_WIDTH_PX,
                                    heightPx = BUFFER_HEIGHT_PX,
                                ),
                            quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
                        ),
                    ).toSkyIntrinsicsRecord(
                        pinhole = SkyPinholeRecord(fxPx = 489.0, fyPx = 491.5, cxPx = 320.0, cyPx = 240.0),
                    ),
            clockAlignment = clockAlignment,
            maxPairDeltaNanos = 25_000_000L,
            clockMismatchThresholdNanos = 250_000_000L,
            deviceModel = "Pixel 9",
            cameraId = "0",
            notes = "synthetic session fixture",
        )

    /** One frame's rendered pixels together with the log line that describes them. */
    data class Frame(
        val record: SkyFrameRecord,
        val data: ByteArray,
        val stars: List<SyntheticStar>,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun frame(
        sequence: Long,
        stars: List<SyntheticStar>,
        observer: SkyObserverContext? = observer(),
        rowStridePx: Int = ROW_STRIDE_PX,
        noiseSigma: Double = 2.0,
    ): Frame {
        val data =
            renderLumaBytes(
                widthPx = BUFFER_WIDTH_PX,
                heightPx = BUFFER_HEIGHT_PX,
                rowStridePx = rowStridePx,
                stars = stars,
                noiseSigma = noiseSigma,
                seed = 606L + sequence,
            )
        val reference =
            SkyLumaReference(
                path = "$SKY_SESSION_FRAMES_DIRECTORY_NAME/${lumaFileName(sequence)}",
                format = SkyLumaFormat.RAW_Y8,
                widthPx = BUFFER_WIDTH_PX,
                heightPx = BUFFER_HEIGHT_PX,
                rowStridePx = rowStridePx,
                byteLength = rowStridePx.toLong() * BUFFER_HEIGHT_PX,
            )
        val timestampNanos = FIRST_FRAME_TIMESTAMP_NANOS + sequence * FRAME_PERIOD_NANOS
        val record =
            SkyFrameRecord(
                sequence = sequence,
                capturedAtEpochMillis = STARTED_AT_EPOCH_MILLIS + sequence,
                frame =
                    CameraFrameMetadata(
                        timestampNanos = timestampNanos,
                        bufferWidthPx = BUFFER_WIDTH_PX,
                        bufferHeightPx = BUFFER_HEIGHT_PX,
                        rotationDegrees = 0,
                    ),
                viewportWidthPx = VIEWPORT_WIDTH_PX,
                viewportHeightPx = VIEWPORT_HEIGHT_PX,
                luma = reference,
                // Same timestamp as the frame, so pairing succeeds at the header's own tolerance.
                pose =
                    SkyPoseSample(
                        timestampNanos = timestampNanos,
                        rotationMatrix = rotationMatrix,
                        frameToPoseRawDeltaNanos = 0L,
                    ),
                observer = observer,
                predictedStars =
                    stars.mapIndexed { index, star ->
                        SkyPredictedStar(
                            catalogIndex = 100 + index,
                            rightAscensionRad = 0.1 + index * 0.2,
                            declinationRad = 0.4 - index * 0.1,
                            magnitude = 1.25 + index,
                            classification = PredictedStarClassification.VISIBLE_IN_VIEWPORT,
                            imageXPx = star.xPx,
                            imageYPx = star.yPx,
                        )
                    },
            )
        return Frame(record = record, data = data, stars = stars)
    }

    /** Writes the directory: pixels first, then the lines that reference them — the writer's order. */
    fun write(
        directory: File,
        header: SkySessionLogHeader,
        frames: List<Frame>,
    ) {
        File(directory, SKY_SESSION_FRAMES_DIRECTORY_NAME).mkdirs()
        frames.forEach { frame -> File(directory, frame.record.luma.path).writeBytes(frame.data) }
        val lines = listOf(encodeSkySessionHeaderLine(header)) + frames.map { encodeSkyFrameLine(it.record) }
        File(directory, SKY_SESSION_LOG_FILE_NAME).writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    /** Zero-padded exactly as `SkySessionLogWriter.lumaFileName` writes it. */
    fun lumaFileName(sequence: Long): String = "frame_" + sequence.toString().padStart(6, '0') + ".y"

    /** Five well-separated sub-pixel positions inside the 640x480 buffer, clear of every border. */
    fun defaultStars(): List<SyntheticStar> =
        listOf(
            120.5 to 90.25,
            305.75 to 160.5,
            480.2 to 250.8,
            210.6 to 380.4,
            560.5 to 400.5,
        ).mapIndexed { index, (x, y) ->
            SyntheticStar(xPx = x, yPx = y, peakAboveBackground = 120.0 + index * 10.0)
        }
}
