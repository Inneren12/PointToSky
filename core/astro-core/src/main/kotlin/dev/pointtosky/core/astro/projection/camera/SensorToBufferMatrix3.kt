package dev.pointtosky.core.astro.projection.camera

import kotlin.math.abs

/**
 * A general 3x3 affine-or-projective map from Camera2 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel
 * coordinates to one CameraX `ImageAnalysis` buffer's own pixel coordinates (CAM-2c fix §1),
 * mirroring `android.graphics.Matrix`'s own 9-value layout exactly:
 * ```text
 * | m00 m01 m02 |     | MSCALE_X MSKEW_X  MTRANS_X |
 * | m10 m11 m12 |  =  | MSKEW_Y  MSCALE_Y MTRANS_Y |
 * | m20 m21 m22 |     | MPERSP_0 MPERSP_1 MPERSP_2 |
 * ```
 * applied to a homogeneous pixel column `(x, y, 1)`: `buffer = M · (activeX, activeY, 1)ᵀ`, with the
 * result's own third (`w`) component dividing back through when it is not `1` (a genuinely
 * projective map — see [classifySensorToBufferMatrix]'s `PROJECTIVE_UNSUPPORTED` bucket).
 *
 * ## Why this replaces the old axis-aligned-only `SensorToBufferTransform`
 * The previous representation (`scaleX`/`scaleY`/`translateXPx`/`translateYPx`) assumed — without
 * verifying — that Camera2's own crop+scale pipeline could only ever produce an axis-aligned
 * scale+translate. That assumption happens to hold for CameraX's own reference implementation
 * (`CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix`, built from
 * `Matrix.setRectToRect(..., ScaleToFit.CENTER)` then inverted — provably rotation- and skew-free),
 * but nothing in the public `ImageInfo.getSensorToBufferTransformMatrix()` contract *guarantees* that
 * for every CameraX version or OEM camera HAL. This type instead preserves the full 9 values Camera2
 * itself reports and defers any "is this axis-aligned?" judgement to [classifySensorToBufferMatrix],
 * so a genuinely rotated, mirrored, skewed, or projective matrix is represented exactly — never
 * silently collapsed to `null` or misrepresented as identity.
 *
 * This type carries no rotation of its own opinion about *display* orientation — it is a pure record
 * of the reported 9 values. `CameraFrameMetadata.rotationDegrees` remains the one place *display*
 * rotation is recorded, applied exactly once downstream by `CropScaleTransform`/
 * `DisplayAlignedOpticalToBufferOpticalTransform`. See
 * `dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel`'s KDoc for the proof
 * that a genuine rotation/mirror component of *this* matrix (a **position**-space active-array→buffer
 * map, resolved once per session) can never double up with that *direction*-space, per-star,
 * `rotationDegrees`-driven rotation.
 *
 * @property m00 scale/rotation X-from-X.
 * @property m01 skew/rotation X-from-Y.
 * @property m02 translate X.
 * @property m10 skew/rotation Y-from-X.
 * @property m11 scale/rotation Y-from-Y.
 * @property m12 translate Y.
 * @property m20 perspective 0. Zero for every affine map; non-zero only for a genuinely projective one.
 * @property m21 perspective 1. Zero for every affine map.
 * @property m22 perspective 2. `1` for every affine map (after Android's own normalization).
 */
data class SensorToBufferMatrix3(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
) {
    init {
        require(
            m00.isFinite() && m01.isFinite() && m02.isFinite() &&
                m10.isFinite() && m11.isFinite() && m12.isFinite() &&
                m20.isFinite() && m21.isFinite() && m22.isFinite(),
        ) {
            "all SensorToBufferMatrix3 components must be finite; was " +
                "[$m00, $m01, $m02, $m10, $m11, $m12, $m20, $m21, $m22]"
        }
    }

    /**
     * Determinant of the top-left 2x2 linear block (`m00·m11 − m01·m10`) — the sign and magnitude
     * [classifySensorToBufferMatrix] uses to distinguish orientation-preserving (proper rotation-like)
     * maps from orientation-reversing (mirrored) ones, and to detect a singular (non-invertible) map.
     * Deliberately ignores the translation ([m02]/[m12]) and perspective ([m20]/[m21]/[m22]) terms —
     * translation never affects orientation/invertibility of the linear part, and a non-affine
     * ([m20]/[m21] non-zero) matrix is rejected by [classifySensorToBufferMatrix] before this value is
     * ever trusted.
     */
    val linearDeterminant: Double get() = m00 * m11 - m01 * m10
}

/**
 * How a [SensorToBufferMatrix3] relates active-array pixel coordinates to buffer pixel coordinates,
 * as a pure geometric fact independent of *why* it has that shape (CAM-2c fix §1). Only the first four
 * buckets are ever composed with [ActiveArrayIntrinsics] by `mapActiveArrayIntrinsicsThroughMatrix`;
 * the remaining four are typed **rejections** — a caller must fall back to a documented, explicit
 * outcome (never a silently-wrong pinhole model) when it sees one of them.
 */
enum class SensorToBufferTransformClass {
    /** Pure scale + translate: `bufferX` from `activeX`, `bufferY` from `activeY`, both scales positive. */
    AXIS_ALIGNED_0,

    /**
     * A 90°-like axis permutation: `bufferX` driven by `activeY`, `bufferY` driven by `activeX`, with
     * the specific sign pattern `m01 > 0`, `m10 < 0` (see `classifySensorToBufferMatrix`'s KDoc for the
     * worked numeric example fixing this convention against [ORTHOGONAL_270]).
     */
    ORTHOGONAL_90,

    /** Pure scale + translate with both scale factors negative — orientation-preserving (`det > 0`). */
    ORTHOGONAL_180,

    /** The other 90°-like axis permutation: sign pattern `m01 < 0`, `m10 > 0`. */
    ORTHOGONAL_270,

    /** Orientation-reversing (`det < 0`) but not decomposable into one of the four buckets above. */
    MIRRORED,

    /** Affine (`m20`/`m21` ≈ 0, `m22` ≈ 1), invertible, but not axis-aligned/permuted/mirrored-clean. */
    GENERAL_AFFINE_UNSUPPORTED,

    /** `m20`/`m21` not ≈ 0, or `m22` not ≈ 1 — a genuinely projective (non-affine) map. */
    PROJECTIVE_UNSUPPORTED,

    /** `|linearDeterminant| ≈ 0` — not invertible; no active-array region maps onto the buffer 1:1. */
    SINGULAR,
}

/**
 * Default tolerance for [classifySensorToBufferMatrix]'s zero/one comparisons and determinant
 * singularity check — float noise only, never a physically meaningful margin. `1e-6` is many orders
 * of magnitude below the smallest real scale/translate coefficient any CameraX buffer mapping could
 * report (buffer and active-array dimensions are always at least tens of pixels).
 */
const val DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE: Double = 1e-6

/**
 * Classifies [matrix]'s geometric relationship (CAM-2c fix §1) using [tolerance] only to absorb
 * floating-point noise around exact zero/one/sign boundaries — never to decide "close enough to a
 * rotation" for a matrix that is genuinely skewed or projective.
 *
 * ## Decision order
 * 1. **Affine check**: [SensorToBufferMatrix3.m20]/[SensorToBufferMatrix3.m21] must be ≈0 and
 *    [SensorToBufferMatrix3.m22] ≈1, or this is [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED].
 * 2. **Singularity check**: `|linearDeterminant| <= tolerance` → [SensorToBufferTransformClass.SINGULAR].
 * 3. **Sign-pattern match** against the four supported buckets (see below).
 * 4. Otherwise: `linearDeterminant < 0` → [SensorToBufferTransformClass.MIRRORED] (orientation-reversing);
 *    else [SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED] (orientation-preserving but not a
 *    clean axis-aligned/permuted case — e.g. a genuine shear or non-90°-multiple rotation).
 *
 * ## Sign-pattern table (all comparisons against ±[tolerance])
 * ```text
 *                    m00     m01     m10     m11     linearDeterminant
 * AXIS_ALIGNED_0     >0      ≈0      ≈0      >0      +  (both scales positive)
 * ORTHOGONAL_180     <0      ≈0      ≈0      <0      +  (both scales negative; still orientation-preserving)
 * ORTHOGONAL_90      ≈0      >0      <0      ≈0      +  (worked example below)
 * ORTHOGONAL_270     ≈0      <0      >0      ≈0      +  (the other 90°-like permutation)
 * ```
 * `linearDeterminant` is shown for cross-checking only — it follows algebraically from the other four
 * columns and is never tested independently of the sign pattern (a matrix could coincidentally have
 * `det > 0` while matching none of the four sign patterns; that case correctly falls through to
 * [SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED], not [SensorToBufferTransformClass.AXIS_ALIGNED_0]).
 *
 * ## Worked numeric example fixing the [SensorToBufferTransformClass.ORTHOGONAL_90] vs
 * [SensorToBufferTransformClass.ORTHOGONAL_270] convention
 * A genuine 90°-clockwise position rotation of a `W`-wide, `H`-tall active-array rectangle onto a
 * `H`-wide, `W`-tall buffer is `bufferX = activeY`, `bufferY = W − activeX`, i.e.
 * `m00=0, m01=1, m02=0, m10=−1, m11=0, m12=W`. That matches the `m01 > 0, m10 < 0` row above, so this
 * codebase's convention calls that case [SensorToBufferTransformClass.ORTHOGONAL_90] — a naming choice
 * only; nothing downstream depends on it meaning "clockwise" specifically, only that it is applied
 * consistently between this function and `mapActiveArrayIntrinsicsThroughMatrix`'s sign-normalization
 * for the same matrix.
 */
fun classifySensorToBufferMatrix(
    matrix: SensorToBufferMatrix3,
    tolerance: Double = DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE,
): SensorToBufferTransformClass {
    require(tolerance.isFinite() && tolerance >= 0.0) { "tolerance must be finite and non-negative; was $tolerance" }

    val isAffine =
        abs(matrix.m20) <= tolerance && abs(matrix.m21) <= tolerance && abs(matrix.m22 - 1.0) <= tolerance
    if (!isAffine) return SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED

    val det = matrix.linearDeterminant
    if (abs(det) <= tolerance) return SensorToBufferTransformClass.SINGULAR

    fun isZero(value: Double) = abs(value) <= tolerance

    val m00 = matrix.m00
    val m01 = matrix.m01
    val m10 = matrix.m10
    val m11 = matrix.m11

    return when {
        isZero(m01) && isZero(m10) && m00 > tolerance && m11 > tolerance -> SensorToBufferTransformClass.AXIS_ALIGNED_0
        isZero(m01) && isZero(m10) && m00 < -tolerance && m11 < -tolerance -> SensorToBufferTransformClass.ORTHOGONAL_180
        isZero(m00) && isZero(m11) && m01 > tolerance && m10 < -tolerance -> SensorToBufferTransformClass.ORTHOGONAL_90
        isZero(m00) && isZero(m11) && m01 < -tolerance && m10 > tolerance -> SensorToBufferTransformClass.ORTHOGONAL_270
        det < 0.0 -> SensorToBufferTransformClass.MIRRORED
        else -> SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED
    }
}
