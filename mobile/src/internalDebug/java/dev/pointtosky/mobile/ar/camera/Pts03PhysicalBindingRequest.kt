package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraSelector

/**
 * PTS-03 follow-up (`internalDebug`-only): the physical-camera IDs the frame-content bind **requests**, at
 * each of the three places CameraX accepts one. A record of configuration, never of hardware truth: nothing
 * here says which sensor produced a frame (see [attributePts03Frame] for that).
 *
 * - A1 ([explicitPhysicalCameraId] `null`): nothing is pinned anywhere — the plain logical back camera, no
 *   physical ID on the selector, no physical ID on either use case.
 * - A2 ([explicitPhysicalCameraId] `X`): `X` on the `CameraSelector` **and** on `Preview` **and** on
 *   `ImageAnalysis` (via `Camera2Interop.Extender.setPhysicalCameraId`).
 *
 * All three IDs derive from the one constructor argument, so `selector == preview == analysis` holds by
 * construction: CameraX cannot bind use cases carrying conflicting physical IDs in one camera configuration,
 * and the experiment must never request one.
 *
 * ## Why the selector and the use cases both carry the ID (diagnostic redundancy)
 * CameraX documents that a physical ID set on the `CameraSelector` is propagated to the bound use cases'
 * output configurations. Reading the CameraX 1.4.2 sources, the two requests are **not** the same config
 * option and do not travel the same path:
 *
 * - Selector: `ProcessCameraProvider.bindToLifecycle` copies `cameraSelector.physicalCameraId` onto every
 *   use case (`UseCase.setPhysicalCameraId`). `Preview` passes it to `SessionConfig.Builder.addSurface`, so
 *   its `OutputConfig` carries it; `ImageAnalysis.createPipeline` passes `null` there, so its `OutputConfig`
 *   carries no physical ID from the selector.
 * - `Camera2Interop.Extender.setPhysicalCameraId`: inserts `Camera2ImplConfig.SESSION_PHYSICAL_CAMERA_ID_OPTION`
 *   into the use case's config, which reaches the session's implementation options; `CaptureSession` then
 *   applies it to **every** stream's `OutputConfiguration.setPhysicalCameraId`, in preference to each
 *   `OutputConfig`'s own ID.
 *
 * Pixel 9 runs of A2 bound with the selector alone reported no nested physical result for the requested ID.
 * Setting the ID on both use cases as well is an **experimental control**: it removes "the requested ID was
 * not propagated onto the analysis output" as an explanation. Reading the sources says what CameraX passes
 * to Camera2, not what the HAL does with it; whether this changes Pixel 9 behaviour is unknown until the
 * device re-test, and none of this recommends that production code configure both.
 */
internal data class Pts03PhysicalBindingRequest(
    /** `null` → A1 (logical, unpinned); non-null → A2, the one ID applied at all three levels. */
    val explicitPhysicalCameraId: String?,
) {
    val selectorPhysicalCameraId: String? get() = explicitPhysicalCameraId
    val previewInteropPhysicalCameraId: String? get() = explicitPhysicalCameraId
    val analysisInteropPhysicalCameraId: String? get() = explicitPhysicalCameraId

    val isExplicitPhysical: Boolean get() = explicitPhysicalCameraId != null

    companion object {
        val LOGICAL_UNPINNED: Pts03PhysicalBindingRequest = Pts03PhysicalBindingRequest(null)
    }
}

/** The selector this request binds: the plain logical back camera for A1, [explicitPhysicalCameraSelector] for A2. */
internal fun Pts03PhysicalBindingRequest.cameraSelector(): CameraSelector =
    selectorPhysicalCameraId?.let { explicitPhysicalCameraSelector(it) } ?: CameraSelector.DEFAULT_BACK_CAMERA
