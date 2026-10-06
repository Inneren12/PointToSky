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
 * - [Pts03SessionClass.EXPLICIT_PHYSICAL] (A2): one declared child pinned with `setPhysicalCameraId`. Identity
 *   here is fixed by construction; the reported active ID can only *confirm* or *contradict* the pin.
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

/** One matched frame: the `ImageProxy` geometry and the `CaptureResult` with the identical sensor timestamp. */
internal data class Pts03FrameTruthRecord(
    val frameIndex: Long,
    val sensorTimestampNanos: Long,
    val lighting: Pts03LightingLabel,
    val geometry: Pts03AnalysisGeometry,
    val captureResult: SkyCaptureResultSnapshot,
)

internal data class Pts03PhysicalIdTransition(
    val frameIndex: Long,
    val sensorTimestampNanos: Long,
    val fromActivePhysicalCameraId: String?,
    val toActivePhysicalCameraId: String?,
    val lighting: Pts03LightingLabel,
)

/**
 * Group A session summary, folded incrementally so it covers **every** matched frame even though only a
 * bounded window of raw records is retained. Keys use [PTS03_ACTIVE_ID_NOT_REPORTED_KEY] for frames whose
 * result carried no active physical ID.
 */
internal data class Pts03IdentitySummary(
    val matchedFrameCount: Long = 0,
    val framesByActivePhysicalId: Map<String, Long> = emptyMap(),
    val framesWithNullActivePhysicalId: Long = 0,
    val activePhysicalIdAvailabilityCounts: Map<Pts03KeyAvailability, Long> = emptyMap(),
    val framesByLightingAndActivePhysicalId: Map<Pts03LightingLabel, Map<String, Long>> = emptyMap(),
    val transitionCount: Long = 0,
    /** The first [MAX_TRANSITIONS] transitions, in order. [transitionCount] counts all of them. */
    val transitions: List<Pts03PhysicalIdTransition> = emptyList(),
    val focalLengthsMmByActivePhysicalId: Map<String, List<Float>> = emptyMap(),
    val intrinsicsByActivePhysicalId: Map<String, List<List<Float>>> = emptyMap(),
    val cropRegionsByActivePhysicalId: Map<String, List<Pts03IntRect>> = emptyMap(),
    val zoomRatiosByActivePhysicalId: Map<String, List<Float>> = emptyMap(),
    val effectiveDistortionModeCountsByActivePhysicalId: Map<String, Map<String, Long>> = emptyMap(),
    val afStateCountsByActivePhysicalId: Map<String, Map<String, Long>> = emptyMap(),
    val hasPrevious: Boolean = false,
    val previousActivePhysicalCameraId: String? = null,
) {
    fun plus(record: Pts03FrameTruthRecord): Pts03IdentitySummary {
        val truth = record.captureResult.cameraTruth
        val id = truth.activePhysicalCameraId
        val key = id ?: PTS03_ACTIVE_ID_NOT_REPORTED_KEY
        val isTransition = hasPrevious && previousActivePhysicalCameraId != id
        val transition =
            Pts03PhysicalIdTransition(
                record.frameIndex,
                record.sensorTimestampNanos,
                previousActivePhysicalCameraId,
                id,
                record.lighting,
            )
        return copy(
            matchedFrameCount = matchedFrameCount + 1,
            framesByActivePhysicalId = framesByActivePhysicalId.increment(key),
            framesWithNullActivePhysicalId = framesWithNullActivePhysicalId + if (id == null) 1 else 0,
            activePhysicalIdAvailabilityCounts =
                activePhysicalIdAvailabilityCounts.increment(
                    truth.activePhysicalCameraIdAvailability,
                ),
            framesByLightingAndActivePhysicalId =
                framesByLightingAndActivePhysicalId +
                    (
                        record.lighting to
                            (framesByLightingAndActivePhysicalId[record.lighting] ?: emptyMap()).increment(key)
                    ),
            transitionCount = transitionCount + if (isTransition) 1 else 0,
            transitions =
                if (isTransition &&
                    transitions.size < MAX_TRANSITIONS
                ) {
                    transitions + transition
                } else {
                    transitions
                },
            focalLengthsMmByActivePhysicalId =
                focalLengthsMmByActivePhysicalId.addDistinct(
                    key,
                    truth.lensFocalLengthMm,
                ),
            intrinsicsByActivePhysicalId =
                intrinsicsByActivePhysicalId.addDistinct(
                    key,
                    truth.lensIntrinsicCalibration,
                ),
            cropRegionsByActivePhysicalId =
                cropRegionsByActivePhysicalId.addDistinct(
                    key,
                    truth.scalerCropRegion,
                ),
            zoomRatiosByActivePhysicalId = zoomRatiosByActivePhysicalId.addDistinct(key, truth.controlZoomRatio),
            effectiveDistortionModeCountsByActivePhysicalId =
                effectiveDistortionModeCountsByActivePhysicalId.incrementNested(
                    key,
                    truth.effectiveDistortionMode().label,
                ),
            afStateCountsByActivePhysicalId =
                afStateCountsByActivePhysicalId.incrementNested(key, truth.controlAfState?.name ?: "NOT_REPORTED"),
            hasPrevious = true,
            previousActivePhysicalCameraId = id,
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

/** For an explicit-physical session: does the reported active ID confirm the pin? */
internal enum class Pts03ExplicitIdentityConfirmation {
    NOT_APPLICABLE_LOGICAL_SESSION,
    NO_MATCHED_FRAMES,
    ACTIVE_PHYSICAL_ID_NOT_REPORTED,
    CONTRADICTED_BY_ACTIVE_PHYSICAL_ID,
    PARTIALLY_CONFIRMED_SOME_FRAMES_UNREPORTED,
    CONFIRMED_BY_ACTIVE_PHYSICAL_ID,
}

internal fun confirmPts03ExplicitIdentity(
    sessionClass: Pts03SessionClass,
    requestedPhysicalCameraId: String?,
    summary: Pts03IdentitySummary,
): Pts03ExplicitIdentityConfirmation {
    if (sessionClass == Pts03SessionClass.LOGICAL_UNPINNED || requestedPhysicalCameraId == null) {
        return Pts03ExplicitIdentityConfirmation.NOT_APPLICABLE_LOGICAL_SESSION
    }
    if (summary.matchedFrameCount == 0L) return Pts03ExplicitIdentityConfirmation.NO_MATCHED_FRAMES
    val reported = summary.framesByActivePhysicalId.keys - PTS03_ACTIVE_ID_NOT_REPORTED_KEY
    return when {
        reported.isEmpty() -> Pts03ExplicitIdentityConfirmation.ACTIVE_PHYSICAL_ID_NOT_REPORTED
        reported.any {
            it != requestedPhysicalCameraId
        } -> Pts03ExplicitIdentityConfirmation.CONTRADICTED_BY_ACTIVE_PHYSICAL_ID
        summary.framesWithNullActivePhysicalId > 0 ->
            Pts03ExplicitIdentityConfirmation.PARTIALLY_CONFIRMED_SOME_FRAMES_UNREPORTED
        else -> Pts03ExplicitIdentityConfirmation.CONFIRMED_BY_ACTIVE_PHYSICAL_ID
    }
}

/**
 * One operator-captured printed-target placement: the frozen frame-content snapshot (which carries its own
 * exact-joined `CaptureResult`), the Group B evidence computed from that same frame, and the residual
 * points Group C measures. Nothing here is recomputed from a later frame.
 */
internal data class Pts03TargetEvidenceCapture(
    val captureIndex: Int,
    val lighting: Pts03LightingLabel,
    val requestedDistortionMode: Pts03DistortionModeRequest,
    val snapshot: FrameContentCorrespondenceSnapshot,
    val domainEvidence: Pts03ProjectionDomainEvidence,
    val residualPoints: List<Pts03TargetResidualPoint>,
) {
    val effectiveDistortionMode: Pts03EffectiveDistortionMode
        get() =
            snapshot.captureResult?.cameraTruth?.effectiveDistortionMode()
                ?: Pts03EffectiveDistortionMode.NotReported
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
    val analysisGeometryCounts: Map<Pts03AnalysisGeometry, Long> = emptyMap(),
    /** The first [MAX_HEAD_RECORDS] matched frames, kept so the session's start is always inspectable. */
    val headRecords: List<Pts03FrameTruthRecord> = emptyList(),
    /** The most recent [MAX_TAIL_RECORDS] matched frames after the head. */
    val tailRecords: List<Pts03FrameTruthRecord> = emptyList(),
    val evidenceCaptures: List<Pts03TargetEvidenceCapture> = emptyList(),
) {
    val totalFrameRecords: Long get() = identity.matchedFrameCount

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
        (
            if (sessionClass ==
                Pts03SessionClass.LOGICAL_UNPINNED
            ) {
                "logical"
            } else {
                "phys${requestedPhysicalCameraId ?: "unknown"}"
            }
        )

internal fun Pts03TruthSessionState.withMatchedFrame(
    frame: CameraFrameMetadata,
    captureResult: SkyCaptureResultSnapshot,
): Pts03TruthSessionState {
    require(captureResult.sensorTimestampNanos == frame.timestampNanos) {
        "a PTS-03 frame record requires the exact SENSOR_TIMESTAMP join; " +
            "frame=${frame.timestampNanos} result=${captureResult.sensorTimestampNanos}"
    }
    val geometry = frame.toPts03AnalysisGeometry()
    val record =
        Pts03FrameTruthRecord(
            frameIndex = identity.matchedFrameCount,
            sensorTimestampNanos = frame.timestampNanos,
            lighting = lighting,
            geometry = geometry,
            captureResult = captureResult,
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

/**
 * Appends one evidence capture built entirely from [snapshot] (which carries its own joined result).
 * A snapshot with no joined `CaptureResult` is refused: its distortion mode and active ID would be unknown.
 */
internal fun Pts03TruthSessionState.withEvidenceCapture(
    snapshot: FrameContentCorrespondenceSnapshot,
): Pts03TruthSessionState {
    if (snapshot.captureResult == null ||
        evidenceCaptures.size >= Pts03TruthSessionState.MAX_EVIDENCE_CAPTURES
    ) {
        return this
    }
    val activeId = snapshot.captureResult.cameraTruth.activePhysicalCameraId
    val physical =
        characteristics?.forCameraId(snapshot.provenance.physicalCameraId)?.snapshot
            ?: snapshot.selectedPhysicalCharacteristics
    val domain =
        buildPts03ProjectionDomainEvidence(
            matrix = snapshot.sensorToBufferTransformMatrix,
            bufferWidthPx = snapshot.bufferWidthPx,
            bufferHeightPx = snapshot.bufferHeightPx,
            logical = characteristics?.logical?.snapshot ?: snapshot.openedLogicalCharacteristics,
            physical = physical,
            activePhysicalCameraId = activeId,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            frameContentVerdict = snapshot.verdict.verdict,
        )
    val capture =
        Pts03TargetEvidenceCapture(
            captureIndex = evidenceCaptures.size,
            lighting = lighting,
            requestedDistortionMode = requestedDistortionMode,
            snapshot = snapshot,
            domainEvidence = domain,
            residualPoints = pts03ResidualPointsOf(snapshot),
        )
    return copy(evidenceCaptures = evidenceCaptures + capture)
}

/** Group C residual measurements per (producing camera, effective mode), across every evidence capture. */
internal fun Pts03TruthSessionState.distortionChains(
    thresholds: Pts03ResidualDistortionThresholds = Pts03ResidualDistortionThresholds(),
): List<Pts03DistortionChain> =
    evidenceCaptures
        .groupBy { (it.snapshot.provenance.physicalCameraId) to it.effectiveDistortionMode.label }
        .toSortedMap(compareBy({ it.first }, { it.second }))
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
