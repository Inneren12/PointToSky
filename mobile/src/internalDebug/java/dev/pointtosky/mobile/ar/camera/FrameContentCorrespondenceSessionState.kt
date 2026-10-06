package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). One attempt's session state,
 * mirroring [ExperimentSessionState]'s own generation-scoped no-op-unless-current-attempt pattern
 * exactly (task §7/§8's "late callbacks from an older attempt are ignored" / "a new attempt clears the
 * old snapshot" requirements) — every reducer below is a no-op unless [attemptId] matches and the
 * session is not [isTerminallyFailed].
 */
internal data class FrameContentExperimentSessionState(
    val attemptId: Long,
    val physicalCameraId: String,
    val bindingResolution: PhysicalCameraBindingResolution? = null,
    val explicitBindFailureReason: String? = null,
    val openedLogicalCamera: OpenedLogicalCameraSnapshotResolution? = null,
    val zoomTargetRatio: Float? = null,
    val observedZoomRatio: Float? = null,
    val latestFrame: CameraFrameMetadata? = null,
    val latestDetection: FrameContentDetectionResult? = null,
    val framesObserved: Long = 0L,
    val requestedAnalysisResolutionWidthPx: Int? = null,
    val requestedAnalysisResolutionHeightPx: Int? = null,
    val requestedAnalysisResolutionFamily: AnalysisResolutionFamily? = null,
    val targetPlacementLabel: TargetPlacementLabel = TargetPlacementLabel.CENTER,
    val distanceLabelMm: Double? = null,
    val latestSnapshot: FrameContentCorrespondenceSnapshot? = null,
    /** PTS-03: the `CaptureResult` exact-joined to [latestFrame]; always replaced together with it. */
    val latestCaptureResult: SkyCaptureResultSnapshot? = null,
    /** PTS-03: the lighting label active when [latestFrame] arrived; replaced together with it. */
    val latestFrameLighting: Pts03LightingLabel = Pts03LightingLabel.UNSPECIFIED,
    /** PTS-03: [Pts03SessionClass.LOGICAL_UNPINNED] binds the logical camera with no physical/zoom pin (A1). */
    val sessionClass: Pts03SessionClass = Pts03SessionClass.EXPLICIT_PHYSICAL,
    /** PTS-03 camera-truth evidence for this attempt (this bind) only. */
    val pts03: Pts03TruthSessionState =
        Pts03TruthSessionState(
            sessionId = pts03SessionId(0L, attemptId, sessionClass, physicalCameraId),
            sessionClass = sessionClass,
            requestedPhysicalCameraId = physicalCameraId.takeIf { sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL },
            requestedDistortionMode = Pts03DistortionModeRequest.DEVICE_DEFAULT,
            requestedAnalysisWidthPx = requestedAnalysisResolutionWidthPx,
            requestedAnalysisHeightPx = requestedAnalysisResolutionHeightPx,
        ),
) {
    val isTerminallyFailed: Boolean get() = explicitBindFailureReason != null
}

/** Target-placement labels the device workflow offers (task §7). */
internal enum class TargetPlacementLabel {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal fun initialFrameContentExperimentSessionState(
    attemptId: Long,
    physicalCameraId: String,
    requestedAnalysisResolution: AnalysisResolutionCandidate? = null,
    pts03Request: Pts03AttemptRequest = Pts03AttemptRequest(),
): FrameContentExperimentSessionState {
    val sessionClass =
        if (physicalCameraId == PTS03_LOGICAL_UNPINNED_CANDIDATE) Pts03SessionClass.LOGICAL_UNPINNED else Pts03SessionClass.EXPLICIT_PHYSICAL
    val requestedPhysicalId = physicalCameraId.takeIf { sessionClass == Pts03SessionClass.EXPLICIT_PHYSICAL }
    return FrameContentExperimentSessionState(
        attemptId = attemptId,
        physicalCameraId = physicalCameraId,
        requestedAnalysisResolutionWidthPx = requestedAnalysisResolution?.widthPx,
        requestedAnalysisResolutionHeightPx = requestedAnalysisResolution?.heightPx,
        requestedAnalysisResolutionFamily = requestedAnalysisResolution?.family,
        sessionClass = sessionClass,
        pts03 =
            Pts03TruthSessionState(
                sessionId = pts03SessionId(pts03Request.startedAtEpochMillis, attemptId, sessionClass, requestedPhysicalId),
                sessionClass = sessionClass,
                requestedPhysicalCameraId = requestedPhysicalId,
                requestedDistortionMode = pts03Request.distortionMode,
                requestedAnalysisWidthPx = requestedAnalysisResolution?.widthPx,
                requestedAnalysisHeightPx = requestedAnalysisResolution?.heightPx,
                lighting = pts03Request.lighting,
            ),
    )
}

/** The PTS-03 choices fixed for one attempt (one bind): they are bind-time decisions. */
internal data class Pts03AttemptRequest(
    val distortionMode: Pts03DistortionModeRequest = Pts03DistortionModeRequest.DEVICE_DEFAULT,
    val lighting: Pts03LightingLabel = Pts03LightingLabel.UNSPECIFIED,
    val startedAtEpochMillis: Long = 0L,
)

internal fun FrameContentExperimentSessionState.reduceBindingResolved(
    attemptId: Long,
    dualBinding: DualBasisBindingResolution,
    zoomTargetRatio: Float?,
    observedZoomRatio: Float?,
    capturedAtEpochMillis: Long,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(
        bindingResolution = dualBinding.binding,
        openedLogicalCamera = dualBinding.openedLogicalCamera,
        zoomTargetRatio = zoomTargetRatio,
        observedZoomRatio = observedZoomRatio,
    ).recomputeSnapshot(capturedAtEpochMillis)
}

/**
 * PTS-03: the static characteristics (logical + every declared child) and CameraX's post-bind stream
 * configuration for this attempt. Separate from [reduceBindingResolved] because the A1 logical session has
 * no physical binding to resolve.
 */
internal fun FrameContentExperimentSessionState.reducePts03Bound(
    attemptId: Long,
    logicalCameraId: String?,
    characteristics: Pts03CameraCharacteristicsSet?,
    streamConfiguration: Pts03StreamConfiguration,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(
        pts03 =
            pts03.copy(
                logicalCameraId = logicalCameraId,
                characteristics = characteristics,
                streamConfiguration = streamConfiguration,
            ),
    )
}

/** PTS-03: the operator's lighting label; frozen into every later frame record of this attempt. */
internal fun FrameContentExperimentSessionState.reducePts03Lighting(
    attemptId: Long,
    lighting: Pts03LightingLabel,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(pts03 = pts03.copy(lighting = lighting))
}

/** PTS-03: join statistics as of the latest offer. Replaced wholesale; never merged across binds. */
internal fun FrameContentExperimentSessionState.reducePts03JoinStatistics(
    attemptId: Long,
    statistics: SkyJoinStatistics,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(pts03 = pts03.withJoinStatistics(statistics))
}

/**
 * PTS-03: the join was finalized (offers stopped, pending entries drained, statistics frozen). Idempotent —
 * see [Pts03TruthSessionState.finalizedWith]. The authoritative export is written only after this.
 */
internal fun FrameContentExperimentSessionState.reducePts03Finalized(
    attemptId: Long,
    finalStatistics: SkyJoinStatistics,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId) return this
    return copy(pts03 = pts03.finalizedWith(finalStatistics))
}

/**
 * PTS-03: appends [snapshot] — the exact snapshot the operator is looking at (frozen or live) — as one
 * printed-target evidence capture. A snapshot from another attempt is refused.
 */
internal fun FrameContentExperimentSessionState.reducePts03AddEvidence(
    attemptId: Long,
    snapshot: FrameContentCorrespondenceSnapshot,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed || snapshot.attemptId != this.attemptId) return this
    return copy(pts03 = pts03.withEvidenceCapture(snapshot))
}

internal fun FrameContentExperimentSessionState.reduceExplicitBindFailure(
    attemptId: Long,
    reason: String,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(explicitBindFailureReason = reason)
}

/** Applies a newly analyzed frame and its detection result together — this experiment's detector reads
 * pixel data unavailable from [CameraFrameMetadata] alone, so both must arrive from the same
 * `ImageProxy` at once (never combined from two different frames — task §2's "never mixes values from
 * different frames" constraint). */
internal fun FrameContentExperimentSessionState.reduceFrame(
    attemptId: Long,
    frame: CameraFrameMetadata,
    detection: FrameContentDetectionResult,
    capturedAtEpochMillis: Long,
    captureResult: SkyCaptureResultSnapshot? = null,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    // PTS-03: the frame, its detection and its exact-joined CaptureResult are replaced together, so
    // the snapshot below can never pair this frame's pixels with another frame's metadata.
    val joined = captureResult?.takeIf { it.sensorTimestampNanos == frame.timestampNanos }
    return copy(
        latestFrame = frame,
        latestDetection = detection,
        latestCaptureResult = joined,
        latestFrameLighting = pts03.lighting,
        framesObserved = framesObserved + 1,
        pts03 = if (joined != null) pts03.withMatchedFrame(frame, joined) else pts03,
    ).recomputeSnapshot(capturedAtEpochMillis)
}

/**
 * Applies a target-placement-label edit (task §3). Chosen, documented behavior for "editing
 * placement/distance after a snapshot already exists": **recompute a metadata-identical snapshot
 * immediately, using the exact same frame generation** — never silently show one label in the live UI
 * while a stale value would export in Copy/Share. This is a cheap metadata patch (the frame, detection,
 * pose, hypotheses, and residuals are all untouched — see [FrameContentCorrespondenceSnapshot.copy]
 * call below), not a full recompute; the snapshot's own [FrameContentCorrespondenceSnapshot.generation]
 * is intentionally unchanged, since the edit does not correspond to a new analyzed frame.
 */
internal fun FrameContentExperimentSessionState.reduceTargetPlacementLabel(
    attemptId: Long,
    label: TargetPlacementLabel,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(
        targetPlacementLabel = label,
        latestSnapshot = latestSnapshot?.copy(targetPlacementLabel = label),
    )
}

/** The [distanceLabelMm] analogue of [reduceTargetPlacementLabel] — same metadata-identical,
 * same-generation immediate-patch behavior. */
internal fun FrameContentExperimentSessionState.reduceDistanceLabel(
    attemptId: Long,
    distanceMm: Double?,
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(
        distanceLabelMm = distanceMm,
        latestSnapshot = latestSnapshot?.copy(distanceLabelMm = distanceMm),
    )
}

/** Recomputes [FrameContentExperimentSessionState.latestSnapshot] once a verified physical binding, a
 * frame, and a detection result are all present — order-independent, exactly like
 * [ExperimentSessionState]'s own `recomputeCam2cResult`. Either input missing leaves the snapshot
 * `null` ("awaiting"), never a guess or a stale carry-over from a previous frame. */
private fun FrameContentExperimentSessionState.recomputeSnapshot(capturedAtEpochMillis: Long): FrameContentExperimentSessionState {
    val binding = bindingResolution as? PhysicalCameraBindingResolution.Bound ?: return copy(latestSnapshot = null)
    val frame = latestFrame ?: return copy(latestSnapshot = null)
    val detection = latestDetection ?: return copy(latestSnapshot = null)
    val openedLogicalSnapshot = (openedLogicalCamera as? OpenedLogicalCameraSnapshotResolution.Captured)?.snapshot

    val snapshot =
        buildFrameContentCorrespondenceSnapshot(
            attemptId = attemptId,
            generation = framesObserved,
            requestedPhysicalCameraId = physicalCameraId,
            provenance = binding.provenance,
            openedLogicalCharacteristics = openedLogicalSnapshot,
            selectedPhysicalCharacteristics = binding.physicalCharacteristicsSnapshot,
            requestedAnalysisResolutionWidthPx = requestedAnalysisResolutionWidthPx,
            requestedAnalysisResolutionHeightPx = requestedAnalysisResolutionHeightPx,
            requestedAnalysisResolutionFamily = requestedAnalysisResolutionFamily,
            bufferWidthPx = frame.bufferWidthPx,
            bufferHeightPx = frame.bufferHeightPx,
            cropRectLeftPx = frame.cropRectLeftPx,
            cropRectTopPx = frame.cropRectTopPx,
            cropRectRightPx = frame.cropRectRightPx,
            cropRectBottomPx = frame.cropRectBottomPx,
            rotationDegrees = frame.rotationDegrees,
            sensorToBufferTransformMatrix = frame.sensorToBufferTransform,
            zoomTargetRatio = zoomTargetRatio,
            observedZoomRatio = observedZoomRatio,
            targetPlacementLabel = targetPlacementLabel,
            distanceLabelMm = distanceLabelMm,
            detectionResult = detection,
            targetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
            detectionTolerances = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES,
            capturedAtEpochMillis = capturedAtEpochMillis,
            captureResult = latestCaptureResult,
            lightingAtCapture = latestFrameLighting,
        )
    return copy(latestSnapshot = snapshot)
}
