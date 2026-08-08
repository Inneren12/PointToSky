package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockAlignment
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplayResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPinholeRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPoseSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPredictedStar
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkyFrameLine
import dev.pointtosky.core.astro.projection.camera.skylog.encodeSkySessionHeaderLine
import dev.pointtosky.core.astro.projection.camera.skylog.replaySkySessionFrame
import dev.pointtosky.core.astro.projection.camera.skylog.toSkyIntrinsicsRecord
import java.io.File
import kotlin.math.hypot

/**
 * Builds a session directory on disk in exactly the layout
 * `dev.pointtosky.mobile.ar.camera.SkySessionLogWriter` produces — `session.jsonl` plus
 * `frames/frame_NNNNNN.y` — using only the public SKY-1 encoders, so a fixture can never drift from
 * the wire format the codec defines.
 *
 * ## Self-consistency is the whole point of the happy-path fixture
 * A session is only a valid input for a *detection rate* if its pixels, its recorded predictions and
 * the projection replay recomputes all describe the same sky. So [selfConsistentFrame] does not invent
 * pixel positions: it runs the real path — header intrinsics + frame pose + observer + star RA/Dec
 * through [replaySkySessionFrame] — takes the detector-observable `imagePoint`s that come out, renders
 * the luma at exactly those positions, and records exactly those coordinates. Replaying the result
 * therefore reproduces the recorded values bit for bit, which is what a captured session from an intact
 * device looks like.
 *
 * [inconsistentFrame] is the deliberate opposite: pixels at the replayed positions, recorded
 * coordinates shifted away from them. It exists to prove SKY-3 scores the detector against the
 * *replayed* projection and reports the recorded-vs-replayed gap separately, instead of quietly
 * grading the detector against a coordinate nothing verified.
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

    /** How far apart two rendered stars must be so their footprints cannot merge into one source. */
    private const val MIN_STAR_SEPARATION_PX = 40.0

    /** How far from the border a rendered star must stay so its PSF is not truncated. */
    private const val BORDER_MARGIN_PX = 24.0

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

    /**
     * Both timestamp APIs are known — the capture read `ImageProxy`'s and `SensorEvent`'s — but nothing
     * established that they share a time base and no offset was measured. Naming the clocks
     * [SkyClock.UNKNOWN] would say something different and false: that the capture could not tell which
     * API a timestamp came from. Replay refuses to align this and reports `POSE_CLOCK_UNALIGNED`.
     */
    val unknownClockAlignment: SkyClockAlignment =
        SkyClockAlignment.unknown(
            frameClock = SkyClock.CAMERA_SENSOR_NANOS,
            poseClock = SkyClock.SENSOR_EVENT_NANOS,
        )

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
        /** Where the pixels were actually rendered — the replayed positions, in the observable set. */
        val renderedStars: List<SyntheticStar>,
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * A frame whose pixels, recorded predictions and replayed projection all agree.
     *
     * @param starCount how many observable stars to render; the fixture searches the sky for that many
     *   directions that project into the frame, well separated and clear of the borders.
     * @param renderStars when `false` the luma is left starless while the predictions stay in place —
     *   a valid truth set the detector scores zero against, which is a different state from having no
     *   truth set at all.
     */
    fun selfConsistentFrame(
        sequence: Long,
        header: SkySessionLogHeader = header(),
        observer: SkyObserverContext? = observer(),
        starCount: Int = 5,
        rowStridePx: Int = ROW_STRIDE_PX,
        renderStars: Boolean = true,
        noiseSigma: Double = 2.0,
        recordedOffsetPx: Double = 0.0,
    ): Frame {
        val directions = observableDirections(sequence, header, observer, starCount)
        return buildFrame(
            sequence = sequence,
            header = header,
            observer = observer,
            directions = directions,
            rowStridePx = rowStridePx,
            renderStars = renderStars,
            noiseSigma = noiseSigma,
            recordedOffsetPx = recordedOffsetPx,
        )
    }

    /**
     * The same frame, except the recorded pixel coordinates are shifted [recordedOffsetPx] away from
     * the replayed ones while the pixels stay at the replayed positions. A stale or hand-edited record,
     * in other words — the case where scoring against recorded coordinates gives the wrong answer.
     */
    fun inconsistentFrame(
        sequence: Long,
        recordedOffsetPx: Double,
        header: SkySessionLogHeader = header(),
        starCount: Int = 5,
    ): Frame =
        selfConsistentFrame(
            sequence = sequence,
            header = header,
            starCount = starCount,
            recordedOffsetPx = recordedOffsetPx,
        )

    /**
     * A frame that replays cleanly but whose every recorded star is behind the camera, so replay
     * produces no observable source at all. The luma still has stars in it, which is exactly the state
     * that must not be scored: detections with nothing to pair against are not false positives.
     */
    fun frameWithNoObservablePredictions(
        sequence: Long,
        header: SkySessionLogHeader = header(),
        observer: SkyObserverContext? = observer(),
        renderedStars: List<SyntheticStar> = starsAt(listOf(160.5 to 120.25, 400.75 to 300.5)),
    ): Frame {
        val behindCamera = behindCameraDirections(sequence, header, observer)
        check(behindCamera.isNotEmpty()) { "the fixture found no behind-camera direction to record" }
        val data = renderFrameData(sequence, ROW_STRIDE_PX, renderedStars, noiseSigma = 2.0)
        val record =
            frameRecord(
                sequence = sequence,
                observer = observer,
                rowStridePx = ROW_STRIDE_PX,
                predictedStars =
                    behindCamera.map { direction ->
                        SkyPredictedStar(
                            catalogIndex = direction.catalogIndex,
                            rightAscensionRad = direction.rightAscensionRad,
                            declinationRad = direction.declinationRad,
                            magnitude = direction.magnitude,
                            classification = PredictedStarClassification.BEHIND_CAMERA,
                        )
                    },
            )
        return Frame(record = record, data = data, renderedStars = renderedStars)
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

    fun starsAt(positions: List<Pair<Double, Double>>): List<SyntheticStar> =
        positions.mapIndexed { index, (x, y) ->
            SyntheticStar(xPx = x, yPx = y, peakAboveBackground = 120.0 + index * 10.0)
        }

    /** One candidate star direction, before anything is known about where it lands. */
    private data class StarDirection(
        val catalogIndex: Int,
        val rightAscensionRad: Double,
        val declinationRad: Double,
        val magnitude: Double,
    )

    /**
     * Sweeps the sky, replays the sweep, and keeps the directions whose recomputed `imagePoint` is
     * observable, clear of the borders and far enough from every direction already kept.
     *
     * A search rather than hand-picked RA/Dec constants: the positions have to come out of the same
     * projection replay runs, and a constant that merely happened to land in frame when it was written
     * would stop being self-consistent the moment any projection detail changed.
     */
    private fun observableDirections(
        sequence: Long,
        header: SkySessionLogHeader,
        observer: SkyObserverContext?,
        starCount: Int,
    ): List<StarDirection> {
        val kept = mutableListOf<StarDirection>()
        val keptPoints = mutableListOf<Pair<Double, Double>>()
        projectCandidates(sequence, header, observer).forEach { (direction, projection) ->
            if (kept.size == starCount) return@forEach
            val point = projection.imagePoint ?: return@forEach
            if (!isObservable(projection.classification)) return@forEach
            val insideMargin =
                point.x in BORDER_MARGIN_PX..(BUFFER_WIDTH_PX - BORDER_MARGIN_PX) &&
                    point.y in BORDER_MARGIN_PX..(BUFFER_HEIGHT_PX - BORDER_MARGIN_PX)
            if (!insideMargin) return@forEach
            if (keptPoints.any { hypot(it.first - point.x, it.second - point.y) < MIN_STAR_SEPARATION_PX }) {
                return@forEach
            }
            kept += direction
            keptPoints += point.x to point.y
        }
        check(kept.size == starCount) {
            "the fixture found only ${kept.size} of $starCount in-frame directions; widen the sweep"
        }
        return kept
    }

    /** Directions the same sweep put behind the camera — a normal projection outcome, not an error. */
    private fun behindCameraDirections(
        sequence: Long,
        header: SkySessionLogHeader,
        observer: SkyObserverContext?,
    ): List<StarDirection> =
        projectCandidates(sequence, header, observer)
            .filter { (_, projection) -> projection.classification == PredictedStarClassification.BEHIND_CAMERA }
            .map { it.first }
            .take(3)

    /**
     * Runs the sweep through [replaySkySessionFrame] — the very path SKY-3 derives its truth set from.
     *
     * The provisional record carries no pixel coordinates at all, so nothing recorded can influence
     * what comes back: replay reprojects from RA/Dec, the pose and the intrinsics alone.
     */
    private fun projectCandidates(
        sequence: Long,
        header: SkySessionLogHeader,
        observer: SkyObserverContext?,
    ): List<Pair<StarDirection, PredictedStarProjection>> {
        val candidates = candidateDirections()
        val provisional =
            frameRecord(
                sequence = sequence,
                observer = observer,
                rowStridePx = ROW_STRIDE_PX,
                predictedStars =
                    candidates.map { direction ->
                        SkyPredictedStar(
                            catalogIndex = direction.catalogIndex,
                            rightAscensionRad = direction.rightAscensionRad,
                            declinationRad = direction.declinationRad,
                            magnitude = direction.magnitude,
                            // No coordinates, so this provisional line cannot seed its own answer; the
                            // classification is a placeholder replay never reads as an input.
                            classification = PredictedStarClassification.BEHIND_CAMERA,
                        )
                    },
            )
        val replayed = replaySkySessionFrame(header, provisional)
        check(replayed is SkyFrameReplayResult.Ready) {
            "the fixture's own frame must replay; was $replayed"
        }
        return candidates.zip(replayed.projections)
    }

    /** A coarse whole-sky grid. Deterministic, and wide enough that some of it is always in frame. */
    private fun candidateDirections(): List<StarDirection> {
        val directions = mutableListOf<StarDirection>()
        var index = 0
        for (raStep in 0 until RA_STEPS) {
            for (decStep in 0 until DEC_STEPS) {
                directions +=
                    StarDirection(
                        catalogIndex = 1000 + index,
                        rightAscensionRad = TWO_PI * raStep / RA_STEPS,
                        declinationRad = -HALF_PI + PI * (decStep + 0.5) / DEC_STEPS,
                        magnitude = 1.5 + (index % 4) * 0.5,
                    )
                index += 1
            }
        }
        return directions
    }

    /** SKY-2's rule, restated only for fixture selection; the production rule lives in astro-core. */
    private fun isObservable(classification: PredictedStarClassification): Boolean =
        classification == PredictedStarClassification.VISIBLE_IN_VIEWPORT ||
            classification == PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT

    private fun buildFrame(
        sequence: Long,
        header: SkySessionLogHeader,
        observer: SkyObserverContext?,
        directions: List<StarDirection>,
        rowStridePx: Int,
        renderStars: Boolean,
        noiseSigma: Double,
        recordedOffsetPx: Double,
    ): Frame {
        val projections =
            projectCandidates(sequence, header, observer)
                .filter { (direction, _) -> directions.any { it.catalogIndex == direction.catalogIndex } }
        val renderedStars =
            projections.mapIndexed { index, (_, projection) ->
                val point = checkNotNull(projection.imagePoint)
                SyntheticStar(xPx = point.x, yPx = point.y, peakAboveBackground = 120.0 + index * 10.0)
            }
        val data =
            renderFrameData(
                sequence = sequence,
                rowStridePx = rowStridePx,
                stars = if (renderStars) renderedStars else emptyList(),
                noiseSigma = noiseSigma,
            )
        val record =
            frameRecord(
                sequence = sequence,
                observer = observer,
                rowStridePx = rowStridePx,
                predictedStars =
                    projections.map { (direction, projection) ->
                        val image = checkNotNull(projection.imagePoint)
                        val display = checkNotNull(projection.displayPoint)
                        SkyPredictedStar(
                            catalogIndex = direction.catalogIndex,
                            rightAscensionRad = direction.rightAscensionRad,
                            declinationRad = direction.declinationRad,
                            magnitude = direction.magnitude,
                            classification = projection.classification,
                            // recordedOffsetPx = 0 makes the record exactly what replay recomputes.
                            imageXPx = image.x + recordedOffsetPx,
                            imageYPx = image.y,
                            displayXPx = display.x,
                            displayYPx = display.y,
                        )
                    },
            )
        return Frame(record = record, data = data, renderedStars = if (renderStars) renderedStars else emptyList())
    }

    private fun renderFrameData(
        sequence: Long,
        rowStridePx: Int,
        stars: List<SyntheticStar>,
        noiseSigma: Double,
    ): ByteArray =
        renderLumaBytes(
            widthPx = BUFFER_WIDTH_PX,
            heightPx = BUFFER_HEIGHT_PX,
            rowStridePx = rowStridePx,
            stars = stars,
            noiseSigma = noiseSigma,
            seed = 606L + sequence,
        )

    private fun frameRecord(
        sequence: Long,
        observer: SkyObserverContext?,
        rowStridePx: Int,
        predictedStars: List<SkyPredictedStar>,
    ): SkyFrameRecord {
        val timestampNanos = FIRST_FRAME_TIMESTAMP_NANOS + sequence * FRAME_PERIOD_NANOS
        return SkyFrameRecord(
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
            luma =
                SkyLumaReference(
                    path = "$SKY_SESSION_FRAMES_DIRECTORY_NAME/${lumaFileName(sequence)}",
                    format = SkyLumaFormat.RAW_Y8,
                    widthPx = BUFFER_WIDTH_PX,
                    heightPx = BUFFER_HEIGHT_PX,
                    rowStridePx = rowStridePx,
                    byteLength = rowStridePx.toLong() * BUFFER_HEIGHT_PX,
                ),
            // Same timestamp as the frame, so pairing succeeds at the header's own tolerance.
            pose =
                SkyPoseSample(
                    timestampNanos = timestampNanos,
                    rotationMatrix = rotationMatrix,
                    frameToPoseRawDeltaNanos = 0L,
                ),
            observer = observer,
            predictedStars = predictedStars,
        )
    }

    private const val RA_STEPS = 48
    private const val DEC_STEPS = 24
    private const val TWO_PI = 6.283185307179586
    private const val PI = 3.141592653589793
    private const val HALF_PI = 1.5707963267948966
}

/**
 * The same frame with a different observer context on its line — how a fixture reaches
 * `OBSERVER_CONTEXT_UNAVAILABLE` or `MAGNETIC_DECLINATION_UNAVAILABLE` without giving up positions that
 * came from a real projection. The pixels and the recorded coordinates are untouched; only the
 * observing context the replay is refused for changes.
 */
internal fun SyntheticSession.Frame.withObserver(observer: SkyObserverContext?): SyntheticSession.Frame =
    copy(record = record.copy(observer = observer))
