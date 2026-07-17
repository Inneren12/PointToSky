package dev.pointtosky.core.astro.projection.camera

import kotlin.math.abs

/**
 * A rectangle in whichever pixel space it was computed in (source-domain or destination-buffer),
 * produced by [assessSensorToBufferDomainConsistency] (CAM-2c domain-consistency fix). Unlike
 * [ActiveArrayRect]/[ActiveArrayLocalRect], this type deliberately carries **no** ordering invariant
 * (`leftPx <= rightPx`/`topPx <= bottomPx` is not `require`d) — a degenerate ([SensorToBufferTransformClass.SINGULAR]-like)
 * matrix can legitimately collapse all four mapped corners onto a single point or line, and that
 * degenerate result must still be representable (and then correctly flagged as a mismatch against a
 * non-degenerate expected buffer rectangle) rather than throwing.
 *
 * @property leftPx the minimum X of the rectangle's corners.
 * @property topPx the minimum Y of the rectangle's corners.
 * @property rightPx the maximum X of the rectangle's corners.
 * @property bottomPx the maximum Y of the rectangle's corners.
 */
data class SensorToBufferDomainBounds(
    val leftPx: Double,
    val topPx: Double,
    val rightPx: Double,
    val bottomPx: Double,
)

/**
 * The typed outcome of [assessSensorToBufferDomainConsistency] (CAM-2c domain-consistency fix) — a
 * **semantic** verdict, deliberately independent of [SensorToBufferTransformClass]'s own **structural**
 * verdict. A matrix classifying as [SensorToBufferTransformClass.AXIS_ALIGNED_0] only proves it is a
 * pure, positive-scale, axis-aligned affine map; it says nothing about whether that map's own
 * coefficients actually relate the reported source domain to the reported destination buffer in a
 * physically sensible way. Real Pixel 9 evidence (`docs/validation/cam_2c_pixel9_evidence.md`) is the
 * motivating counter-example: an **identity** matrix (`AXIS_ALIGNED_0`, `m00=m11=1`) reported alongside
 * a `4080x3072` active array and a `640x480` analysis buffer is structurally classifiable but cannot
 * possibly describe a real crop/scale from one domain into the other — see [MAPPED_BOUNDS_MISMATCH].
 */
enum class SensorToBufferDomainConsistency {
    /**
     * The matrix's mapped source-domain bounds match the expected destination-buffer bounds within
     * [DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX] (or the caller's own explicit tolerance).
     */
    CONSISTENT,

    /** The reported source-domain width/height was missing or not strictly positive. */
    SOURCE_DOMAIN_UNAVAILABLE,

    /** The reported analysis-buffer width/height was missing or not strictly positive. */
    BUFFER_DOMAIN_UNAVAILABLE,

    /**
     * The matrix mapped the source domain's four corners to finite bounds, but those bounds do not
     * match the expected destination-buffer bounds within tolerance — the matrix, taken at face value,
     * does not describe the source domain landing on the buffer. This is the outcome an identity matrix
     * over a `4080x3072` source and a `640x480` buffer produces; it is also the outcome a genuine,
     * legitimate active-array crop produces under this function's own deliberately strict, whole-source-
     * domain comparison (see this function's own KDoc "Scope and known limitation" section) — a
     * `MAPPED_BOUNDS_MISMATCH` verdict is therefore evidence that domain consistency is **not proven**,
     * never proof that a real, working crop is broken.
     */
    MAPPED_BOUNDS_MISMATCH,

    /**
     * The matrix's own coefficients, applied to the source domain's corners, produced a non-finite
     * (`NaN`/infinite) mapped coordinate — e.g. an extreme scale coefficient overflowing `Double` when
     * multiplied by a large source dimension. [SensorToBufferMatrix3] itself already rejects non-finite
     * *component* values at construction; this outcome instead covers a finite matrix whose mapped
     * *result* overflows.
     */
    NON_FINITE_MAPPED_BOUNDS,

    /**
     * The matrix classified as [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED] — a genuinely
     * projective map, which requires a perspective divide this function does not perform (mirroring
     * [mapActiveArrayIntrinsicsThroughMatrix]'s own refusal to compose that class). Every other
     * [SensorToBufferTransformClass] (including the four rejected by [mapActiveArrayIntrinsicsThroughMatrix]
     * — `MIRRORED`/`GENERAL_AFFINE_UNSUPPORTED`/`SINGULAR`, which are still plain affine maps) is forward-
     * mappable by ordinary matrix multiplication, so only the projective case is rejected here.
     */
    UNSUPPORTED_TRANSFORM_CLASS,
}

/**
 * The full result of [assessSensorToBufferDomainConsistency]: the typed [consistency] verdict plus the
 * evidence behind it, so a diagnostics panel or JSON export can show *why*, not just the verdict.
 *
 * @property consistency the typed verdict; see [SensorToBufferDomainConsistency].
 * @property mappedSourceBoundsPx the source domain's four corners, mapped through the matrix, as a
 *   bounding box — `null` only for [SensorToBufferDomainConsistency.SOURCE_DOMAIN_UNAVAILABLE],
 *   [SensorToBufferDomainConsistency.BUFFER_DOMAIN_UNAVAILABLE], and
 *   [SensorToBufferDomainConsistency.UNSUPPORTED_TRANSFORM_CLASS], where no mapping was attempted, and
 *   for [SensorToBufferDomainConsistency.NON_FINITE_MAPPED_BOUNDS], where the mapping itself failed.
 * @property expectedBufferBoundsPx always exactly `[0, 0, bufferWidthPx, bufferHeightPx]` when the
 *   buffer domain was available — the one destination-domain contract this codebase already commits to
 *   elsewhere (see [SensorToBufferMatrix3.toActiveArrayLocalRect]'s own use of the buffer's `[0, 0]` to
 *   `[bufferWidthPx, bufferHeightPx]` corners). `null` only for
 *   [SensorToBufferDomainConsistency.BUFFER_DOMAIN_UNAVAILABLE].
 * @property reason a short, human-readable, non-device-specific explanation — never a raw exception
 *   message, always safe to surface in a diagnostics report.
 */
data class SensorToBufferDomainConsistencyAssessment(
    val consistency: SensorToBufferDomainConsistency,
    val mappedSourceBoundsPx: SensorToBufferDomainBounds?,
    val expectedBufferBoundsPx: SensorToBufferDomainBounds?,
    val reason: String,
)

/**
 * Default tolerance, in pixels, [assessSensorToBufferDomainConsistency] uses to compare mapped source
 * bounds against expected buffer bounds. Bounded and explicit (CAM-2c domain-consistency fix) — half a
 * pixel, matching this codebase's existing sub-pixel tolerance convention (see
 * `dev.pointtosky.mobile.ar.camera.INTRINSIC_SKEW_TOLERANCE_PX`) — large enough to absorb ordinary
 * floating-point rounding from a scale computed as `bufferDimPx / sourceDimPx`, far too small to treat a
 * genuinely different domain (e.g. an identity matrix over a `4080x3072` source vs. a `640x480` buffer,
 * off by thousands of pixels) as a match.
 */
const val DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX: Double = 0.5

/**
 * Assesses whether [matrix], taken at face value, actually maps the reported source domain
 * (`[0, 0]` to `[sourceWidthPx, sourceHeightPx]`, active-array-local pixels) onto the reported
 * destination analysis buffer (`[0, 0]` to `[bufferWidthPx, bufferHeightPx]`, buffer pixels) — a
 * **semantic** check, deliberately kept separate from [classifySensorToBufferMatrix]'s own
 * **structural** classification (CAM-2c domain-consistency fix; see
 * [SensorToBufferDomainConsistency]'s own KDoc for why the two must never be conflated).
 *
 * ## Why this exists
 * A real Pixel 9 `internalDebug` run recorded `matrixClass=AXIS_ALIGNED_0`,
 * `framesWithSupportedTransformClass=1751/1751`, and a reported sensor-to-buffer matrix that was the
 * **identity** (`m00=m11=1`, every translate/skew/perspective term `0`) alongside a `4080x3072` active
 * array and a `640x480` `ImageAnalysis` buffer (`docs/validation/cam_2c_pixel9_evidence.md`). An
 * identity matrix cannot map a `4080x3072` domain onto a `640x480` domain — no scaling is applied at
 * all — yet the pre-existing diagnostics only ever reported [SensorToBufferTransformClass.AXIS_ALIGNED_0]
 * (a purely structural fact: positive scale, no skew) and a raw frame count, neither of which says
 * anything about whether the matrix's own *numbers* are usable. This function closes that gap.
 *
 * ## What "consistent" means here
 * [matrix]'s affine coefficients are applied directly (no inversion — this is the same forward
 * direction [matrix] itself is documented to describe, active-array-local pixels to buffer pixels) to
 * the source rectangle's four corners; the bounding box of the four mapped points is [SensorToBufferDomainConsistencyAssessment.mappedSourceBoundsPx].
 * That is compared, within [tolerancePx], against the buffer's own `[0, 0]` to `[bufferWidthPx,
 * bufferHeightPx]` rectangle ([SensorToBufferDomainConsistencyAssessment.expectedBufferBoundsPx]). A
 * match is [SensorToBufferDomainConsistency.CONSISTENT]; anything else is
 * [SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH].
 *
 * ## Scope and known limitation — this is intentionally strict, not a general crop-validity oracle
 * Forward-mapping the **whole** reported source domain and requiring it to land **exactly** on the
 * buffer means a matrix that legitimately maps only a *sub-region* of the source domain onto the buffer
 * (an ordinary aspect-ratio crop — extremely common on real devices, since a sensor's native aspect
 * ratio rarely matches an analysis buffer's exactly) will also be reported as
 * [SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH] here, even though that matrix may be perfectly
 * correct. This is a deliberate, honest design choice, not an oversight: this codebase does not
 * currently have a proven, documented contract for what a *cropped* source-to-buffer relationship should
 * look like (see `dev.pointtosky.mobile.ar.camera.AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven`'s
 * KDoc for the same "the math exists but is not proven correct for a real device" pattern applied to
 * rotation). Only the trivial, no-crop, whole-domain-to-whole-buffer relationship is provably checkable
 * against this codebase's own existing coordinate contract today (the same `[0, 0]` to `[bufferWidthPx,
 * bufferHeightPx]` destination rectangle [SensorToBufferMatrix3.toActiveArrayLocalRect] already inverts
 * against). Consequently: [SensorToBufferDomainConsistency.CONSISTENT] is strong positive evidence;
 * [SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH] is evidence domain consistency is **not
 * proven**, never proof the transform is wrong. Callers must not treat `MAPPED_BOUNDS_MISMATCH` as
 * grounds to reject an otherwise-valid, already-resolved calibration — see
 * `dev.pointtosky.mobile.ar.camera.AnalysisBufferIntrinsicsResolution.DomainConsistencyUnproven`'s own
 * KDoc for why this codebase does not (yet) wire this assessment into resolution's control flow.
 *
 * ## Why the transform class is derived, not accepted as a parameter
 * [SensorToBufferTransformClass] is derived here via [classifySensorToBufferMatrix] applied to [matrix]
 * itself, rather than accepted as a second, independent parameter — so this assessment can never
 * disagree with [matrix]'s own classification (a caller-supplied, possibly-stale class could otherwise
 * drift from the matrix it is meant to describe). Only [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED]
 * is rejected outright ([SensorToBufferDomainConsistency.UNSUPPORTED_TRANSFORM_CLASS]) — every other
 * class (including the three [mapActiveArrayIntrinsicsThroughMatrix] itself refuses to compose —
 * `MIRRORED`/`GENERAL_AFFINE_UNSUPPORTED`/`SINGULAR`) is still an ordinary affine map, forward-mappable
 * by direct matrix multiplication with no inversion, so this function can and does assess all of them.
 *
 * ## Crop rectangle is deliberately not a parameter
 * A per-frame buffer-local crop rectangle (`CameraFrameMetadata.cropRectLeftPx`/etc.,
 * `ImageProxy.cropRect`) is **not** accepted here: it is documented, elsewhere in this codebase
 * (`dev.pointtosky.core.astro.projection.camera.ActiveArrayRect`'s own KDoc), to live in a *different*
 * coordinate space (buffer-local) than the active-array-local source domain this function compares
 * against — folding it in here would repeat exactly the coordinate-space conflation that KDoc warns
 * against, not add real information about the active-array-to-buffer relationship this function assesses.
 *
 * @param sourceWidthPx the source domain's width, active-array-local pixels — `null`/non-positive is
 *   reported as [SensorToBufferDomainConsistency.SOURCE_DOMAIN_UNAVAILABLE].
 * @param sourceHeightPx the source domain's height, the [sourceWidthPx] analogue.
 * @param bufferWidthPx the destination analysis buffer's width, pixels — `null`/non-positive is
 *   reported as [SensorToBufferDomainConsistency.BUFFER_DOMAIN_UNAVAILABLE].
 * @param bufferHeightPx the destination analysis buffer's height, the [bufferWidthPx] analogue.
 * @param tolerancePx bounded, explicit comparison tolerance in pixels; see
 *   [DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX].
 * @throws IllegalArgumentException if [tolerancePx] is not finite and non-negative.
 */
fun assessSensorToBufferDomainConsistency(
    matrix: SensorToBufferMatrix3,
    sourceWidthPx: Int?,
    sourceHeightPx: Int?,
    bufferWidthPx: Int?,
    bufferHeightPx: Int?,
    tolerancePx: Double = DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX,
): SensorToBufferDomainConsistencyAssessment {
    require(tolerancePx.isFinite() && tolerancePx >= 0.0) {
        "tolerancePx must be finite and non-negative; was $tolerancePx"
    }

    if (sourceWidthPx == null || sourceHeightPx == null || sourceWidthPx <= 0 || sourceHeightPx <= 0) {
        return SensorToBufferDomainConsistencyAssessment(
            consistency = SensorToBufferDomainConsistency.SOURCE_DOMAIN_UNAVAILABLE,
            mappedSourceBoundsPx = null,
            expectedBufferBoundsPx = null,
            reason = "source domain width/height missing or not strictly positive " +
                "(sourceWidthPx=$sourceWidthPx, sourceHeightPx=$sourceHeightPx)",
        )
    }
    if (bufferWidthPx == null || bufferHeightPx == null || bufferWidthPx <= 0 || bufferHeightPx <= 0) {
        return SensorToBufferDomainConsistencyAssessment(
            consistency = SensorToBufferDomainConsistency.BUFFER_DOMAIN_UNAVAILABLE,
            mappedSourceBoundsPx = null,
            expectedBufferBoundsPx = null,
            reason = "analysis buffer width/height missing or not strictly positive " +
                "(bufferWidthPx=$bufferWidthPx, bufferHeightPx=$bufferHeightPx)",
        )
    }

    val transformClass = classifySensorToBufferMatrix(matrix)
    if (transformClass == SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED) {
        return SensorToBufferDomainConsistencyAssessment(
            consistency = SensorToBufferDomainConsistency.UNSUPPORTED_TRANSFORM_CLASS,
            mappedSourceBoundsPx = null,
            expectedBufferBoundsPx = null,
            reason = "transform class $transformClass requires a perspective divide this assessment does not perform",
        )
    }

    val sourceW = sourceWidthPx.toDouble()
    val sourceH = sourceHeightPx.toDouble()
    fun mappedX(x: Double, y: Double) = matrix.m00 * x + matrix.m01 * y + matrix.m02
    fun mappedY(x: Double, y: Double) = matrix.m10 * x + matrix.m11 * y + matrix.m12
    val xs = doubleArrayOf(mappedX(0.0, 0.0), mappedX(sourceW, 0.0), mappedX(0.0, sourceH), mappedX(sourceW, sourceH))
    val ys = doubleArrayOf(mappedY(0.0, 0.0), mappedY(sourceW, 0.0), mappedY(0.0, sourceH), mappedY(sourceW, sourceH))
    if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) {
        return SensorToBufferDomainConsistencyAssessment(
            consistency = SensorToBufferDomainConsistency.NON_FINITE_MAPPED_BOUNDS,
            mappedSourceBoundsPx = null,
            expectedBufferBoundsPx = null,
            reason = "mapping the source domain's corners through the matrix produced a non-finite coordinate",
        )
    }

    val mapped =
        SensorToBufferDomainBounds(
            leftPx = xs.min(),
            topPx = ys.min(),
            rightPx = xs.max(),
            bottomPx = ys.max(),
        )
    val expected =
        SensorToBufferDomainBounds(
            leftPx = 0.0,
            topPx = 0.0,
            rightPx = bufferWidthPx.toDouble(),
            bottomPx = bufferHeightPx.toDouble(),
        )
    val isConsistent =
        abs(mapped.leftPx - expected.leftPx) <= tolerancePx &&
            abs(mapped.topPx - expected.topPx) <= tolerancePx &&
            abs(mapped.rightPx - expected.rightPx) <= tolerancePx &&
            abs(mapped.bottomPx - expected.bottomPx) <= tolerancePx

    return SensorToBufferDomainConsistencyAssessment(
        consistency = if (isConsistent) SensorToBufferDomainConsistency.CONSISTENT else SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH,
        mappedSourceBoundsPx = mapped,
        expectedBufferBoundsPx = expected,
        reason =
            if (isConsistent) {
                "mapped source bounds match the expected buffer bounds within ${tolerancePx}px"
            } else {
                "mapped source bounds [${mapped.leftPx},${mapped.topPx} — ${mapped.rightPx},${mapped.bottomPx}] do not " +
                    "match expected buffer bounds [0,0 — ${expected.rightPx},${expected.bottomPx}] within ${tolerancePx}px"
            },
    )
}
