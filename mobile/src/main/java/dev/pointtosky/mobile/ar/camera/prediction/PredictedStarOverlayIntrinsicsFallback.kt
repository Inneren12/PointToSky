package dev.pointtosky.mobile.ar.camera.prediction

/**
 * CAM-2b explicit intrinsics-mode selector (task §2 of the follow-up). CAM-2a correctly refuses to
 * project a `PhysicalSensor`-referenced intrinsics value (no active-array/crop mapping to the analyzed
 * buffer exists) — but the production session resolver commonly *does* resolve real
 * `CAMERA_CHARACTERISTICS` data, which is always `PhysicalSensor`-referenced
 * (`dev.pointtosky.core.astro.projection.camera.CameraIntrinsics`'s own cross-field rule). Without an
 * explicit alternative, CAM-2b would permanently report
 * [dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED]
 * on many real devices and never draw a marker — defeating its diagnostic purpose. This mode makes the
 * substitution **explicit, user-selected, and clearly labeled** rather than silently reinterpreting a
 * physical-sensor FOV as an analysis-buffer one.
 *
 * The actual geometry substitution ([dev.pointtosky.core.astro.projection.camera.withIntrinsics], in
 * `:core:astro-core` beside [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry]'s own
 * construction path) lives in the same module as [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry]
 * itself — see [reducePredictedStarOverlayState]'s `DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` branch for the
 * call site. There is no mobile-side geometry-reconstruction helper: `withIntrinsics` never fails (see
 * its own KDoc), so no `CameraSessionGeometryResult`-shaped fallback/failure path is needed here.
 */
enum class PredictedStarOverlayIntrinsicsMode {
    /**
     * Use the camera session's own resolved-or-fallback intrinsics exactly as CAM-1f/1g already publish
     * them — no substitution. The safest, most explicit choice, and the default.
     */
    SESSION_INTRINSICS,

    /**
     * Diagnostic-only: substitute a legacy fixed-FOV,
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.AnalysisBuffer]-referenced
     * intrinsics sized to the *current* analyzed frame's exact dimensions, so CAM-2b can still draw
     * predicted markers on a session whose real intrinsics are `PhysicalSensor`-referenced. Never
     * labeled calibrated or resolved physical intrinsics — see
     * [PredictedStarOverlayMetadata.projectionIntrinsicsSource]/[PredictedStarOverlayMetadata.sessionIntrinsicsSource]
     * and [buildPredictedStarOverlayDiagnosticText], which always show both the real session intrinsics
     * and the substituted projection intrinsics side by side when this mode is active.
     */
    DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK,
}
