package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata

/**
 * CAM-2c physical-camera-binding experiment: one attempt's session state, as a single, flat,
 * pure-Kotlin data shape - no Android/Compose types, no sealed "phase" variant that would force a
 * choice between "has a binding" and "has a frame" (fix for a runtime correctness gap). The prior
 * design used a sealed `ExperimentPhase` (`Binding`/`Bound`/`ExplicitBindFailed`) whose `Bound` variant
 * bundled binding *and* frame together, and relied on a plain `phase` value parameter captured inside a
 * lambda passed to [dev.pointtosky.mobile.ar.CameraPreview] - since that composable's own long-lived
 * `DisposableEffect(Unit)`-installed `ImageAnalysis.Analyzer` retained whichever lambda instance
 * existed when the effect first ran, a later recomposition transitioning `phase` from `Binding` to
 * `Bound` did not, by itself, guarantee the already-installed analyzer would read the new value - see
 * `dev.pointtosky.mobile.ar.rememberStableCallback`'s own KDoc for the companion fix in `CameraPreview`
 * itself.
 *
 * This type is the *pure, testable* half of the fix: [reduceCameraInfoResolved]/[reduceFrame]/
 * [reduceExplicitBindFailure] are the only ways this state ever changes, each independently applying
 * one real-world event (binding resolved, a frame analyzed, or a terminal explicit-bind/zoom failure)
 * without requiring the other to have already happened - see each function's own KDoc for the exact
 * ordering/idempotency guarantees.
 *
 * @property attemptId identifies exactly one physical-camera-binding attempt. Every reducer function
 *   below takes its own `attemptId` parameter and is a no-op unless it matches [attemptId] - a defense
 *   against a callback from a *superseded* attempt (e.g. a late, already-in-flight analyzer callback
 *   that fires just after the UI has moved on to a new candidate or a retry) mutating this session's
 *   state. A fresh attempt must always get a new, distinct `attemptId` - never reused.
 * @property physicalCameraId the exact Camera2 ID this attempt requested - never inferred, never
 *   defaulted.
 * @property bindingResolution the verified (or rejected) physical-camera binding, once
 *   [dev.pointtosky.mobile.ar.CameraPreview]'s `onCameraInfo` has fired for this attempt and
 *   `resolvePhysicalCameraBindingFromCameraInfo` has run - `null` until then.
 * @property explicitBindFailureReason non-`null` only when `CameraPreview` itself reported a terminal
 *   explicit-bind/zoom failure (`onExplicitBindFailure`) - see [isTerminallyFailed].
 * @property latestFrame the most recently analyzed frame's metadata, retained independently of
 *   [bindingResolution] - a frame that arrives before the binding is resolved is not discarded; it
 *   waits here until [recomputeCam2cResult] has both inputs it needs.
 * @property cam2cResult the current CAM-2c resolution attempt, `null` ("awaiting") until both
 *   [bindingResolution] and [latestFrame] are present - see [recomputeCam2cResult].
 */
internal data class ExperimentSessionState(
    val attemptId: Long,
    val physicalCameraId: String,
    val bindingResolution: PhysicalCameraBindingResolution? = null,
    val explicitBindFailureReason: String? = null,
    val latestFrame: CameraFrameMetadata? = null,
    val cam2cResult: Cam2cPhysicalCameraResolution? = null,
) {
    /** `true` once [dev.pointtosky.mobile.ar.CameraPreview] itself reported a terminal explicit-bind
     * or zoom failure for this attempt. Once `true`, this attempt is permanently over - every reducer
     * below becomes a no-op, and the UI must stop rendering `CameraPreview` for this attempt (removing
     * it from composition so its own `DisposableEffect`/`CameraSessionLifecycle` disposal path performs
     * the actual unbind - never a second, ad-hoc unbind call). */
    val isTerminallyFailed: Boolean get() = explicitBindFailureReason != null
}

/** A fresh, empty session for a newly selected (or retried) [physicalCameraId] and [attemptId]. */
internal fun initialExperimentSessionState(
    attemptId: Long,
    physicalCameraId: String,
): ExperimentSessionState = ExperimentSessionState(attemptId = attemptId, physicalCameraId = physicalCameraId)

/**
 * Applies a resolved physical-camera binding outcome. May arrive before or after the first frame
 * ("handle either callback order") - if a frame is already present, [recomputeCam2cResult] runs
 * immediately using both. A no-op (returns [this] unchanged) when [attemptId] does not match this
 * session's own [ExperimentSessionState.attemptId] (a late callback from a superseded attempt) or the
 * session [ExperimentSessionState.isTerminallyFailed].
 */
internal fun ExperimentSessionState.reduceCameraInfoResolved(
    attemptId: Long,
    binding: PhysicalCameraBindingResolution,
): ExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(bindingResolution = binding).recomputeCam2cResult()
}

/**
 * Applies a terminal explicit-bind/zoom failure - see [ExperimentSessionState.isTerminallyFailed].
 * Once applied, every later reducer call for this [attemptId] is a no-op; this function itself is
 * idempotent (a second failure report for an already-failed session changes nothing).
 */
internal fun ExperimentSessionState.reduceExplicitBindFailure(
    attemptId: Long,
    reason: String,
): ExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(explicitBindFailureReason = reason)
}

/**
 * Applies a newly analyzed [frame]. Never regresses or erases [ExperimentSessionState.bindingResolution] -
 * "frame updates must preserve the currently verified binding and must not regress Bound back to
 * Binding"; "retain the latest usable frame until binding provenance becomes available, so the first
 * frame does not need to be discarded solely because `onCameraInfo` arrived later"; "repeated frames
 * may refresh the evidence/result, but must never erase binding provenance." Same attemptId/terminal-
 * failure no-op guard as [reduceCameraInfoResolved].
 */
internal fun ExperimentSessionState.reduceFrame(
    attemptId: Long,
    frame: CameraFrameMetadata,
): ExperimentSessionState {
    if (attemptId != this.attemptId || isTerminallyFailed) return this
    return copy(latestFrame = frame).recomputeCam2cResult()
}

/**
 * Once both [ExperimentSessionState.bindingResolution] and [ExperimentSessionState.latestFrame] are
 * present, computes the evidence-only transform-domain proof
 * ([evidenceOnlySensorToBufferDomainProof], against the *physical* camera's own active-array
 * dimensions - never the logical camera's) and the typed CAM-2c result
 * ([resolveCam2cForExplicitPhysicalCamera]) - order-independent: it does not matter which of the two
 * inputs arrived first, only that both are now present. Either input missing leaves
 * [ExperimentSessionState.cam2cResult] `null` ("awaiting"), never a guess.
 */
private fun ExperimentSessionState.recomputeCam2cResult(): ExperimentSessionState {
    val binding = bindingResolution ?: return copy(cam2cResult = null)
    val frame = latestFrame ?: return copy(cam2cResult = null)
    val physicalSnapshot = (binding as? PhysicalCameraBindingResolution.Bound)?.physicalCharacteristicsSnapshot
    val activeArrayWidthPx =
        physicalSnapshot?.activeArrayLeftPx?.let { left -> physicalSnapshot.activeArrayRightPx?.let { right -> right - left } }
    val activeArrayHeightPx =
        physicalSnapshot?.activeArrayTopPx?.let { top -> physicalSnapshot.activeArrayBottomPx?.let { bottom -> bottom - top } }
    val domainProof =
        evidenceOnlySensorToBufferDomainProof(
            matrix = frame.sensorToBufferTransform,
            activeArrayWidthPx = activeArrayWidthPx,
            activeArrayHeightPx = activeArrayHeightPx,
            bufferWidthPx = frame.bufferWidthPx,
            bufferHeightPx = frame.bufferHeightPx,
        )
    val result =
        resolveCam2cForExplicitPhysicalCamera(
            binding = binding,
            domainProof = domainProof,
            sensorToBufferTransform = frame.sensorToBufferTransform,
            bufferWidthPx = frame.bufferWidthPx,
            bufferHeightPx = frame.bufferHeightPx,
        )
    return copy(cam2cResult = result)
}
