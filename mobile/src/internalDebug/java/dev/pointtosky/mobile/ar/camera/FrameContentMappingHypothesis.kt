package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.ActiveArrayLocalRect
import dev.pointtosky.core.astro.projection.camera.ActiveArrayRect
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.MatrixIntrinsicsMappingResult
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength
import dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix
import dev.pointtosky.core.astro.projection.camera.toActiveArrayLocalRect

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §3). The three competing
 * coordinate-basis hypotheses this experiment measures detected target points against. Every hypothesis
 * here is evidence-only: none of this file ever constructs a [SensorToBufferDomainProof] value, and
 * none of it feeds [resolveCam2cForExplicitPhysicalCamera] or [resolveAnalysisBufferIntrinsics]'s own
 * gated production path — the same "call the pure math directly, never through the gate" pattern
 * [assessWholeActiveArrayMappingHypothesis]/[assessDualBasisMatrixEvidence] already use.
 */
internal enum class FrameContentMappingHypothesisId {
    /** Apply the *observed*, real CameraX `sensorToBufferTransformMatrix` — built by CameraX for the
     * opened *logical* camera's own active array — directly to points computed in the *physical*
     * camera's own native pixel basis, exactly as its documented API contract says to (no basis
     * translation/correction of any kind). Tests whether that direct application, despite the logical
     * and physical active arrays differing in size, still lands correctly on real detected content. */
    LOGICAL_CAMERAX_MATRIX_PATH,

    /** Build a *fresh* CameraX-1.4.2-style sensor-to-buffer matrix
     * ([predictCameraX142SensorToBufferMatrix]) from the *physical* camera's own active array and the
     * actual analysis-buffer dimensions, then apply that model matrix (never the observed one) to
     * points computed in the physical camera's own native pixel basis. Tests whether CameraX, had it
     * been told to build the matrix from the physical active array instead of the logical one, would
     * land correctly. */
    PHYSICAL_ACTIVE_ARRAY_MODEL_PATH,

    /** A mathematically explicit physical-active-array-to-logical-active-array coordinate transform —
     * e.g. a verified offset/scale relating the two arrays' own origins. No such transform is
     * source-traced or device-proven anywhere in this codebase (confirmed before this experiment was
     * added); [computeFrameContentMappingHypotheses] always reports this hypothesis
     * [FrameContentHypothesisIntrinsicsResult.Unavailable] with
     * [FRAME_CONTENT_RECONCILED_NOT_IMPLEMENTED_REASON] rather than fabricating a transform (task §3:
     * "If no defensible transform exists, report NOT_IMPLEMENTED rather than fabricating one"). */
    RECONCILED_PHYSICAL_TO_LOGICAL_PATH,
}

internal const val FRAME_CONTENT_RECONCILED_NOT_IMPLEMENTED_REASON: String =
    "NOT_IMPLEMENTED: no mathematically explicit physical-active-array-to-logical-active-array " +
        "coordinate transform is source-traced or device-proven in this codebase; fabricating one " +
        "would misrepresent evidence as a verified model."

/**
 * Fixed, shared documentation of this experiment's unrotated-buffer contract (fix for a rotation
 * -semantics correctness gap: an earlier revision of both implemented hypotheses claimed
 * `rotationFoldedIn=true`, which contradicts both the documented CameraX/`ImageProxy` contract and the
 * observed real Pixel 9 evidence — `ImageProxy.imageInfo.rotationDegrees` can be `90` while the very
 * same frame's `sensorToBufferTransformMatrix` still classifies as `AXIS_ALIGNED_0`, i.e. the matrix
 * itself carries no rotation component even when the platform reports a non-zero display rotation).
 *
 * Identical for both [FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH] and
 * [FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH] — neither one folds rotation in,
 * by construction, since neither one ever reads or applies `rotationDegrees`:
 * - [detectFrameContentTargetCorners] reads detected points directly from the **unrotated**
 *   `ImageProxy` analysis buffer (the luma plane exactly as CameraX delivers it) — never through
 *   `PreviewView`/display coordinates.
 * - Every hypothesis's predicted point ([projectObjectPoint]) is computed in that **exact same**
 *   unrotated buffer coordinate space — the sensor-to-buffer matrix composition
 *   ([dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix]) never reads
 *   `CameraFrameMetadata.rotationDegrees` at all.
 * - `rotationDegrees` is captured on [FrameContentCorrespondenceSnapshot] purely as **metadata** — for
 *   the report/JSON to show alongside the residuals, never consumed by any projection or detection
 *   math in this experiment.
 * - No display/`PreviewView` rotation (e.g. `CropScaleTransform`) is applied anywhere in this
 *   experiment — that transform belongs to the production AR renderer's own display pipeline, entirely
 *   separate from this diagnostic.
 */
internal const val FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT: String =
    "Detected points are read directly from the unrotated ImageProxy analysis buffer (the luma plane " +
        "exactly as CameraX delivers it); every hypothesis's predicted points are computed in that " +
        "exact same unrotated buffer coordinate space. CameraFrameMetadata.rotationDegrees " +
        "(ImageProxy.imageInfo.rotationDegrees) is captured as metadata only and is never applied as a " +
        "rotation to either detected or predicted points; no PreviewView/display rotation is applied " +
        "anywhere in this experiment. This matches observed Pixel 9 evidence: rotationDegrees may be " +
        "90 while the same frame's sensorToBufferTransformMatrix still classifies AXIS_ALIGNED_0."

/** Documents, for one hypothesis, every field task §3 requires be documented — carried as data on the
 * hypothesis result itself (never left as an assumption a reader has to reconstruct from code). */
internal data class FrameContentHypothesisDocumentation(
    val inputKBasis: String,
    val distortionBasis: String,
    val sourceCoordinateRect: String,
    val transformsAppliedInOrder: List<String>,
    val rotationFoldedIn: Boolean,
    val cropRectFoldedIn: Boolean,
    val whyOutputIsBufferCoordinates: String,
    val bufferRotationContract: String = FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT,
)

/** `distortionBasis` shared by every hypothesis (task §5/§9 honesty convention): this codebase has no
 * verified interpretation of Android's `LENS_DISTORTION` coefficient semantics (the division-model
 * contract per API 29+ is not source-traced or device-proven here), so raw coefficients are captured
 * for evidence only ([FrameContentDistortionCapture]) and never applied to any projection in this
 * experiment. */
internal const val FRAME_CONTENT_DISTORTION_BASIS: String =
    "NONE_APPLIED (evidence-only capture — see FrameContentDistortionCapture; Android LENS_DISTORTION " +
        "coefficient semantics are not verified/implemented in this codebase)"

internal sealed interface FrameContentHypothesisIntrinsicsResult {
    data class Available(
        val intrinsics: FrameContentHypothesisIntrinsics,
        val diagnostics: CameraCalibrationDiagnostics,
    ) : FrameContentHypothesisIntrinsicsResult

    data class Unavailable(
        val reason: String,
    ) : FrameContentHypothesisIntrinsicsResult
}

internal data class FrameContentHypothesis(
    val id: FrameContentMappingHypothesisId,
    val documentation: FrameContentHypothesisDocumentation,
    val result: FrameContentHypothesisIntrinsicsResult,
)

/**
 * Builds this hypothesis's own buffer-space camera matrix from the **physical** camera's own
 * [physicalSnapshot] and [matrix], for one hypothesis's [bufferWidthPx]/[bufferHeightPx].
 *
 * **Deliberately does not call [resolveAnalysisBufferIntrinsics]** (task §6 constraint: never through
 * [resolveCam2cForExplicitPhysicalCamera]'s [SensorToBufferDomainProof] gate) — for a second, load
 * -bearing reason beyond that constraint: [resolveAnalysisBufferIntrinsics]'s own crop-bounds check
 * (its step 7, "the matrix-inferred crop region must lie within `[0, activeArrayWidthPx] x
 * [0, activeArrayHeightPx]`") is a *production safety gate* that exists specifically to reject a matrix
 * whose implied source domain does not fit the physical active array it is being validated against.
 * [FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH] **is** exactly that case by
 * construction (a matrix built for the LOGICAL camera's larger active array, applied against the
 * PHYSICAL camera's smaller one) — calling the gated resolver would make that hypothesis silently
 * `Unavailable` on every real device where the logical and physical active arrays differ in size
 * (the Pixel 9 physical-camera-3 case this experiment exists to measure), defeating the whole point of
 * measuring it. This function instead composes the matrix via the same underlying pure algebra
 * ([activeArrayIntrinsicsFromFocalLength], [dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix])
 * the resolver itself uses, but never gates on crop-region containment — a domain mismatch is exactly
 * the *evidence* this experiment reports (via [FrameContentHypothesisIntrinsics.activeArrayWidthPx]/
 * `activeArrayHeightPx` vs. the buffer-space result), not a reason to hide the computation.
 *
 * `physicalSnapshot.isLogicalMultiCamera` is always `false` for a verified physical-camera binding, so
 * the logical-multi-camera guard below never fires in practice — checked anyway as defense in depth.
 */
private fun resolveHypothesisIntrinsics(
    physicalSnapshot: CameraCharacteristicsSnapshot,
    matrix: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): FrameContentHypothesisIntrinsicsResult {
    if (physicalSnapshot.isLogicalMultiCamera) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("UNSUPPORTED_LOGICAL_MULTI_CAMERA_MAPPING")
    }
    val activeLeft = physicalSnapshot.activeArrayLeftPx
    val activeTop = physicalSnapshot.activeArrayTopPx
    val activeRight = physicalSnapshot.activeArrayRightPx
    val activeBottom = physicalSnapshot.activeArrayBottomPx
    if (activeLeft == null || activeTop == null || activeRight == null || activeBottom == null) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_ACTIVE_ARRAY")
    }
    val activeArrayWidthPx = activeRight - activeLeft
    val activeArrayHeightPx = activeBottom - activeTop
    if (activeArrayWidthPx <= 0 || activeArrayHeightPx <= 0) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_ACTIVE_ARRAY")
    }

    val sensorWidthMm = physicalSnapshot.sensorPhysicalWidthMm
    val sensorHeightMm = physicalSnapshot.sensorPhysicalHeightMm
    if (sensorWidthMm == null || sensorHeightMm == null ||
        !sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() ||
        sensorWidthMm <= 0f || sensorHeightMm <= 0f
    ) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_PHYSICAL_SENSOR_SIZE")
    }

    val focalLengthMm =
        when (val selection = selectFocalLengthMm(physicalSnapshot.availableFocalLengthsMm)) {
            is FocalLengthSelection.Resolved -> selection.focalLengthMm
            FocalLengthSelection.NoneValid, FocalLengthSelection.Ambiguous ->
                return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_FOCAL_LENGTH")
        }

    val pixelArrayWidthPx = physicalSnapshot.pixelArrayWidthPx
    val pixelArrayHeightPx = physicalSnapshot.pixelArrayHeightPx
    if (pixelArrayWidthPx == null || pixelArrayHeightPx == null || pixelArrayWidthPx <= 0 || pixelArrayHeightPx <= 0) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_PIXEL_ARRAY_SIZE")
    }

    if (matrix == null) {
        return FrameContentHypothesisIntrinsicsResult.Unavailable("MISSING_SENSOR_TO_BUFFER_TRANSFORM")
    }

    val active =
        try {
            activeArrayIntrinsicsFromFocalLength(
                focalLengthMm = focalLengthMm,
                sensorWidthMm = sensorWidthMm.toDouble(),
                sensorHeightMm = sensorHeightMm.toDouble(),
                pixelArrayWidthPx = pixelArrayWidthPx,
                pixelArrayHeightPx = pixelArrayHeightPx,
                activeArrayWidthPx = activeArrayWidthPx,
                activeArrayHeightPx = activeArrayHeightPx,
            )
        } catch (e: IllegalArgumentException) {
            return FrameContentHypothesisIntrinsicsResult.Unavailable("ACTIVE_ARRAY_INTRINSICS_INVALID(${e.message})")
        }

    val mapping = mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx, bufferHeightPx)
    val bufferValues =
        when (mapping) {
            is MatrixIntrinsicsMappingResult.Unsupported ->
                return FrameContentHypothesisIntrinsicsResult.Unavailable("UNSUPPORTED_SENSOR_TO_BUFFER_TRANSFORM(${mapping.transformClass})")
            is MatrixIntrinsicsMappingResult.Mapped ->
                if (mapping.transformClass != SensorToBufferTransformClass.AXIS_ALIGNED_0) {
                    return FrameContentHypothesisIntrinsicsResult.Unavailable("NON_AXIS_ALIGNED_TRANSFORM_CLASS(${mapping.transformClass})")
                } else {
                    mapping.values
                }
        }

    // Diagnostics-only: the matrix-implied source region, computed without gating on whether it fits
    // inside the physical active array — a mismatch here (e.g. LOGICAL_CAMERAX_MATRIX_PATH against a
    // smaller physical active array) is exactly the evidence this experiment measures, never a reason
    // to hide the computation (see this function's own KDoc).
    val cropRegion =
        try {
            matrix.toActiveArrayLocalRect(bufferWidthPx, bufferHeightPx)
        } catch (_: IllegalArgumentException) {
            null
        }

    val diagnostics =
        CameraCalibrationDiagnostics.of(
            active = active,
            activeArrayRect = ActiveArrayRect(activeLeft.toDouble(), activeTop.toDouble(), activeRight.toDouble(), activeBottom.toDouble()),
            cropRegion = cropRegion ?: ActiveArrayLocalRect(0.0, 0.0, 1.0, 1.0),
            bufferValues = bufferValues,
            sensorWidthMm = sensorWidthMm.toDouble(),
            sensorHeightMm = sensorHeightMm.toDouble(),
            focalLengthMm = focalLengthMm,
            quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            transformClass = mapping.transformClass,
            focalDerivationBasis = CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_PIXEL_ARRAY,
            pixelArrayWidthPx = pixelArrayWidthPx,
            pixelArrayHeightPx = pixelArrayHeightPx,
            cameraId = physicalSnapshot.cameraId,
            isLogicalMultiCamera = physicalSnapshot.isLogicalMultiCamera,
            physicalCameraIds = physicalSnapshot.physicalCameraIds,
        )

    val intrinsics =
        FrameContentHypothesisIntrinsics(
            activeFxPx = active.fxPx,
            activeFyPx = active.fyPx,
            activeCxPx = active.cxPx,
            activeCyPx = active.cyPx,
            activeArrayWidthPx = active.widthPx,
            activeArrayHeightPx = active.heightPx,
            bufferFxPx = bufferValues.fxPx,
            bufferFyPx = bufferValues.fyPx,
            bufferCxPx = bufferValues.cxPx,
            bufferCyPx = bufferValues.cyPx,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    return FrameContentHypothesisIntrinsicsResult.Available(intrinsics, diagnostics)
}

/**
 * Computes all three [FrameContentMappingHypothesisId] hypotheses (task §3) for one frozen frame:
 * [physicalSnapshot] is the verified physical camera's own characteristics,
 * [observedCameraXMatrix] the real per-frame CameraX `sensorToBufferTransformMatrix`,
 * [bufferWidthPx]/[bufferHeightPx] the exact analyzed `ImageAnalysis` buffer dimensions. Always returns
 * exactly three entries, one per [FrameContentMappingHypothesisId], in enum declaration order.
 */
internal fun computeFrameContentMappingHypotheses(
    physicalSnapshot: CameraCharacteristicsSnapshot,
    observedCameraXMatrix: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): List<FrameContentHypothesis> {
    val activeArrayRect =
        physicalArrayRectOrNull(physicalSnapshot)

    val logicalPathResult =
        resolveHypothesisIntrinsics(physicalSnapshot, observedCameraXMatrix, bufferWidthPx, bufferHeightPx)
    val logicalPath =
        FrameContentHypothesis(
            id = FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH,
            documentation =
                FrameContentHypothesisDocumentation(
                    inputKBasis = "Characteristics-derived approximate physical pinhole K " +
                        "(CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN), active-array-local " +
                        "pixel basis (SENSOR_INFO_ACTIVE_ARRAY_SIZE of the SELECTED physical camera) — " +
                        "derived from LENS_INFO_AVAILABLE_FOCAL_LENGTHS + SENSOR_INFO_PHYSICAL_SIZE + " +
                        "SENSOR_INFO_PIXEL_ARRAY_SIZE with an assumed geometric-centre principal point " +
                        "(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT); LENS_INTRINSIC_CALIBRATION " +
                        "is NOT consulted by this path — never call this K \"calibrated\"",
                    distortionBasis = FRAME_CONTENT_DISTORTION_BASIS,
                    sourceCoordinateRect = activeArrayRect?.let { "physical active array $it" } ?: "unavailable",
                    transformsAppliedInOrder =
                        listOf(
                            "1. Pinhole-project object point using the characteristics-derived approximate " +
                                "physical K, active-array-local basis",
                            "2. Apply the OBSERVED CameraX sensorToBufferTransformMatrix directly, with no " +
                                "basis translation/correction — per its documented API contract, treating " +
                                "the point as already being in the domain the matrix expects",
                        ),
                    rotationFoldedIn = false,
                    cropRectFoldedIn = false,
                    whyOutputIsBufferCoordinates =
                        "mapActiveArrayIntrinsicsThroughMatrix composes the active-array-local K directly " +
                            "with the 3x3 sensor-to-buffer matrix (K' = M . K) for a buffer of exactly " +
                            "bufferWidthPx x bufferHeightPx pixels — this path never calls " +
                            "resolveAnalysisBufferIntrinsics (see resolveHypothesisIntrinsics's own KDoc for " +
                            "why); the composed K' is buffer-space by direct construction of that matrix " +
                            "product, not by any resolver's own labelling",
                    bufferRotationContract = FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT,
                ),
            result = logicalPathResult,
        )

    val physicalModelMatrix =
        activeArrayRect?.let { rect -> predictCameraX142SensorToBufferMatrix(rect, bufferWidthPx, bufferHeightPx)?.matrix }
    val physicalPathResult =
        if (physicalModelMatrix == null) {
            FrameContentHypothesisIntrinsicsResult.Unavailable("PHYSICAL_ACTIVE_ARRAY_MODEL_UNAVAILABLE")
        } else {
            resolveHypothesisIntrinsics(physicalSnapshot, physicalModelMatrix, bufferWidthPx, bufferHeightPx)
        }
    val physicalPath =
        FrameContentHypothesis(
            id = FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH,
            documentation =
                FrameContentHypothesisDocumentation(
                    inputKBasis = "Characteristics-derived approximate physical pinhole K " +
                        "(CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN), active-array-local " +
                        "pixel basis (SENSOR_INFO_ACTIVE_ARRAY_SIZE of the SELECTED physical camera) — " +
                        "derived from LENS_INFO_AVAILABLE_FOCAL_LENGTHS + SENSOR_INFO_PHYSICAL_SIZE + " +
                        "SENSOR_INFO_PIXEL_ARRAY_SIZE with an assumed geometric-centre principal point " +
                        "(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT); LENS_INTRINSIC_CALIBRATION " +
                        "is NOT consulted by this path — never call this K \"calibrated\"",
                    distortionBasis = FRAME_CONTENT_DISTORTION_BASIS,
                    sourceCoordinateRect = activeArrayRect?.let { "physical active array $it" } ?: "unavailable",
                    transformsAppliedInOrder =
                        listOf(
                            "1. Pinhole-project object point using the characteristics-derived approximate " +
                                "physical K, active-array-local basis",
                            "2. Apply a FRESHLY BUILT CameraX-1.4.2-style matrix " +
                                "(predictCameraX142SensorToBufferMatrix), constructed FROM the physical " +
                                "camera's own active array and this frame's real buffer dimensions — " +
                                "never the observed matrix",
                        ),
                    rotationFoldedIn = false,
                    cropRectFoldedIn = false,
                    whyOutputIsBufferCoordinates =
                        "mapActiveArrayIntrinsicsThroughMatrix composes the active-array-local K directly " +
                            "with the freshly-built 3x3 CameraX-1.4.2-style matrix (K' = M . K) for a buffer " +
                            "of exactly bufferWidthPx x bufferHeightPx pixels — this path never calls " +
                            "resolveAnalysisBufferIntrinsics (see resolveHypothesisIntrinsics's own KDoc for " +
                            "why); the composed K' is buffer-space by direct construction of that matrix " +
                            "product, not by any resolver's own labelling",
                    bufferRotationContract = FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT,
                ),
            result = physicalPathResult,
        )

    val reconciledPath =
        FrameContentHypothesis(
            id = FrameContentMappingHypothesisId.RECONCILED_PHYSICAL_TO_LOGICAL_PATH,
            documentation =
                FrameContentHypothesisDocumentation(
                    inputKBasis = "N/A — not implemented",
                    distortionBasis = "N/A — not implemented",
                    sourceCoordinateRect = "N/A — not implemented",
                    transformsAppliedInOrder = emptyList(),
                    rotationFoldedIn = false,
                    cropRectFoldedIn = false,
                    whyOutputIsBufferCoordinates = "N/A — not implemented",
                    bufferRotationContract = "N/A — not implemented",
                ),
            result = FrameContentHypothesisIntrinsicsResult.Unavailable(FRAME_CONTENT_RECONCILED_NOT_IMPLEMENTED_REASON),
        )

    return listOf(logicalPath, physicalPath, reconciledPath)
}

private fun physicalArrayRectOrNull(snapshot: CameraCharacteristicsSnapshot): CameraBasisRect? {
    val left = snapshot.activeArrayLeftPx ?: return null
    val top = snapshot.activeArrayTopPx ?: return null
    val right = snapshot.activeArrayRightPx ?: return null
    val bottom = snapshot.activeArrayBottomPx ?: return null
    return runCatching { CameraBasisRect(left, top, right, bottom) }.getOrNull()
}
