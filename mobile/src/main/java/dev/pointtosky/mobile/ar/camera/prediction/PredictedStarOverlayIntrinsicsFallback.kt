package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.FrameRotationPair
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

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

/**
 * CAM-2b (task §2): rebuilds an equivalent [CameraSessionGeometryResult] from [geometry] with only its
 * [CameraSessionGeometry.intrinsics] replaced by [intrinsicsResolution] — [geometry]'s frame, pairing,
 * crop/scale transform, viewport, rotation matrix, and timestamps are all reused unchanged.
 * [CameraSessionGeometry] has no public `copy(...)` (its sole construction path,
 * `CameraSessionGeometry.of`, is `internal` to `:core:astro-core`), so this goes through the public,
 * pure factory [createCameraSessionGeometry] instead, feeding it a [FrameRotationPairingResult.Paired]
 * built from [geometry]'s own already-validated frame/rotation/delta — never re-pairing, never
 * re-deriving a rotation sample, never touching
 * [dev.pointtosky.mobile.ar.camera.CameraSessionGeometryProvider],
 * [dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver], or any production intrinsics
 * resolution — [geometry] itself, and the observation it came from, are only ever read here, never
 * mutated (nothing in this function assigns to any field of [geometry]; a fresh, independent
 * [CameraSessionGeometry] is returned).
 *
 * [maxAllowedPairDeltaNanos] is set to `abs(geometry.frameRotationDeltaNanos)` — the exact delta
 * [geometry] itself already carries and was already accepted under its own (unknown to this function)
 * configured tolerance — so this reconstruction can never spuriously reject a pairing that was already
 * valid; the pairing/timestamp facts are being replayed, not re-checked.
 *
 * Returns whatever [createCameraSessionGeometry] returns — expected to be
 * [CameraSessionGeometryResult.Ready] for any [geometry] that was itself already `Ready` (frame/viewport
 * are unchanged and already valid, and the crop/scale transform does not depend on intrinsics at all),
 * but callers must still handle the non-`Ready` case explicitly rather than assume it, per this
 * codebase's categorized-result convention — see [reducePredictedStarOverlayState]'s
 * `DiagnosticFallbackGeometryUnavailable` handling, which reports an explicit diagnostic state rather
 * than throwing if this ever returns something other than `Ready`.
 */
internal fun rebuildGeometryWithIntrinsics(
    geometry: CameraSessionGeometry,
    intrinsicsResolution: CameraIntrinsicsResolution,
): CameraSessionGeometryResult {
    val pairingResult =
        FrameRotationPairingResult.Paired(
            FrameRotationPair(
                frame = geometry.frame,
                rotation = geometry.pairedRotation,
                deltaNanos = geometry.frameRotationDeltaNanos,
            ),
        )
    return createCameraSessionGeometry(
        frame = geometry.frame,
        pairingResult = pairingResult,
        intrinsicsResolution = intrinsicsResolution,
        viewportWidthPx = geometry.viewportSize.width.roundToInt(),
        viewportHeightPx = geometry.viewportSize.height.roundToInt(),
        maxAllowedPairDeltaNanos = abs(geometry.frameRotationDeltaNanos),
    )
}
