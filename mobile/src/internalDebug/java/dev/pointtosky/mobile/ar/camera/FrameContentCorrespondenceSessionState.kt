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
): FrameContentExperimentSessionState =
    FrameContentExperimentSessionState(
        attemptId = attemptId,
        physicalCameraId = physicalCameraId,
        requestedAnalysisResolutionWidthPx = requestedAnalysisResolution?.widthPx,
        requestedAnalysisResolutionHeightPx = requestedAnalysisResolution?.heightPx,
        requestedAnalysisResolutionFamily = requestedAnalysisResolution?.family,
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
): FrameContentExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(
        latestFrame = frame,
        latestDetection = detection,
        framesObserved = framesObserved + 1,
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
        )
    return copy(latestSnapshot = snapshot)
}
