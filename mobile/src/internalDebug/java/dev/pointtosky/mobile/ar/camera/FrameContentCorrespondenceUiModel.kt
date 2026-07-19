package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). Screen-level UI model mirroring
 * [ExperimentUiModel] exactly: [nextAttemptId] is monotonic and never reused, [startAttempt] always
 * constructs a brand-new [FrameContentExperimentSessionState] (never mutates the previous one — task
 * §8's "a new attempt clears the old snapshot" requirement), and [updateSession] is a defense-in-depth
 * `attemptId` guard layered on top of [FrameContentExperimentSessionState]'s own internal guard.
 */
internal data class FrameContentCorrespondenceUiModel(
    val nextAttemptId: Long = 0L,
    val session: FrameContentExperimentSessionState? = null,
)

internal fun FrameContentCorrespondenceUiModel.startAttempt(
    physicalCameraId: String,
    requestedAnalysisResolution: AnalysisResolutionCandidate? = null,
): FrameContentCorrespondenceUiModel =
    copy(
        nextAttemptId = nextAttemptId + 1,
        session = initialFrameContentExperimentSessionState(nextAttemptId, physicalCameraId, requestedAnalysisResolution),
    )

internal fun FrameContentCorrespondenceUiModel.retry(): FrameContentCorrespondenceUiModel {
    val current = session ?: return this
    val requestedResolution =
        if (current.requestedAnalysisResolutionWidthPx != null && current.requestedAnalysisResolutionHeightPx != null) {
            AnalysisResolutionCandidate(
                widthPx = current.requestedAnalysisResolutionWidthPx,
                heightPx = current.requestedAnalysisResolutionHeightPx,
                family = current.requestedAnalysisResolutionFamily ?: AnalysisResolutionFamily.NEAR_4_3,
            )
        } else {
            null
        }
    return startAttempt(current.physicalCameraId, requestedResolution)
}

internal fun FrameContentCorrespondenceUiModel.backToCandidates(): FrameContentCorrespondenceUiModel = copy(session = null)

internal fun FrameContentCorrespondenceUiModel.updateSession(
    attemptId: Long,
    reducer: (FrameContentExperimentSessionState) -> FrameContentExperimentSessionState,
): FrameContentCorrespondenceUiModel {
    val current = session ?: return this
    if (current.attemptId != attemptId) return this
    return copy(session = reducer(current))
}
