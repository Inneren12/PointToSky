package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.math.abs

/**
 * `internalDebug`-only. Per-attempt sensor-to-buffer matrix stability tracking (task §10) — pure,
 * Android-free, reduced from each [CameraFrameMetadata] by [reduceMatrixStability]. Generation
 * scoping is inherited from [ExperimentSessionState]: these counters live inside one attempt's
 * session state, and every reducer is `attemptId`-guarded there, so a late frame from a superseded
 * attempt can never update a newer attempt's counters.
 *
 * Coefficient deltas are compared against [MATRIX_STABILITY_FLOAT_NOISE_TOLERANCE] — the same
 * float-storage-noise reasoning as [DEFAULT_FLOAT_NOISE_TOLERANCE_PX], applied to raw coefficients
 * rather than mapped pixels: matrix coefficients at CameraX magnitudes (|value| ≤ ~4096) carry
 * float32 ULPs ≤ ~2.4e-4, and a *re-computed identical* matrix reproduces bit-identical floats, so
 * any delta beyond `1e-6` on scale terms would already be suspicious; `1e-6` is kept for scale-sized
 * terms while translations (magnitude ≤ ~4096) get the same absolute bound as a deliberate,
 * conservative choice — a real basis/resolution change shifts coefficients by ≥ 1e-3.
 */
internal data class MatrixStabilityCounters(
    val firstMatrix: SensorToBufferMatrix3? = null,
    val latestMatrix: SensorToBufferMatrix3? = null,
    val framesObserved: Long = 0L,
    val framesWithNullTransform: Long = 0L,
    val changesBeyondFloatNoise: Long = 0L,
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

/** Absolute per-coefficient delta below which two observed matrices are treated as the same float
 * value (see [MatrixStabilityCounters]'s KDoc for the numeric reasoning). */
internal const val MATRIX_STABILITY_FLOAT_NOISE_TOLERANCE: Double = 1e-6

private fun maxAbsCoefficientDelta(a: SensorToBufferMatrix3, b: SensorToBufferMatrix3): Double =
    listOf(
        a.m00 - b.m00, a.m01 - b.m01, a.m02 - b.m02,
        a.m10 - b.m10, a.m11 - b.m11, a.m12 - b.m12,
        a.m20 - b.m20, a.m21 - b.m21, a.m22 - b.m22,
    ).maxOf { abs(it) }

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
    val changed =
        matrix != null && previous != null &&
            maxAbsCoefficientDelta(matrix, previous) > MATRIX_STABILITY_FLOAT_NOISE_TOLERANCE
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
        changesBeyondFloatNoise = changesBeyondFloatNoise + (if (changed) 1 else 0),
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
