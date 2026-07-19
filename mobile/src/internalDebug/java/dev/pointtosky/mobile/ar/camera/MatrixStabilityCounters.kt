package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.math.abs
import kotlin.math.hypot

/**
 * `internalDebug`-only. Per-attempt sensor-to-buffer matrix stability tracking (task §10) — pure,
 * Android-free, reduced from each [CameraFrameMetadata] by [reduceMatrixStability]. Generation
 * scoping is inherited from [ExperimentSessionState]: these counters live inside one attempt's
 * session state, and every reducer is `attemptId`-guarded there, so a late frame from a superseded
 * attempt can never update a newer attempt's counters.
 *
 * ## Two explicitly separated change notions (P2 fix — unit- and magnitude-honest thresholds)
 * A single raw-coefficient tolerance cannot be honest across coefficient kinds: at translation
 * magnitudes around ±400 px a float32 ULP is ≈ 6e-5, so a `1e-6` per-coefficient bound would flag
 * ordinary float storage as "change", while at scale magnitudes (~0.15) the same bound is ~7 ULPs.
 * This type therefore tracks two distinct things and names them for exactly what they are:
 *
 * 1. **Bitwise change** ([MatrixStabilityCounters.bitwiseMatrixChanges]): the reported matrix's
 *    nine values are not exactly equal to the previous frame's. Since every value is a widened
 *    `android.graphics.Matrix` float32, a re-delivery of the *same* platform matrix reproduces the
 *    same bits — any inequality at all means CameraX/the HAL actually reported different numbers.
 *    No tolerance is involved, so no magnitude dishonesty is possible.
 * 2. **Geometrically meaningful change**
 *    ([MatrixStabilityCounters.mappedDisplacementChangesBeyondTolerance] /
 *    [MatrixStabilityCounters.maxMappedDisplacementFromFirstPx]): the maximum **mapped-point
 *    displacement in buffer pixels** over a fixed, documented reference rectangle
 *    ([MATRIX_STABILITY_REFERENCE_WIDTH_PX] × [MATRIX_STABILITY_REFERENCE_HEIGHT_PX], corners
 *    evaluated through each matrix's affine rows), compared against
 *    [MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX]. Pixels are the unit the diagnostic
 *    actually cares about, and the threshold is derived from float32 error at reference-rect
 *    magnitudes — see the constant's own KDoc. (For a projective matrix the affine rows alone are
 *    not its full mapping; this displacement is then a stability diagnostic on the affine part
 *    only, alongside the structural story `transformClass` already tells.)
 *
 * The raw [MatrixStabilityCounters.maxCoefficientDeltaFromFirst] is retained as an untolerated
 * diagnostic number (no threshold is applied to it), and the exports carry the threshold names and
 * values so a device report is self-describing.
 */
internal data class MatrixStabilityCounters(
    val firstMatrix: SensorToBufferMatrix3? = null,
    val latestMatrix: SensorToBufferMatrix3? = null,
    val framesObserved: Long = 0L,
    val framesWithNullTransform: Long = 0L,
    val bitwiseMatrixChanges: Long = 0L,
    val mappedDisplacementChangesBeyondTolerance: Long = 0L,
    val maxMappedDisplacementFromFirstPx: Double = 0.0,
    val maxCoefficientDeltaFromFirst: Double = 0.0,
    val firstBufferWidthPx: Int? = null,
    val firstBufferHeightPx: Int? = null,
    val firstRotationDegrees: Int? = null,
    val firstCropRectLeftPx: Int? = null,
    val firstCropRectTopPx: Int? = null,
    val firstCropRectRightPx: Int? = null,
    val firstCropRectBottomPx: Int? = null,
    val dimensionsCropOrRotationChanged: Boolean = false,
)

/** Fixed reference-rectangle width for the mapped-displacement stability metric — spans realistic
 * active-array magnitudes so translation-scale float effects are evaluated where they actually
 * occur, not at a flattering small magnitude. */
internal const val MATRIX_STABILITY_REFERENCE_WIDTH_PX: Int = 4096

/** Fixed reference-rectangle height, the [MATRIX_STABILITY_REFERENCE_WIDTH_PX] analogue. */
internal const val MATRIX_STABILITY_REFERENCE_HEIGHT_PX: Int = 3072

/**
 * Mapped-point displacement (buffer pixels, over the reference rectangle) at or below which two
 * matrices are geometrically "the same mapping" for stability purposes. Derivation at reference
 * magnitudes: float32 ULP of a ~±400 px translation ≈ 6e-5 px; scale-coefficient ULP (~1.5e-8 at
 * 0.15) × 4096 ≈ 6e-5 px — so unavoidable float-storage jitter between *equivalent* matrices stays
 * ≤ ~2e-4 px. `1e-3` px gives ≥ 5× headroom over that jitter while sitting ≥ 900× below the
 * smallest geometrically meaningful difference this diagnostic has measured (the 0.941 px/side
 * Pixel 9 crop). Same reasoning family as [DEFAULT_FLOAT_NOISE_TOLERANCE_PX].
 */
internal const val MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX: Double = 1e-3

private fun maxAbsCoefficientDelta(a: SensorToBufferMatrix3, b: SensorToBufferMatrix3): Double =
    listOf(
        a.m00 - b.m00, a.m01 - b.m01, a.m02 - b.m02,
        a.m10 - b.m10, a.m11 - b.m11, a.m12 - b.m12,
        a.m20 - b.m20, a.m21 - b.m21, a.m22 - b.m22,
    ).maxOf { abs(it) }

/** Max Euclidean displacement, in buffer pixels, between [a]'s and [b]'s affine-row mappings of the
 * fixed reference rectangle's four corners. */
internal fun maxMappedDisplacementOverReferenceRectPx(
    a: SensorToBufferMatrix3,
    b: SensorToBufferMatrix3,
): Double {
    val w = MATRIX_STABILITY_REFERENCE_WIDTH_PX.toDouble()
    val h = MATRIX_STABILITY_REFERENCE_HEIGHT_PX.toDouble()
    val corners = listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h)
    return corners.maxOf { (x, y) ->
        val dx = (a.m00 * x + a.m01 * y + a.m02) - (b.m00 * x + b.m01 * y + b.m02)
        val dy = (a.m10 * x + a.m11 * y + a.m12) - (b.m10 * x + b.m11 * y + b.m12)
        hypot(dx, dy)
    }
}

/**
 * Applies one analyzed [frame] to these counters. First-frame identity (buffer size, crop rect,
 * rotation) is latched from the first frame ever observed; any later frame differing in those fields
 * flips [MatrixStabilityCounters.dimensionsCropOrRotationChanged] permanently for this attempt (a
 * resolution/aspect switch must instead start a fresh attempt/generation — task §11 — so this flag
 * reading `true` is itself evidence of an incoherent session).
 */
internal fun MatrixStabilityCounters.reduceMatrixStability(frame: CameraFrameMetadata): MatrixStabilityCounters {
    val matrix = frame.sensorToBufferTransform
    val framesObserved = framesObserved + 1
    val framesWithNullTransform = framesWithNullTransform + (if (matrix == null) 1 else 0)

    val first = firstMatrix ?: matrix
    val previous = latestMatrix
    // Bitwise: exact inequality of the widened float32 values — no tolerance, so no magnitude bias.
    val bitwiseChanged = matrix != null && previous != null && matrix != previous
    // Geometric: mapped-pixel displacement vs the previous matrix over the fixed reference rect.
    val geometricallyChanged =
        matrix != null && previous != null &&
            maxMappedDisplacementOverReferenceRectPx(matrix, previous) > MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX
    val maxDisplacementFromFirst =
        if (matrix != null && first != null) {
            maxOf(maxMappedDisplacementFromFirstPx, maxMappedDisplacementOverReferenceRectPx(matrix, first))
        } else {
            maxMappedDisplacementFromFirstPx
        }
    val maxDelta =
        if (matrix != null && first != null) {
            maxOf(maxCoefficientDeltaFromFirst, maxAbsCoefficientDelta(matrix, first))
        } else {
            maxCoefficientDeltaFromFirst
        }

    val isFirstFrame = this.framesObserved == 0L
    val identityChanged =
        !isFirstFrame && (
            frame.bufferWidthPx != firstBufferWidthPx ||
                frame.bufferHeightPx != firstBufferHeightPx ||
                frame.rotationDegrees != firstRotationDegrees ||
                frame.cropRectLeftPx != firstCropRectLeftPx ||
                frame.cropRectTopPx != firstCropRectTopPx ||
                frame.cropRectRightPx != firstCropRectRightPx ||
                frame.cropRectBottomPx != firstCropRectBottomPx
            )

    return copy(
        firstMatrix = first,
        latestMatrix = matrix ?: latestMatrix,
        framesObserved = framesObserved,
        framesWithNullTransform = framesWithNullTransform,
        bitwiseMatrixChanges = bitwiseMatrixChanges + (if (bitwiseChanged) 1 else 0),
        mappedDisplacementChangesBeyondTolerance =
            mappedDisplacementChangesBeyondTolerance + (if (geometricallyChanged) 1 else 0),
        maxMappedDisplacementFromFirstPx = maxDisplacementFromFirst,
        maxCoefficientDeltaFromFirst = maxDelta,
        firstBufferWidthPx = firstBufferWidthPx ?: frame.bufferWidthPx.takeIf { isFirstFrame },
        firstBufferHeightPx = firstBufferHeightPx ?: frame.bufferHeightPx.takeIf { isFirstFrame },
        firstRotationDegrees = firstRotationDegrees ?: frame.rotationDegrees.takeIf { isFirstFrame },
        firstCropRectLeftPx = if (isFirstFrame) frame.cropRectLeftPx else firstCropRectLeftPx,
        firstCropRectTopPx = if (isFirstFrame) frame.cropRectTopPx else firstCropRectTopPx,
        firstCropRectRightPx = if (isFirstFrame) frame.cropRectRightPx else firstCropRectRightPx,
        firstCropRectBottomPx = if (isFirstFrame) frame.cropRectBottomPx else firstCropRectBottomPx,
        dimensionsCropOrRotationChanged = dimensionsCropOrRotationChanged || identityChanged,
    )
}
