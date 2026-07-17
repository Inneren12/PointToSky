package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * CAM-2c physical-camera provenance experiment (task §6): the narrow, additive path that allows a
 * calibrated `AnalysisBuffer` resolution on a logical multi-camera device *only* once every one of
 * the task's five conditions is proven:
 *
 * 1. A concrete physical camera was explicitly selected/bound - see [PhysicalCameraBindingResolution.Bound].
 * 2. The selected physical Camera2 ID is known - [PhysicalCameraProvenance.physicalCameraId].
 * 3. Characteristics were read from that exact physical ID - [PhysicalCameraBindingResolution.Bound.physicalCharacteristicsSnapshot]
 *    is verified (`verifyPhysicalCameraProvenance`) to carry that exact `cameraId`.
 * 4. Preview and ImageAnalysis share the same selected camera binding - guaranteed structurally by
 *    `CameraPreview`'s `cameraSelectorOverride`, since both use cases are bound together in one
 *    `bindToLifecycle(..., selector, preview, imageAnalysis)` call using the same selector.
 * 5. No unproven logical-camera metadata is substituted - [resolveAnalysisBufferIntrinsics] is called
 *    with the *physical* camera's own snapshot, never the logical camera's.
 *
 * This does **not** change [resolveAnalysisBufferIntrinsics] or its `UnsupportedLogicalMultiCameraMapping`
 * guard at all - a session with no explicit, verified physical binding still resolves through the
 * unchanged `resolveCameraIntrinsicsPreferringCalibration`/`resolveAnalysisBufferIntrinsics` path and
 * still hits that guard exactly as before (task §6's "preserve old behavior" requirement). This
 * function is a second, independent entry point that a physical-camera-bound session uses instead.
 */
sealed interface Cam2cPhysicalCameraResolution {
    data class Resolved(
        val intrinsics: dev.pointtosky.core.astro.projection.camera.CameraIntrinsics,
        val diagnostics: CameraCalibrationDiagnostics,
        val provenance: PhysicalCameraProvenance,
    ) : Cam2cPhysicalCameraResolution

    /** The physical-camera binding itself never reached [PhysicalCameraBindingResolution.Bound]. */
    data class BindingFailure(val binding: PhysicalCameraBindingResolution) : Cam2cPhysicalCameraResolution

    /** Binding succeeded and was verified, but the underlying [AnalysisBufferIntrinsicsResolution]
     * still did not resolve (e.g. missing focal length, unsupported transform class) - distinct from
     * [BindingFailure]: the physical-camera identity itself was proven here, the *intrinsics* math
     * failed for an unrelated reason any single-camera device could also hit. */
    data class IntrinsicsFailure(
        val attempt: AnalysisBufferIntrinsicsResolution,
        val provenance: PhysicalCameraProvenance,
    ) : Cam2cPhysicalCameraResolution
}

/**
 * Resolves CAM-2c calibrated intrinsics for an explicitly bound, verified physical camera. [binding]
 * must already be the outcome of `verifyPhysicalCameraProvenance`/`resolvePhysicalCameraBindingFromCameraInfo` -
 * this function performs no binding itself, only the final intrinsics resolution once provenance is
 * already proven (or not).
 */
internal fun resolveCam2cForExplicitPhysicalCamera(
    binding: PhysicalCameraBindingResolution,
    sensorToBufferTransform: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): Cam2cPhysicalCameraResolution {
    if (binding !is PhysicalCameraBindingResolution.Bound) {
        return Cam2cPhysicalCameraResolution.BindingFailure(binding)
    }
    val attempt =
        resolveAnalysisBufferIntrinsics(
            snapshot = binding.physicalCharacteristicsSnapshot,
            sensorToBufferTransform = sensorToBufferTransform,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    return when (attempt) {
        is AnalysisBufferIntrinsicsResolution.Resolved ->
            Cam2cPhysicalCameraResolution.Resolved(
                intrinsics = attempt.intrinsics,
                diagnostics = attempt.diagnostics,
                provenance = binding.provenance,
            )
        else -> Cam2cPhysicalCameraResolution.IntrinsicsFailure(attempt, binding.provenance)
    }
}
