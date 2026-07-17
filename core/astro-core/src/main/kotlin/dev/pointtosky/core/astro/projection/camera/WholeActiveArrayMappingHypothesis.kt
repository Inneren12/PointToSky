package dev.pointtosky.core.astro.projection.camera

import kotlin.math.abs

/**
 * A rectangle in whichever pixel space it was computed in (assumed source domain or destination
 * buffer), produced by [assessWholeActiveArrayMappingHypothesis]. Unlike [ActiveArrayRect]/
 * [ActiveArrayLocalRect], this type deliberately carries **no** ordering invariant (`leftPx <=
 * rightPx`/`topPx <= bottomPx` is not `require`d) — a degenerate ([SensorToBufferTransformClass.SINGULAR]-like)
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
 * Which coordinate-space *assumption* [assessWholeActiveArrayMappingHypothesis] tested as the source
 * domain of a [SensorToBufferMatrix3]. This exists because the public CameraX/Camera2 contract for
 * `ImageInfo.getSensorToBufferTransformMatrix()`'s own source domain has **not** been source-traced or
 * device-proven in this codebase at the pinned `androidx.camera:camera-camera2:1.3.4` version: it is not
 * established here whether the matrix always maps the *complete* `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-local
 * rectangle, or some already-cropped/pre-normalized sub-region of it, into the analysis buffer. Every
 * verdict [assessWholeActiveArrayMappingHypothesis] returns is conditioned on whichever basis this field
 * names — a caller must read the verdict as "does this matrix match *this* hypothesis," never as "is
 * this matrix valid" in any absolute sense.
 */
enum class SourceDomainBasis {
    /**
     * The only basis this codebase currently tests: that the matrix's source domain is the *complete*
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-local rectangle (`[0, 0]` to `[activeArrayWidthPx,
     * activeArrayHeightPx]`), unmodified by any crop or pre-normalization. A future pass that
     * source-traces or device-proves the pinned CameraX version's actual contract — or that discovers a
     * legitimate cropped/pre-normalized source domain — may add further [SourceDomainBasis] values and
     * a corresponding assessment against each; none exist yet.
     */
    ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL,
}

/**
 * The typed outcome of [assessWholeActiveArrayMappingHypothesis] — **not** a general verdict on whether
 * a [SensorToBufferMatrix3] is valid, usable, or semantically consistent with the real CameraX pipeline.
 * It tests exactly one thing: does this matrix, applied to the [SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL]
 * hypothesis (the *complete* active array, uncropped), land on the reported analysis buffer's own `[0,
 * 0]` to `[bufferWidthPx, bufferHeightPx]` rectangle? A mismatch is evidence *that hypothesis* does not
 * hold for this matrix — it is never evidence the matrix itself is broken, invalid, unusable, or
 * semantically impossible: this codebase has not proven what the pinned CameraX version's real source
 * domain is, so a legitimate, correctly-functioning cropped or pre-normalized source domain remains a
 * live possibility this assessment cannot rule out. See [assessWholeActiveArrayMappingHypothesis]'s own
 * KDoc for the full scope note.
 */
enum class WholeActiveArrayHypothesisVerdict {
    /**
     * The matrix's mapped-assumed-source bounds match the expected destination-buffer bounds within
     * [DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX] (or the caller's own explicit tolerance) —
     * this matrix is consistent with the whole-active-array hypothesis. Positive evidence for that one
     * hypothesis; not a general "this matrix is correct" claim.
     */
    MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS,

    /** The reported source-domain (active-array) width/height was missing or not strictly positive. */
    SOURCE_METADATA_UNAVAILABLE,

    /** The reported analysis-buffer width/height was missing or not strictly positive. */
    BUFFER_METADATA_UNAVAILABLE,

    /**
     * The matrix mapped the whole-active-array hypothesis's four corners to finite bounds, but those
     * bounds do not match the expected destination-buffer bounds within tolerance. This is the outcome
     * an identity matrix over a `4080x3072` active array and a `640x480` buffer produces; it is also the
     * outcome a legitimate, already-cropped or pre-normalized source domain would produce, since this
     * codebase has not proven whether the pinned CameraX version's matrix ever uses one. **Read this
     * verdict as "does not match the whole-active-array hypothesis," never as "this matrix is broken,
     * invalid, unusable, or known not to describe the real pipeline."**
     */
    WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH,

    /**
     * The matrix's own coefficients, applied to the hypothesis's source-domain corners, produced a
     * non-finite (`NaN`/infinite) mapped coordinate — e.g. an extreme scale coefficient overflowing
     * `Double` when multiplied by a large source dimension. [SensorToBufferMatrix3] itself already
     * rejects non-finite *component* values at construction; this outcome instead covers a finite matrix
     * whose mapped *result* overflows.
     */
    NON_FINITE_MAPPED_BOUNDS,

    /**
     * The matrix classified as [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED] — a genuinely
     * projective map, which requires a perspective divide this function does not perform (mirroring
     * [mapActiveArrayIntrinsicsThroughMatrix]'s own refusal to compose that class). Every other
     * [SensorToBufferTransformClass] (including the three [mapActiveArrayIntrinsicsThroughMatrix] itself
     * refuses to compose — `MIRRORED`/`GENERAL_AFFINE_UNSUPPORTED`/`SINGULAR`) is still an ordinary
     * affine map, forward-mappable by direct matrix multiplication with no inversion, so this function
     * can and does test the hypothesis against all of them.
     */
    UNSUPPORTED_TRANSFORM_CLASS,
}

/**
 * The full result of [assessWholeActiveArrayMappingHypothesis]: the typed [verdict], the
 * [sourceDomainBasis] it was tested against, and the evidence behind it, so a diagnostics panel or JSON
 * export can show *which hypothesis* was tested and *why* it did or did not hold — never just a bare
 * verdict a reader could mistake for a general validity claim.
 *
 * @property verdict the typed outcome; see [WholeActiveArrayHypothesisVerdict].
 * @property sourceDomainBasis always [SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL] as of this
 *   function — carried explicitly (never omitted, regardless of [verdict]) so a reader never has to
 *   assume which hypothesis was tested.
 * @property mappedAssumedSourceBoundsPx the assumed source domain's four corners, mapped through the
 *   matrix, as a bounding box — `null` when no mapping was attempted or the mapping itself failed:
 *   [WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE],
 *   [WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE],
 *   [WholeActiveArrayHypothesisVerdict.UNSUPPORTED_TRANSFORM_CLASS], and
 *   [WholeActiveArrayHypothesisVerdict.NON_FINITE_MAPPED_BOUNDS].
 * @property expectedBufferBoundsPx always exactly `[0, 0, bufferWidthPx, bufferHeightPx]` whenever a
 *   valid (strictly positive) buffer width/height was supplied — preserved even when [verdict] is
 *   [WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE],
 *   [WholeActiveArrayHypothesisVerdict.UNSUPPORTED_TRANSFORM_CLASS], or
 *   [WholeActiveArrayHypothesisVerdict.NON_FINITE_MAPPED_BOUNDS], since the buffer's own rectangle is
 *   independent of whether the source-side assessment could complete. `null` **only** for
 *   [WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE], where no valid buffer rectangle
 *   exists to report at all.
 * @property reason a short, human-readable, non-device-specific explanation — never a raw exception
 *   message, always safe to surface in a diagnostics report, and never phrased as a claim that the
 *   matrix itself is invalid.
 */
data class WholeActiveArrayMappingAssessment(
    val verdict: WholeActiveArrayHypothesisVerdict,
    val sourceDomainBasis: SourceDomainBasis,
    val mappedAssumedSourceBoundsPx: SensorToBufferDomainBounds?,
    val expectedBufferBoundsPx: SensorToBufferDomainBounds?,
    val reason: String,
)

/**
 * Default tolerance, in pixels, [assessWholeActiveArrayMappingHypothesis] uses to compare mapped-assumed-
 * source bounds against expected buffer bounds. Bounded and explicit — half a pixel, matching this
 * codebase's existing sub-pixel tolerance convention (see
 * `dev.pointtosky.mobile.ar.camera.INTRINSIC_SKEW_TOLERANCE_PX`) — large enough to absorb ordinary
 * floating-point rounding from a scale computed as `bufferDimPx / sourceDimPx`, far too small to treat a
 * genuinely different domain as a match.
 */
const val DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX: Double = 0.5

/**
 * Tests one specific, named hypothesis about [matrix]'s source domain — that it is the *complete*
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-local rectangle (`[0, 0]` to `[sourceWidthPx, sourceHeightPx]`,
 * [SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL]) — against the reported destination analysis
 * buffer (`[0, 0]` to `[bufferWidthPx, bufferHeightPx]`, buffer pixels). This is **not** a general
 * validity or semantic-consistency check on [matrix]; see "Scope and provenance" below for exactly what
 * it does and does not establish.
 *
 * ## Why this exists
 * A real Pixel 9 `internalDebug` run recorded `matrixClass=AXIS_ALIGNED_0`,
 * `framesWithSupportedTransformClass=1751/1751`, and a reported sensor-to-buffer matrix that was the
 * **identity** (`m00=m11=1`, every translate/skew/perspective term `0`) alongside a `4080x3072` active
 * array and a `640x480` `ImageAnalysis` buffer (`docs/validation/cam_2c_pixel9_evidence.md`). The
 * pre-existing diagnostics only ever reported [SensorToBufferTransformClass.AXIS_ALIGNED_0] (a purely
 * structural fact: positive scale, no skew) and a raw frame count, neither of which says anything about
 * whether the matrix's own *numbers*, under any particular source-domain assumption, actually relate to
 * this device's reported active-array/buffer dimensions. This function closes *that* gap for exactly one,
 * explicitly named assumption — it does not, and cannot yet, close it for every possible assumption.
 *
 * ## Scope and provenance — read this before treating a mismatch as a defect
 * The pinned `androidx.camera:camera-camera2:1.3.4` implementation's actual source-domain contract for
 * `ImageInfo.getSensorToBufferTransformMatrix()` has **not** been source-traced or device-proven in this
 * codebase. It is not established here whether that matrix always maps the *complete* active array, or
 * some already-cropped/pre-normalized sub-region of it (a legitimate, correctly-functioning device could
 * plausibly report an identity matrix if its own source domain were already normalized to match the
 * buffer before this matrix is even computed). [SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL] is
 * this function's one, explicit, named assumption — never a proven fact about CameraX. Consequently:
 * - [WholeActiveArrayHypothesisVerdict.MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS] is evidence *this*
 *   hypothesis holds for this matrix — still not a general "the matrix is correct" claim, since a
 *   coincidental match under a wrong hypothesis is not ruled out either.
 * - [WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH] is evidence *this*
 *   hypothesis does **not** hold — it must never be read as "the matrix is broken, invalid, unusable, or
 *   known not to describe the real pipeline." A legitimate cropped/pre-normalized source domain would
 *   produce the exact same verdict.
 * A future pass that source-traces or device-proves the pinned CameraX version's real contract (or an
 * upgraded version's) may add further [SourceDomainBasis] values and assess against each; until then,
 * this function tests exactly the one hypothesis its own name describes, honestly labelled as such in
 * every field, text label, and JSON key it feeds.
 *
 * ## What is compared
 * [matrix]'s affine coefficients are applied directly (no inversion — this is the same forward direction
 * [matrix] itself is documented to describe) to the assumed source rectangle's four corners; the bounding
 * box of the four mapped points is [WholeActiveArrayMappingAssessment.mappedAssumedSourceBoundsPx]. That
 * is compared, within [tolerancePx], against the buffer's own `[0, 0]` to `[bufferWidthPx,
 * bufferHeightPx]` rectangle ([WholeActiveArrayMappingAssessment.expectedBufferBoundsPx]).
 *
 * ## Why the transform class is derived, not accepted as a parameter
 * [SensorToBufferTransformClass] is derived here via [classifySensorToBufferMatrix] applied to [matrix]
 * itself, rather than accepted as a second, independent parameter — so this assessment can never disagree
 * with [matrix]'s own classification. Only [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED] is
 * rejected outright ([WholeActiveArrayHypothesisVerdict.UNSUPPORTED_TRANSFORM_CLASS]) — every other class
 * (including the three [mapActiveArrayIntrinsicsThroughMatrix] itself refuses to compose —
 * `MIRRORED`/`GENERAL_AFFINE_UNSUPPORTED`/`SINGULAR`) is still an ordinary affine map, forward-mappable by
 * direct matrix multiplication with no inversion, so this function can and does test all of them.
 *
 * ## Crop rectangle is deliberately not a parameter
 * A per-frame buffer-local crop rectangle (`CameraFrameMetadata.cropRectLeftPx`/etc.,
 * `ImageProxy.cropRect`) is **not** accepted here: it is documented, elsewhere in this codebase
 * (`dev.pointtosky.core.astro.projection.camera.ActiveArrayRect`'s own KDoc), to live in a *different*
 * coordinate space (buffer-local) than the active-array-local source domain this function assumes —
 * folding it in here would repeat exactly the coordinate-space conflation that KDoc warns against, not
 * add real information about which source-domain hypothesis actually holds.
 *
 * @param sourceWidthPx the assumed source domain's width, active-array-local pixels — `null`/non-positive
 *   is reported as [WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE].
 * @param sourceHeightPx the assumed source domain's height, the [sourceWidthPx] analogue.
 * @param bufferWidthPx the destination analysis buffer's width, pixels — `null`/non-positive is reported
 *   as [WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE].
 * @param bufferHeightPx the destination analysis buffer's height, the [bufferWidthPx] analogue.
 * @param tolerancePx bounded, explicit comparison tolerance in pixels; see
 *   [DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX].
 * @throws IllegalArgumentException if [tolerancePx] is not finite and non-negative.
 */
fun assessWholeActiveArrayMappingHypothesis(
    matrix: SensorToBufferMatrix3,
    sourceWidthPx: Int?,
    sourceHeightPx: Int?,
    bufferWidthPx: Int?,
    bufferHeightPx: Int?,
    tolerancePx: Double = DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX,
): WholeActiveArrayMappingAssessment {
    require(tolerancePx.isFinite() && tolerancePx >= 0.0) {
        "tolerancePx must be finite and non-negative; was $tolerancePx"
    }

    val basis = SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL

    val bufferValid = bufferWidthPx != null && bufferHeightPx != null && bufferWidthPx > 0 && bufferHeightPx > 0
    // Computed as soon as the buffer domain is known valid, independent of the source-domain checks
    // below - the buffer's own rectangle does not depend on whether the source-side assessment can
    // complete (see WholeActiveArrayMappingAssessment.expectedBufferBoundsPx's own KDoc contract).
    val expectedBounds =
        if (bufferValid) {
            SensorToBufferDomainBounds(leftPx = 0.0, topPx = 0.0, rightPx = bufferWidthPx!!.toDouble(), bottomPx = bufferHeightPx!!.toDouble())
        } else {
            null
        }

    if (sourceWidthPx == null || sourceHeightPx == null || sourceWidthPx <= 0 || sourceHeightPx <= 0) {
        return WholeActiveArrayMappingAssessment(
            verdict = WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE,
            sourceDomainBasis = basis,
            mappedAssumedSourceBoundsPx = null,
            expectedBufferBoundsPx = expectedBounds,
            reason = "assumed source domain (whole active array) width/height missing or not strictly positive " +
                "(sourceWidthPx=$sourceWidthPx, sourceHeightPx=$sourceHeightPx)",
        )
    }
    if (!bufferValid) {
        return WholeActiveArrayMappingAssessment(
            verdict = WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE,
            sourceDomainBasis = basis,
            mappedAssumedSourceBoundsPx = null,
            expectedBufferBoundsPx = null,
            reason = "analysis buffer width/height missing or not strictly positive " +
                "(bufferWidthPx=$bufferWidthPx, bufferHeightPx=$bufferHeightPx)",
        )
    }

    val transformClass = classifySensorToBufferMatrix(matrix)
    if (transformClass == SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED) {
        return WholeActiveArrayMappingAssessment(
            verdict = WholeActiveArrayHypothesisVerdict.UNSUPPORTED_TRANSFORM_CLASS,
            sourceDomainBasis = basis,
            mappedAssumedSourceBoundsPx = null,
            expectedBufferBoundsPx = expectedBounds,
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
        return WholeActiveArrayMappingAssessment(
            verdict = WholeActiveArrayHypothesisVerdict.NON_FINITE_MAPPED_BOUNDS,
            sourceDomainBasis = basis,
            mappedAssumedSourceBoundsPx = null,
            expectedBufferBoundsPx = expectedBounds,
            reason = "mapping the assumed source domain's corners through the matrix produced a non-finite coordinate",
        )
    }

    val mapped =
        SensorToBufferDomainBounds(
            leftPx = xs.min(),
            topPx = ys.min(),
            rightPx = xs.max(),
            bottomPx = ys.max(),
        )
    val expected = checkNotNull(expectedBounds) { "expectedBounds must be non-null once bufferValid has been confirmed" }
    val matches =
        abs(mapped.leftPx - expected.leftPx) <= tolerancePx &&
            abs(mapped.topPx - expected.topPx) <= tolerancePx &&
            abs(mapped.rightPx - expected.rightPx) <= tolerancePx &&
            abs(mapped.bottomPx - expected.bottomPx) <= tolerancePx

    return WholeActiveArrayMappingAssessment(
        verdict =
            if (matches) {
                WholeActiveArrayHypothesisVerdict.MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS
            } else {
                WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH
            },
        sourceDomainBasis = basis,
        mappedAssumedSourceBoundsPx = mapped,
        expectedBufferBoundsPx = expected,
        reason =
            if (matches) {
                "mapped assumed-source bounds match the expected buffer bounds within ${tolerancePx}px, under the " +
                    "$basis hypothesis"
            } else {
                "mapped assumed-source bounds [${mapped.leftPx},${mapped.topPx} — ${mapped.rightPx},${mapped.bottomPx}] do " +
                    "not match expected buffer bounds [0,0 — ${expected.rightPx},${expected.bottomPx}] within " +
                    "${tolerancePx}px, under the $basis hypothesis — this does not establish the matrix itself is " +
                    "invalid, unusable, or known not to describe the real pipeline; only that this one hypothesis " +
                    "does not hold for it"
            },
    )
}
