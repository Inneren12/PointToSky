package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c physical-camera-binding experiment: the screen-level state above [ExperimentSessionState] -
 * either no attempt has started yet ([session] is `null`) or exactly one attempt is live - plus the
 * monotonically increasing [nextAttemptId] counter every new or retried attempt draws its own
 * `attemptId` from (never reused - see [ExperimentSessionState]'s own KDoc on why attempt identity must
 * never repeat). Pulled out as its own pure, directly testable type (fix for a lifecycle-testability gap:
 * "add lifecycle tests covering... retry candidate -> fresh session; previous attempt cannot overwrite
 * the retry state") rather than two separate `remember`ed Compose variables
 * (`uiState`/`nextAttemptId`) whose combined transition logic could previously only be exercised by
 * actually running [PhysicalCameraBindingExperimentScreen].
 */
internal data class ExperimentUiModel(
    val nextAttemptId: Long = 0L,
    val session: ExperimentSessionState? = null,
)

/**
 * Starts a brand-new attempt for [physicalCameraId] - a fresh candidate pick, or a retry of the same
 * candidate after a terminal failure - always with a new, never-before-used `attemptId`. Any callback
 * still in flight from a prior attempt is guarded off by [ExperimentSessionState]'s own `attemptId`
 * no-op checks; this function's job is only to guarantee that `attemptId` is never reused.
 */
internal fun ExperimentUiModel.startAttempt(
    physicalCameraId: String,
    requestedAnalysisResolution: AnalysisResolutionCandidate? = null,
): ExperimentUiModel =
    copy(
        nextAttemptId = nextAttemptId + 1,
        session =
            initialExperimentSessionState(
                attemptId = nextAttemptId,
                physicalCameraId = physicalCameraId,
                requestedAnalysisResolution = requestedAnalysisResolution,
            ),
    )

/**
 * Retries the currently selected candidate as a brand-new attempt (a fresh `attemptId`, so a late
 * callback from the just-failed attempt can never mutate the retried session - see
 * [ExperimentSessionState]'s no-op guards). A no-op (returns [this] unchanged) if no attempt has ever
 * started - retry is only ever offered once a [session] already exists.
 */
internal fun ExperimentUiModel.retry(): ExperimentUiModel {
    val current = session ?: return this
    return startAttempt(
        physicalCameraId = current.physicalCameraId,
        requestedAnalysisResolution =
            current.requestedAnalysisResolutionWidthPx?.let { width ->
                current.requestedAnalysisResolutionHeightPx?.let { height ->
                    current.requestedAnalysisResolutionFamily?.let { family ->
                        // The family is restored from the stored request (P1 fix) — never re-derived
                        // from the dimensions' exact integer ratio.
                        AnalysisResolutionCandidate(widthPx = width, heightPx = height, family = family)
                    }
                }
            },
    )
}

/**
 * Returns to candidate selection, discarding the current attempt entirely. A callback that later fires
 * from that discarded attempt is still rejected by [ExperimentSessionState]'s own `attemptId` no-op
 * guard even though this model no longer references the discarded [session] at all - see [updateSession].
 */
internal fun ExperimentUiModel.backToCandidates(): ExperimentUiModel = copy(session = null)

/**
 * Applies [reducer] to the live [session], but only if [attemptId] still matches it - the
 * defense-in-depth guard [PhysicalCameraBindingExperimentScreen] applies to every event
 * [dev.pointtosky.mobile.ar.CameraPreview] reports, on top of [ExperimentSessionState]'s own identical
 * check: a late callback from a superseded attempt (already retried, or already abandoned via
 * [backToCandidates]) must never resurrect or mutate a since-replaced session. A no-op (returns [this]
 * unchanged) both when there is no live [session] and when [attemptId] does not match it.
 */
internal fun ExperimentUiModel.updateSession(
    attemptId: Long,
    reducer: (ExperimentSessionState) -> ExperimentSessionState,
): ExperimentUiModel {
    val current = session ?: return this
    if (current.attemptId != attemptId) return this
    return copy(session = reducer(current))
}
