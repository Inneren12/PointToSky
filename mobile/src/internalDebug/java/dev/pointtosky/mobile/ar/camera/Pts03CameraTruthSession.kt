package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * PTS-03 (`internalDebug`-only): what one bind of the frame-content experiment accumulates as camera-truth
 * evidence. Pure, immutable, reducer-style — it lives inside [FrameContentExperimentSessionState] and is
 * updated only by that state's attempt-guarded reducers, so a late frame from an older bind can never be
 * mixed into a newer bind's evidence.
 *
 * Two session classes are kept apart and never merged:
 * - [Pts03SessionClass.LOGICAL_UNPINNED] (A1): the logical rear camera bound as production binds it — no
 *   physical pin, no zoom pin. This is the only class from which natural physical-camera switching can be read.
 * - [Pts03SessionClass.EXPLICIT_PHYSICAL] (A2): one declared child pinned with `setPhysicalCameraId`. The
 *   analysed output is that physical camera's **by configuration**. The top-level logical active ID is a
 *   diagnostic of the logical camera's backing sensor and never confirms or contradicts the pin; what A2
 *   records is whether a timestamp-matched physical result exists for the requested ID. See
 *   [attributePts03Frame].
 */
internal enum class Pts03SessionClass {
    LOGICAL_UNPINNED,
    EXPLICIT_PHYSICAL,
}

/** Operator-declared lighting condition, frozen into every frame record. A label, not a measurement. */
internal enum class Pts03LightingLabel {
    UNSPECIFIED,
    NORMAL_INDOOR,
    LOW_LIGHT_INDOOR,
    NIGHT_SKY,
}

/** The candidate label the picker uses for the A1 session; never a Camera2 ID. */
internal const val PTS03_LOGICAL_UNPINNED_CANDIDATE: String = "LOGICAL_UNPINNED"

/** Map key standing for "the result carried no `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`". */
internal const val PTS03_ACTIVE_ID_NOT_REPORTED_KEY: String = "<not reported>"

/** Map key standing for "the producing physical camera of this frame is unknown". */
internal const val PTS03_PRODUCER_UNKNOWN_KEY: String = "<unknown producer>"

/** CameraX's post-bind `ResolutionInfo` for one use case. */
internal data class Pts03ResolutionInfo(
    val widthPx: Int,
    val heightPx: Int,
    val cropRect: Pts03IntRect,
    val rotationDegrees: Int,
)

/**
 * What CameraX reports it configured, read once after a successful bind. `null` for either use case
 * means CameraX returned no `ResolutionInfo` — recorded as UNKNOWN, never inferred from a view size.
 */
internal data class Pts03StreamConfiguration(
    val analysisResolutionInfo: Pts03ResolutionInfo?,
    val previewResolutionInfo: Pts03ResolutionInfo?,
)

/** One distinct analysis-buffer geometry as delivered by `ImageProxy` (not as requested). */
internal data class Pts03AnalysisGeometry(
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val cropRect: Pts03IntRect?,
    val rotationDegrees: Int,
    val matrixRowMajor: List<Double>?,
)

internal fun CameraFrameMetadata.toPts03AnalysisGeometry(): Pts03AnalysisGeometry {
    val left = cropRectLeftPx
    val top = cropRectTopPx
    val right = cropRectRightPx
    val bottom = cropRectBottomPx
    return Pts03AnalysisGeometry(
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        // CameraFrameMetadata guarantees the crop fields are all-or-nothing.
        cropRect =
            if (left != null && top != null && right != null &&
                bottom != null
            ) {
                Pts03IntRect(left, top, right, bottom)
            } else {
                null
            },
        rotationDegrees = rotationDegrees,
        matrixRowMajor = sensorToBufferTransform?.rowMajor(),
    )
}

internal fun SensorToBufferMatrix3.rowMajor(): List<Double> = listOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)

/**
 * One matched frame: the `ImageProxy` geometry, the `CaptureResult` with the identical sensor timestamp,
 * and the camera attribution computed for exactly this frame.
 */
internal data class Pts03FrameTruthRecord(
    val frameIndex: Long,
    val sensorTimestampNanos: Long,
    val lighting: Pts03LightingLabel,
    val geometry: Pts03AnalysisGeometry,
    val captureResult: SkyCaptureResultSnapshot,
    val attribution: Pts03FrameCameraAttribution,
)

/**
 * A change of the top-level logical active physical ID between two **reported** values. Frames that do not
 * report the ID are gaps, not identities, so they never start or end a transition. [frameIndex],
 * [sensorTimestampNanos] and [lighting] belong to the first frame that reports [toActivePhysicalCameraId].
 */
internal data class Pts03PhysicalIdTransition(
    val frameIndex: Long,
    val sensorTimestampNanos: Long,
    val fromActivePhysicalCameraId: String,
    val toActivePhysicalCameraId: String,
    val lighting: Pts03LightingLabel,
)

/**
 * Group A session summary, folded incrementally so it covers **every** matched frame even though only a
 * bounded window of raw records is retained.
 *
 * Two kinds of key are kept apart:
 * - `*ByLogicalActivePhysicalId`: the top-level `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`
 *   ([PTS03_ACTIVE_ID_NOT_REPORTED_KEY] when absent). The A1 identity observation; in A2 a diagnostic.
 * - `*ByProducingPhysicalId`: dynamic metadata aggregated under the frame's attributed producing camera,
 *   only from the attributed [Pts03FrameCameraAttribution.dynamicTruth] ([PTS03_PRODUCER_UNKNOWN_KEY] when
 *   the producer is unknown). In A2 a frame without a usable physical result contributes nothing here.
 */
internal data class Pts03IdentitySummary(
    val matchedFrameCount: Long = 0,
    val framesByLogicalActivePhysicalId: Map<String, Long> = emptyMap(),
    val framesWithNullActivePhysicalId: Long = 0,
    val activePhysicalIdAvailabilityCounts: Map<Pts03KeyAvailability, Long> = emptyMap(),
    val framesByLightingAndLogicalActivePhysicalId: Map<Pts03LightingLabel, Map<String, Long>> = emptyMap(),
    val transitionCount: Long = 0,
    /** The first [MAX_TRANSITIONS] transitions, in order. [transitionCount] counts all of them. */
    val transitions: List<Pts03PhysicalIdTransition> = emptyList(),
    /** The last **reported** logical active ID; frames that do not report it leave this unchanged. */
    val lastReportedActivePhysicalCameraId: String? = null,
    val dynamicMetadataSourceCounts: Map<Pts03DynamicMetadataSource, Long> = emptyMap(),
    /** A2: status of the requested ID's physical result per frame. Empty in A1. */
    val physicalResultStatusCounts: Map<Pts03PhysicalResultStatus, Long> = emptyMap(),
    /** A2: [Pts03PhysicalResultObservation] per frame. Empty in A1. */
    val physicalResultObservationCounts: Map<Pts03PhysicalResultObservation, Long> = emptyMap(),
    /** Which physical IDs appeared in each frame's physical-result map (any session class). */
    val framesByPhysicalResultCameraId: Map<String, Long> = emptyMap(),
    val framesByProducingPhysicalId: Map<String, Long> = emptyMap(),
    val focalLengthsMmByProducingPhysicalId: Map<String, List<Float>> = emptyMap(),
    val intrinsicsByProducingPhysicalId: Map<String, List<List<Float>>> = emptyMap(),
    val cropRegionsByProducingPhysicalId: Map<String, List<Pts03IntRect>> = emptyMap(),
    val zoomRatiosByProducingPhysicalId: Map<String, List<Float>> = emptyMap(),
    val activePhysicalSensorCropRegionsByProducingPhysicalId: Map<String, List<Pts03IntRect>> = emptyMap(),
    val effectiveDistortionModeCountsByProducingPhysicalId: Map<String, Map<String, Long>> = emptyMap(),
    val afStateCountsByProducingPhysicalId: Map<String, Map<String, Long>> = emptyMap(),
) {
    fun plus(record: Pts03FrameTruthRecord): Pts03IdentitySummary {
        val logical = record.captureResult.logicalTruth
        val id = logical.activePhysicalCameraId
        val logicalKey = id ?: PTS03_ACTIVE_ID_NOT_REPORTED_KEY
        val previous = lastReportedActivePhysicalCameraId
        val transition =
            if (id != null && previous != null && id != previous) {
                Pts03PhysicalIdTransition(record.frameIndex, record.sensorTimestampNanos, previous, id, record.lighting)
            } else {
                null
            }

        val attribution = record.attribution
        val producerKey = attribution.producingPhysicalCameraId ?: PTS03_PRODUCER_UNKNOWN_KEY
        val dynamic = attribution.dynamicTruth
        var next =
            copy(
                matchedFrameCount = matchedFrameCount + 1,
                framesByLogicalActivePhysicalId = framesByLogicalActivePhysicalId.increment(logicalKey),
                framesWithNullActivePhysicalId = framesWithNullActivePhysicalId + if (id == null) 1 else 0,
                activePhysicalIdAvailabilityCounts =
                    activePhysicalIdAvailabilityCounts.increment(logical.activePhysicalCameraIdAvailability),
                framesByLightingAndLogicalActivePhysicalId =
                    framesByLightingAndLogicalActivePhysicalId +
                        (
                            record.lighting to
                                (framesByLightingAndLogicalActivePhysicalId[record.lighting] ?: emptyMap())
                                    .increment(logicalKey)
                        ),
                transitionCount = transitionCount + if (transition != null) 1 else 0,
                transitions =
                    if (transition != null &&
                        transitions.size < MAX_TRANSITIONS
                    ) {
                        transitions + transition
                    } else {
                        transitions
                    },
                lastReportedActivePhysicalCameraId = id ?: previous,
                dynamicMetadataSourceCounts = dynamicMetadataSourceCounts.increment(attribution.dynamicMetadataSource),
                physicalResultStatusCounts =
                    attribution.physicalResultStatus?.let { physicalResultStatusCounts.increment(it) }
                        ?: physicalResultStatusCounts,
                physicalResultObservationCounts =
                    attribution.physicalResultObservation?.let { physicalResultObservationCounts.increment(it) }
                        ?: physicalResultObservationCounts,
                framesByPhysicalResultCameraId =
                    record.captureResult.physicalResultsByCameraId.keys
                        .fold(framesByPhysicalResultCameraId) { acc, k -> acc.increment(k) },
                framesByProducingPhysicalId = framesByProducingPhysicalId.increment(producerKey),
            )
        if (dynamic != null) {
            next =
                next.copy(
                    focalLengthsMmByProducingPhysicalId =
                        focalLengthsMmByProducingPhysicalId.addDistinct(producerKey, dynamic.lensFocalLengthMm),
                    intrinsicsByProducingPhysicalId =
                        intrinsicsByProducingPhysicalId.addDistinct(producerKey, dynamic.lensIntrinsicCalibration),
                    cropRegionsByProducingPhysicalId =
                        cropRegionsByProducingPhysicalId.addDistinct(producerKey, dynamic.scalerCropRegion),
                    zoomRatiosByProducingPhysicalId =
                        zoomRatiosByProducingPhysicalId.addDistinct(producerKey, dynamic.controlZoomRatio),
                    activePhysicalSensorCropRegionsByProducingPhysicalId =
                        activePhysicalSensorCropRegionsByProducingPhysicalId.addDistinct(
                            producerKey,
                            dynamic.activePhysicalSensorCropRegion,
                        ),
                    afStateCountsByProducingPhysicalId =
                        afStateCountsByProducingPhysicalId.incrementNested(
                            producerKey,
                            dynamic.controlAfState?.name ?: "NOT_REPORTED",
                        ),
                )
        }
        return next.copy(
            effectiveDistortionModeCountsByProducingPhysicalId =
                effectiveDistortionModeCountsByProducingPhysicalId.incrementNested(
                    producerKey,
                    attribution.effectiveDistortionMode().label,
                ),
        )
    }

    companion object {
        const val MAX_TRANSITIONS = 256

        /** Distinct values kept per physical ID; a value set that grows beyond this is itself the finding. */
        const val MAX_DISTINCT_VALUES_PER_ID = 16
    }
}

private fun <K> Map<K, Long>.increment(key: K): Map<K, Long> = this + (key to ((this[key] ?: 0L) + 1L))

private fun Map<String, Map<String, Long>>.incrementNested(
    key: String,
    inner: String,
): Map<String, Map<String, Long>> = this + (key to (this[key] ?: emptyMap()).increment(inner))

private fun <V : Any> Map<String, List<V>>.addDistinct(
    key: String,
    value: V?,
): Map<String, List<V>> {
    if (value == null) return this
    val existing = this[key] ?: emptyList()
    if (value in existing || existing.size >= Pts03IdentitySummary.MAX_DISTINCT_VALUES_PER_ID) return this
    return this + (key to existing + value)
}

/**
 * A2 session-level physical-result status for the requested ID. Deliberately says nothing about the
 * top-level logical active ID, which cannot confirm or contradict a physical-camera-specific output.
 */
internal enum class Pts03ExplicitPhysicalResultStatus {
    NOT_APPLICABLE_LOGICAL_SESSION,
    NO_MATCHED_FRAMES,

    /** Every matched frame had a timestamp-matched physical result for the requested ID. */
    PHYSICAL_RESULT_PRESENT,

    /** Some frames had one, others did not (any other status). */
    PHYSICAL_RESULT_PARTIALLY_PRESENT,

    /** No frame had a physical result entry for the requested ID. */
    PHYSICAL_RESULT_NOT_REPORTED,

    /** Entries existed but none had a matching `SENSOR_TIMESTAMP` (mismatched or missing). */
    PHYSICAL_RESULT_TIMESTAMP_MISMATCH,
}

internal fun summarizePts03ExplicitPhysicalResults(
    sessionClass: Pts03SessionClass,
    requestedPhysicalCameraId: String?,
    summary: Pts03IdentitySummary,
): Pts03ExplicitPhysicalResultStatus {
    if (sessionClass == Pts03SessionClass.LOGICAL_UNPINNED || requestedPhysicalCameraId == null) {
        return Pts03ExplicitPhysicalResultStatus.NOT_APPLICABLE_LOGICAL_SESSION
    }
    if (summary.matchedFrameCount == 0L) return Pts03ExplicitPhysicalResultStatus.NO_MATCHED_FRAMES
    val counts = summary.physicalResultStatusCounts
    val matched = counts[Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED] ?: 0L
    val notReported = counts[Pts03PhysicalResultStatus.NOT_REPORTED] ?: 0L
    return when {
        matched == summary.matchedFrameCount -> Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_PRESENT
        matched > 0L -> Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_PARTIALLY_PRESENT
        notReported == summary.matchedFrameCount -> Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_NOT_REPORTED
        else -> Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_TIMESTAMP_MISMATCH
    }
}

/**
 * A2 session-level device-result discriminator: the per-frame [Pts03PhysicalResultObservation] when every
 * matched frame agrees, [Pts03PhysicalResultObservation.MIXED] otherwise. Like the per-frame value, it never
 * promotes the top-level logical active ID to the producer of the analysed stream.
 */
internal fun summarizePts03PhysicalResultObservation(
    sessionClass: Pts03SessionClass,
    requestedPhysicalCameraId: String?,
    summary: Pts03IdentitySummary,
): Pts03PhysicalResultObservation {
    if (sessionClass == Pts03SessionClass.LOGICAL_UNPINNED || requestedPhysicalCameraId == null) {
        return Pts03PhysicalResultObservation.NOT_APPLICABLE_LOGICAL_SESSION
    }
    if (summary.matchedFrameCount == 0L) return Pts03PhysicalResultObservation.NO_MATCHED_FRAMES
    val observed = summary.physicalResultObservationCounts.filterValues { it > 0L }.keys
    return observed.singleOrNull()?.takeIf {
        summary.physicalResultObservationCounts[it] == summary.matchedFrameCount
    } ?: Pts03PhysicalResultObservation.MIXED
}

/**
 * One operator-captured printed-target placement: the frozen frame-content snapshot (which carries its own
 * exact-joined `CaptureResult` and the lighting label of its own frame), the camera attribution of that
 * frame, the Group B evidence computed from it, and the residual points Group C measures. Nothing here is
 * recomputed from a later frame or read from mutable session state.
 */
internal data class Pts03TargetEvidenceCapture(
    val captureIndex: Int,
    /** Always [FrameContentCorrespondenceSnapshot.lightingAtCapture] — never the session's current label. */
    val lighting: Pts03LightingLabel,
    val requestedDistortionMode: Pts03DistortionModeRequest,
    val snapshot: FrameContentCorrespondenceSnapshot,
    val attribution: Pts03FrameCameraAttribution,
    val domainEvidence: Pts03ProjectionDomainEvidence,
    val residualPoints: List<Pts03TargetResidualPoint>,
) {
    /** The analysed stream's mode from the attributed dynamic metadata; never the logical mode in A2. */
    val effectiveDistortionMode: Pts03EffectiveDistortionMode
        get() = attribution.effectiveDistortionMode()
}

/**
 * The residual points Group C uses from one snapshot: accepted residuals of the pose-reference hypothesis
 * (whose pose was fit through the same pinhole K), measured from that hypothesis's principal point.
 */
internal fun pts03ResidualPointsOf(snapshot: FrameContentCorrespondenceSnapshot): List<Pts03TargetResidualPoint> {
    val reference = snapshot.hypotheses.firstOrNull { it.id == snapshot.poseReferenceHypothesis } ?: return emptyList()
    val intrinsics =
        (reference.result as? FrameContentHypothesisIntrinsicsResult.Available)?.intrinsics ?: return emptyList()
    val halfDiagonal = 0.5 * kotlin.math.hypot(snapshot.bufferWidthPx.toDouble(), snapshot.bufferHeightPx.toDouble())
    return snapshot.residualsByHypothesis[snapshot.poseReferenceHypothesis]
        .orEmpty()
        .filterIsInstance<FrameContentPointResidual.Accepted>()
        .map {
            Pts03TargetResidualPoint(
                placement = snapshot.targetPlacementLabel,
                generation = snapshot.generation,
                observedXPx = it.observedXPx,
                observedYPx = it.observedYPx,
                predictedXPx = it.predictedXPx,
                predictedYPx = it.predictedYPx,
                centerXPx = intrinsics.bufferCxPx,
                centerYPx = intrinsics.bufferCyPx,
                normalizationRadiusPx = halfDiagonal,
            )
        }
}

/** The PTS-03 evidence of one bind. */
internal data class Pts03TruthSessionState(
    val sessionId: String,
    val sessionClass: Pts03SessionClass,
    val requestedPhysicalCameraId: String?,
    val requestedDistortionMode: Pts03DistortionModeRequest,
    val requestedAnalysisWidthPx: Int?,
    val requestedAnalysisHeightPx: Int?,
    val lighting: Pts03LightingLabel = Pts03LightingLabel.UNSPECIFIED,
    val logicalCameraId: String? = null,
    val characteristics: Pts03CameraCharacteristicsSet? = null,
    val streamConfiguration: Pts03StreamConfiguration? = null,
    val identity: Pts03IdentitySummary = Pts03IdentitySummary(),
    val joinStatistics: SkyJoinStatistics = SkyJoinStatistics.EMPTY,
    /**
     * `true` once the join was finalized (offers stopped, pending entries drained into the pending-at-stop
     * counts, statistics frozen). After that the statistics and frame records never change again.
     */
    val finalized: Boolean = false,
    val analysisGeometryCounts: Map<Pts03AnalysisGeometry, Long> = emptyMap(),
    /** The first [MAX_HEAD_RECORDS] matched frames, kept so the session's start is always inspectable. */
    val headRecords: List<Pts03FrameTruthRecord> = emptyList(),
    /** The most recent [MAX_TAIL_RECORDS] matched frames after the head. */
    val tailRecords: List<Pts03FrameTruthRecord> = emptyList(),
    val evidenceCaptures: List<Pts03TargetEvidenceCapture> = emptyList(),
) {
    val totalFrameRecords: Long get() = identity.matchedFrameCount

    /**
     * The physical IDs this bind requests (selector, Preview interop, ImageAnalysis interop). Derived from
     * [requestedPhysicalCameraId] — `null` for A1 — so the bind and the export read the same request.
     * REQUESTED_BIND_CONFIGURATION, never a proven producer.
     */
    val physicalBindingRequest: Pts03PhysicalBindingRequest
        get() =
            Pts03PhysicalBindingRequest(
                requestedPhysicalCameraId.takeIf { sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL },
            )

    /** Frames folded into the summaries but not retained as raw records. */
    val omittedFrameRecords: Long get() = totalFrameRecords - headRecords.size - tailRecords.size

    companion object {
        const val MAX_HEAD_RECORDS = 60
        const val MAX_TAIL_RECORDS = 240
        const val MAX_EVIDENCE_CAPTURES = 64
    }
}

/** A stable, sortable session ID: `pts03-<startEpochMillis>-a<attemptId>-<class>-<camera>`. */
internal fun pts03SessionId(
    startedAtEpochMillis: Long,
    attemptId: Long,
    sessionClass: Pts03SessionClass,
    requestedPhysicalCameraId: String?,
): String =
    "pts03-$startedAtEpochMillis-a$attemptId-" +
        if (sessionClass ==
            Pts03SessionClass.LOGICAL_UNPINNED
        ) {
            "logical"
        } else {
            "phys${requestedPhysicalCameraId ?: "unknown"}"
        }

/** Attributes one exact-joined frame of this session. */
internal fun Pts03TruthSessionState.attribute(
    frameSensorTimestampNanos: Long,
    captureResult: SkyCaptureResultSnapshot,
): Pts03FrameCameraAttribution =
    attributePts03Frame(sessionClass, requestedPhysicalCameraId, frameSensorTimestampNanos, captureResult)

internal fun Pts03TruthSessionState.withMatchedFrame(
    frame: CameraFrameMetadata,
    captureResult: SkyCaptureResultSnapshot,
): Pts03TruthSessionState {
    require(captureResult.sensorTimestampNanos == frame.timestampNanos) {
        "a PTS-03 frame record requires the exact SENSOR_TIMESTAMP join; " +
            "frame=${frame.timestampNanos} result=${captureResult.sensorTimestampNanos}"
    }
    if (finalized) return this
    val geometry = frame.toPts03AnalysisGeometry()
    val record =
        Pts03FrameTruthRecord(
            frameIndex = identity.matchedFrameCount,
            sensorTimestampNanos = frame.timestampNanos,
            lighting = lighting,
            geometry = geometry,
            captureResult = captureResult,
            attribution = attribute(frame.timestampNanos, captureResult),
        )
    val (head, tail) =
        if (headRecords.size < Pts03TruthSessionState.MAX_HEAD_RECORDS) {
            (headRecords + record) to tailRecords
        } else {
            headRecords to (tailRecords + record).takeLast(Pts03TruthSessionState.MAX_TAIL_RECORDS)
        }
    val geometryCounts =
        if (geometry in analysisGeometryCounts || analysisGeometryCounts.size < MAX_DISTINCT_GEOMETRIES) {
            analysisGeometryCounts + (geometry to ((analysisGeometryCounts[geometry] ?: 0L) + 1L))
        } else {
            analysisGeometryCounts
        }
    return copy(
        identity = identity.plus(record),
        headRecords = head,
        tailRecords = tail,
        analysisGeometryCounts = geometryCounts,
    )
}

private const val MAX_DISTINCT_GEOMETRIES = 32

/** Live (non-final) statistics; ignored once [Pts03TruthSessionState.finalized]. */
internal fun Pts03TruthSessionState.withJoinStatistics(statistics: SkyJoinStatistics): Pts03TruthSessionState =
    if (finalized) this else copy(joinStatistics = statistics)

/**
 * Freezes the final join statistics (already drained by [SkyExposureJoin.finalizeJoin]). Idempotent: a
 * second finalize keeps the first statistics, so pending entries are never counted twice.
 */
internal fun Pts03TruthSessionState.finalizedWith(finalStatistics: SkyJoinStatistics): Pts03TruthSessionState =
    if (finalized) this else copy(joinStatistics = finalStatistics, finalized = true)

/**
 * Appends one evidence capture built entirely from [snapshot] (which carries its own joined result and its
 * own frame's lighting label). A snapshot with no joined `CaptureResult` is refused: its camera
 * attribution would be unknown.
 *
 * Static characteristics are selected by the attributed **producing** camera (A2: the requested ID; A1:
 * the reported logical active ID). Dynamic metadata comes only from the attribution; when it is
 * unresolved the Group C mode is [Pts03EffectiveDistortionMode.PhysicalResultUnavailable].
 */
internal fun Pts03TruthSessionState.withEvidenceCapture(
    snapshot: FrameContentCorrespondenceSnapshot,
): Pts03TruthSessionState {
    val captureResult = snapshot.captureResult
    if (captureResult == null || evidenceCaptures.size >= Pts03TruthSessionState.MAX_EVIDENCE_CAPTURES) return this
    val frameTimestamp = captureResult.sensorTimestampNanos ?: return this
    val attribution = attribute(frameTimestamp, captureResult)
    val producing = attribution.producingPhysicalCameraId
    val physical =
        producing?.let { id ->
            characteristics?.forCameraId(id)?.snapshot
                ?: snapshot.selectedPhysicalCharacteristics.takeIf { it.cameraId == id }
        }
    val domain =
        buildPts03ProjectionDomainEvidence(
            matrix = snapshot.sensorToBufferTransformMatrix,
            bufferWidthPx = snapshot.bufferWidthPx,
            bufferHeightPx = snapshot.bufferHeightPx,
            logical = characteristics?.logical?.snapshot ?: snapshot.openedLogicalCharacteristics,
            physical = physical,
            producingPhysicalCameraId = producing,
            logicalActivePhysicalCameraId = attribution.logicalActivePhysicalCameraId,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            frameContentVerdict = snapshot.verdict.verdict,
        )
    val capture =
        Pts03TargetEvidenceCapture(
            captureIndex = evidenceCaptures.size,
            lighting = snapshot.lightingAtCapture,
            requestedDistortionMode = requestedDistortionMode,
            snapshot = snapshot,
            attribution = attribution,
            domainEvidence = domain,
            residualPoints = pts03ResidualPointsOf(snapshot),
        )
    return copy(evidenceCaptures = evidenceCaptures + capture)
}

/**
 * Group C residual measurements per (producing camera, effective mode), across every evidence capture.
 * Captures whose producing camera is unknown are grouped under [PTS03_PRODUCER_UNKNOWN_KEY].
 */
internal fun Pts03TruthSessionState.distortionChains(
    thresholds: Pts03ResidualDistortionThresholds = Pts03ResidualDistortionThresholds(),
): List<Pts03DistortionChain> =
    evidenceCaptures
        .groupBy {
            (it.attribution.producingPhysicalCameraId ?: PTS03_PRODUCER_UNKNOWN_KEY) to
                it.effectiveDistortionMode.label
        }.toSortedMap(compareBy({ it.first }, { it.second }))
        .map { (key, captures) ->
            val outcomes = captures.map { it.domainEvidence.outcome }.distinct()
            buildPts03DistortionChain(
                cameraId = key.first,
                requested = requestedDistortionMode,
                effectiveModes = captures.map { it.effectiveDistortionMode },
                characteristics = characteristics?.forCameraId(key.first),
                residual = measurePts03ResidualDistortion(captures.flatMap { it.residualPoints }, thresholds),
                matrixDomainOutcome =
                    when {
                        Pts03EvidenceOutcome.MISMATCH in outcomes -> Pts03EvidenceOutcome.MISMATCH
                        outcomes == listOf(Pts03EvidenceOutcome.PROVEN) -> Pts03EvidenceOutcome.PROVEN
                        else -> Pts03EvidenceOutcome.UNRESOLVED
                    },
            )
        }
