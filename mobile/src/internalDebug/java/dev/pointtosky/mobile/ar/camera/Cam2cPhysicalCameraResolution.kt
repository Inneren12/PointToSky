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
 * **A sixth, independent condition (fix for a P1 correctness gap): the sensor-to-buffer transform's
 * source domain must also be proven**, via [SensorToBufferDomainProof] — see that type's own KDoc.
 * Physical-sensor identity (conditions 1-5) says nothing about whether
 * [resolveAnalysisBufferIntrinsics]'s own implicit active-array-local source-domain assumption holds
 * for this device's real matrix. A verified physical binding with an *unresolved* domain (the honest,
 * currently-universal state for every real device this codebase has run on) must **not** resolve, even
 * when the reported matrix is a trivial identity matrix that superficially "looks fine" — see
 * `docs/validation/cam_2c_pixel9_evidence.md` §3 for exactly the real-device case this guards against.
 *
 * This does **not** change [resolveAnalysisBufferIntrinsics] or its `UnsupportedLogicalMultiCameraMapping`
 * guard at all - a session with no explicit, verified physical binding still resolves through the
 * unchanged `resolveCameraIntrinsicsPreferringCalibration`/`resolveAnalysisBufferIntrinsics` path and
 * still hits that guard exactly as before (task §6's "preserve old behavior" requirement). This
 * function is a second, independent entry point that a physical-camera-bound session uses instead.
 */
internal sealed interface Cam2cPhysicalCameraResolution {
    data class Resolved(
        val intrinsics: dev.pointtosky.core.astro.projection.camera.CameraIntrinsics,
        val diagnostics: CameraCalibrationDiagnostics,
        val provenance: PhysicalCameraProvenance,
    ) : Cam2cPhysicalCameraResolution

    /** The physical-camera binding itself never reached [PhysicalCameraBindingResolution.Bound]. Checked
     * before [DomainNotProven] - a binding failure always wins, regardless of what [SensorToBufferDomainProof]
     * a caller happened to supply. */
    data class BindingFailure(val binding: PhysicalCameraBindingResolution) : Cam2cPhysicalCameraResolution

    /** Binding was verified ([PhysicalCameraBindingResolution.Bound]), but [SensorToBufferDomainProof]
     * was not one of the `Proven*` variants [resolveAnalysisBufferIntrinsics]'s own math requires -
     * [resolveAnalysisBufferIntrinsics] was never called at all in this case (never merely discarded
     * after the fact). Physical-sensor identity alone never publishes calibrated `AnalysisBuffer`
     * intrinsics. */
    data class DomainNotProven(
        val proof: SensorToBufferDomainProof,
        val provenance: PhysicalCameraProvenance,
    ) : Cam2cPhysicalCameraResolution

    /** Binding was verified and the transform domain was proven, but the underlying
     * [AnalysisBufferIntrinsicsResolution] still did not resolve (e.g. missing focal length,
     * unsupported transform class) - distinct from [BindingFailure]/[DomainNotProven]: both the
     * physical-camera identity and the transform domain were proven here, the *intrinsics* math failed
     * for an unrelated reason any single-camera device could also hit. */
    data class IntrinsicsFailure(
        val attempt: AnalysisBufferIntrinsicsResolution,
        val provenance: PhysicalCameraProvenance,
    ) : Cam2cPhysicalCameraResolution
}

/**
 * Resolves CAM-2c calibrated intrinsics for an explicitly bound, verified physical camera. [binding]
 * must already be the outcome of `verifyPhysicalCameraProvenance`/`resolvePhysicalCameraBindingFromCameraInfo` -
 * this function performs no binding itself. [domainProof] must independently establish that
 * [sensorToBufferTransform]'s source domain matches what [resolveAnalysisBufferIntrinsics] assumes -
 * see [SensorToBufferDomainProof.unlocksAnalysisBufferResolution]. Order of checks matters: [binding]
 * is checked first, so a binding failure always wins over an unresolved/mismatched domain, never the
 * reverse.
 */
internal fun resolveCam2cForExplicitPhysicalCamera(
    binding: PhysicalCameraBindingResolution,
    domainProof: SensorToBufferDomainProof,
    sensorToBufferTransform: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): Cam2cPhysicalCameraResolution {
    if (binding !is PhysicalCameraBindingResolution.Bound) {
        return Cam2cPhysicalCameraResolution.BindingFailure(binding)
    }
    if (!domainProof.unlocksAnalysisBufferResolution()) {
        return Cam2cPhysicalCameraResolution.DomainNotProven(domainProof, binding.provenance)
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
