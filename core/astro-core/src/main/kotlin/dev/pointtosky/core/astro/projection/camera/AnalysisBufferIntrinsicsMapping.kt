package dev.pointtosky.core.astro.projection.camera

import kotlin.math.abs

/**
 * A crop region expressed in Camera2 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel coordinates (CAM-2c §4)
 * — e.g. the region a [SensorToBufferMatrix3] maps onto one analysis buffer.
 *
 * **Not** the same coordinate space as [CameraFrameMetadata]'s own `cropRectLeftPx`/`cropRectTopPx`/
 * `cropRectRightPx`/`cropRectBottomPx` fields, which are already in that frame's own *buffer* pixel
 * coordinates (see that class's KDoc: they are constrained to lie within `bufferWidthPx`/
 * `bufferHeightPx`). Conflating the two — treating `ImageProxy.cropRect` as if it described an
 * active-array-space crop — is exactly the mistake this type's distinct name guards against; see
 * `dev.pointtosky.mobile.ar.camera.ImageProxyFrameMetadataSource`'s KDoc for why `ImageProxy.cropRect`
 * is never used as this type's source in this codebase's current CameraX binding path.
 *
 * Diagnostics-only (CAM-2c fix §1): never fed back into [mapActiveArrayIntrinsicsThroughMatrix],
 * which composes matrices directly rather than round-tripping through a crop rectangle.
 *
 * @property leftPx inclusive left edge (smaller x), active-array pixels.
 * @property topPx inclusive top edge (smaller y), active-array pixels.
 * @property rightPx exclusive-by-convention right edge (larger x), active-array pixels.
 * @property bottomPx exclusive-by-convention bottom edge (larger y), active-array pixels.
 */
data class ActiveArraySensorCropRegion(
    val leftPx: Double,
    val topPx: Double,
    val rightPx: Double,
    val bottomPx: Double,
) {
    init {
        require(leftPx.isFinite() && topPx.isFinite() && rightPx.isFinite() && bottomPx.isFinite()) {
            "all crop-region edges must be finite; was ($leftPx, $topPx, $rightPx, $bottomPx)"
        }
        require(leftPx < rightPx) {
            "crop region must be ordered horizontally (leftPx < rightPx); was leftPx=$leftPx, rightPx=$rightPx"
        }
        require(topPx < bottomPx) {
            "crop region must be ordered vertically (topPx < bottomPx); was topPx=$topPx, bottomPx=$bottomPx"
        }
    }

    /** Width in active-array pixels, always strictly positive by the ordering invariant. */
    val widthPx: Double get() = rightPx - leftPx

    /** Height in active-array pixels, always strictly positive by the ordering invariant. */
    val heightPx: Double get() = bottomPx - topPx
}

/**
 * Inverts this (non-singular, affine) [SensorToBufferMatrix3] into the [ActiveArraySensorCropRegion]
 * it maps onto a buffer of exactly [bufferWidthPx] × [bufferHeightPx] pixels (CAM-2c fix §1):
 * transforms the buffer rectangle's four corners back into active-array space via the matrix inverse,
 * then takes their bounding box. **Exact** (a tight fit, not an over-approximation) for
 * [SensorToBufferTransformClass.AXIS_ALIGNED_0]/`ORTHOGONAL_90`/`ORTHOGONAL_180`/`ORTHOGONAL_270` — the
 * only classes this is ever invoked for in production (see [mapActiveArrayIntrinsicsThroughMatrix]) —
 * since each of those maps a rectangle onto a rectangle exactly. Diagnostics-only; never itself fed
 * back into any intrinsics computation.
 *
 * @throws IllegalArgumentException if [bufferWidthPx]/[bufferHeightPx] is not strictly positive, the
 *   matrix is not affine (see [classifySensorToBufferMatrix]), or the matrix's linear part is singular
 *   (not invertible).
 */
fun SensorToBufferMatrix3.toActiveArraySensorCropRegion(
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): ActiveArraySensorCropRegion {
    require(bufferWidthPx > 0) { "bufferWidthPx must be strictly positive; was $bufferWidthPx" }
    require(bufferHeightPx > 0) { "bufferHeightPx must be strictly positive; was $bufferHeightPx" }
    require(abs(m20) <= DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE &&
        abs(m21) <= DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE &&
        abs(m22 - 1.0) <= DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE) {
        "matrix must be affine (m20/m21 ~ 0, m22 ~ 1) to derive a crop region; was " +
            "m20=$m20, m21=$m21, m22=$m22"
    }
    val det = linearDeterminant
    require(abs(det) > DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE) {
        "matrix must be invertible (non-singular) to derive a crop region; linearDeterminant=$det"
    }

    // Invert the affine map: active = Minv * (buffer - translate), Minv = (1/det) * [[m11,-m01],[-m10,m00]].
    fun activeXOf(u: Double, v: Double) = (m11 * (u - m02) - m01 * (v - m12)) / det
    fun activeYOf(u: Double, v: Double) = (-m10 * (u - m02) + m00 * (v - m12)) / det

    val bufferWidth = bufferWidthPx.toDouble()
    val bufferHeight = bufferHeightPx.toDouble()
    val cornerXs =
        doubleArrayOf(
            activeXOf(0.0, 0.0),
            activeXOf(bufferWidth, 0.0),
            activeXOf(0.0, bufferHeight),
            activeXOf(bufferWidth, bufferHeight),
        )
    val cornerYs =
        doubleArrayOf(
            activeYOf(0.0, 0.0),
            activeYOf(bufferWidth, 0.0),
            activeYOf(0.0, bufferHeight),
            activeYOf(bufferWidth, bufferHeight),
        )
    return ActiveArraySensorCropRegion(
        leftPx = cornerXs.min(),
        topPx = cornerYs.min(),
        rightPx = cornerXs.max(),
        bottomPx = cornerYs.max(),
    )
}

/**
 * The final calibrated pinhole intrinsics over one exact analysis buffer (CAM-2c §4) — buffer-space
 * `fx`/`fy`/`cx`/`cy`, before conversion to the FOV-based [CameraIntrinsics] contract (see
 * [toCameraIntrinsics]).
 *
 * @property fxPx horizontal (buffer-X) focal length in buffer pixels, already sign-normalized (see
 *   [mapActiveArrayIntrinsicsThroughMatrix]). Finite, strictly positive.
 * @property fyPx vertical (buffer-Y) focal length in buffer pixels, already sign-normalized. Finite,
 *   strictly positive.
 * @property cxPx principal point X in buffer pixels. Finite.
 * @property cyPx principal point Y in buffer pixels. Finite.
 * @property widthPx the exact analysis buffer width these intrinsics apply to. Strictly positive.
 * @property heightPx the exact analysis buffer height these intrinsics apply to. Strictly positive.
 * @property axisSwapped `true` when the source matrix swapped which active-array axis drives which
 *   buffer axis (CAM-2c fix §1/§2) — see [CameraIntrinsics.axisSwapped].
 * @property negateXInput `true` when [fxPx]'s coefficient came out negative before sign-normalization
 *   — see [CameraIntrinsics.negateXInput].
 * @property negateYInput the [fyPx] analogue of [negateXInput].
 * @property skewPx the buffer-space residual of [ActiveArrayIntrinsics.skewPx] after composition —
 *   diagnostic only. Never fed into [toCameraIntrinsics]/[CameraIntrinsics]: that contract (and
 *   `dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel`) carry no skew
 *   term at all, by design — `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics` only
 *   ever lets a non-zero [ActiveArrayIntrinsics.skewPx] reach this function after confirming (against
 *   its own documented tolerance) that the value is small enough to be practically negligible here.
 */
data class AnalysisBufferIntrinsicsValues(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
    val widthPx: Int,
    val heightPx: Int,
    val axisSwapped: Boolean = false,
    val negateXInput: Boolean = false,
    val negateYInput: Boolean = false,
    val skewPx: Double = 0.0,
) {
    init {
        require(fxPx.isFinite() && fxPx > 0.0) { "fxPx must be finite and strictly positive; was $fxPx" }
        require(fyPx.isFinite() && fyPx > 0.0) { "fyPx must be finite and strictly positive; was $fyPx" }
        require(cxPx.isFinite()) { "cxPx must be finite; was $cxPx" }
        require(cyPx.isFinite()) { "cyPx must be finite; was $cyPx" }
        require(widthPx > 0) { "widthPx must be strictly positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be strictly positive; was $heightPx" }
        require(skewPx.isFinite()) { "skewPx must be finite; was $skewPx" }
    }
}

/**
 * The outcome of [mapActiveArrayIntrinsicsThroughMatrix] (CAM-2c fix §1) — never a silent `null` for
 * an unsupported [SensorToBufferTransformClass]; every rejection carries the specific class a caller
 * can log/diagnose.
 */
sealed interface MatrixIntrinsicsMappingResult {
    /** The matrix was one of the four supported classes; [values] is the composed buffer-space K'. */
    data class Mapped(
        val values: AnalysisBufferIntrinsicsValues,
        val transformClass: SensorToBufferTransformClass,
    ) : MatrixIntrinsicsMappingResult

    /**
     * The matrix classified as [transformClass] — one of [SensorToBufferTransformClass.MIRRORED],
     * [SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED],
     * [SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED], or [SensorToBufferTransformClass.SINGULAR]
     * — none of which this codebase knows how to compose into a valid pinhole model.
     */
    data class Unsupported(
        val transformClass: SensorToBufferTransformClass,
    ) : MatrixIntrinsicsMappingResult
}

/**
 * Composes [active]'s pinhole intrinsic matrix `K` with [matrix] (CAM-2c fix §1/§2) to derive
 * buffer-space intrinsics for a buffer of exactly [bufferWidthPx] × [bufferHeightPx] pixels:
 * `K' = M · K`, where
 * ```text
 * K = | fxActive  skewActive  cxActive |     M = | m00 m01 m02 |
 *     |    0        fyActive  cyActive |         | m10 m11 m12 |
 *     |    0           0         1     |         |  0   0   1  |
 * ```
 * — genuine matrix composition, not independently-derived per-field formulas, so [active]'s skew term
 * is never silently dropped from the algebra (CAM-2c fix §3), even though (as derived below) it never
 * actually leaks into [AnalysisBufferIntrinsicsValues.fxPx]/`fyPx`/`cxPx`/`cyPx` for any of the four
 * supported [SensorToBufferTransformClass] buckets — only into the returned diagnostic
 * [AnalysisBufferIntrinsicsValues.skewPx].
 *
 * ## Unsupported classes: a typed rejection, never a guess
 * [matrix] is first classified via [classifySensorToBufferMatrix]. [SensorToBufferTransformClass.MIRRORED]/
 * `GENERAL_AFFINE_UNSUPPORTED`/`PROJECTIVE_UNSUPPORTED`/`SINGULAR` all return
 * [MatrixIntrinsicsMappingResult.Unsupported] — this codebase has no documented, tested formula for
 * composing those into a valid pinhole model, so it refuses rather than guessing.
 *
 * ## Derivation for the four supported classes
 * Writing out `M · K`'s top two rows (the third is always `[0, 0, 1]` for an affine `M`):
 * ```text
 * row0 (bufferX eq): [ m00·fx,              m00·skew + m01·fy,   m00·cx + m01·cy + m02 ]
 * row1 (bufferY eq): [ m10·fx,              m10·skew + m11·fy,   m10·cx + m11·cy + m12 ]
 * ```
 * For [SensorToBufferTransformClass.AXIS_ALIGNED_0]/[SensorToBufferTransformClass.ORTHOGONAL_180]
 * (`m01 ~ 0`, `m10 ~ 0` by classification): row0 reduces to `[m00·fx, m00·skew, m00·cx+m02]` — a
 * function of `nx` only (plus the `m00·skew` cross term) — and row1 to `[0, m11·fy, m11·cy+m12]` — a
 * function of `ny` only. No axis swap: `fxBuffer = m00·fx`, `cxBuffer = m00·cx+m02`, `fyBuffer =
 * m11·fy`, `cyBuffer = m11·cy+m12`, `skewBuffer = m00·skew` (the row0 residual).
 *
 * For [SensorToBufferTransformClass.ORTHOGONAL_90]/[SensorToBufferTransformClass.ORTHOGONAL_270]
 * (`m00 ~ 0`, `m11 ~ 0` by classification): row0 reduces to `[0, m01·fy, m01·cy+m02]` — now a function
 * of `ny`, not `nx` — and row1 to `[m10·fx, m10·skew, m10·cx+m12]` — now a function of `nx`, not `ny`.
 * This **is** the genuine axis swap: `fxBuffer = m01·fy`, `cxBuffer = m01·cy+m02` (still row0's
 * constant — a swap changes which normalized input drives an equation, never that equation's own
 * constant term), `fyBuffer = m10·fx`, `cyBuffer = m10·cx+m12`, `skewBuffer = m10·skew` (now row1's
 * residual, since skew's row association swaps along with everything else).
 *
 * ## Sign normalization
 * [AnalysisBufferIntrinsicsValues.fxPx]/`fyPx` must stay strictly positive (this codebase's
 * longstanding pinhole-model invariant — see [ActiveArrayIntrinsics], `PinholeProjectionModel`), but
 * the raw coefficient derived above can come out negative — concretely, for
 * [SensorToBufferTransformClass.ORTHOGONAL_180] (`m00 < 0` and `m11 < 0` by classification), both
 * `fxRaw` and `fyRaw` are always negative. Rather than reject a valid, invertible, orientation-
 * preserving map, this function takes the absolute value and records the sign flip in
 * [AnalysisBufferIntrinsicsValues.negateXInput]/`negateYInput`: since `u = fxRaw·nx + cx = |fxRaw|·(−nx)
 * + cx` for any real `fxRaw`, `cx` itself is never affected by this — only the sign applied to the
 * normalized input at projection time (see `PinholeProjectionModel.project`'s KDoc for the exact
 * consumption of these flags, and the proof this can never double up with `rotationDegrees`).
 *
 * @throws IllegalArgumentException if [bufferWidthPx]/[bufferHeightPx] is not strictly positive.
 */
fun mapActiveArrayIntrinsicsThroughMatrix(
    active: ActiveArrayIntrinsics,
    matrix: SensorToBufferMatrix3,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
    tolerance: Double = DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE,
): MatrixIntrinsicsMappingResult {
    require(bufferWidthPx > 0) { "bufferWidthPx must be strictly positive; was $bufferWidthPx" }
    require(bufferHeightPx > 0) { "bufferHeightPx must be strictly positive; was $bufferHeightPx" }

    val transformClass = classifySensorToBufferMatrix(matrix, tolerance)
    val axisSwapped =
        when (transformClass) {
            SensorToBufferTransformClass.ORTHOGONAL_90, SensorToBufferTransformClass.ORTHOGONAL_270 -> true
            SensorToBufferTransformClass.AXIS_ALIGNED_0, SensorToBufferTransformClass.ORTHOGONAL_180 -> false
            else -> return MatrixIntrinsicsMappingResult.Unsupported(transformClass)
        }

    val fx = active.fxPx
    val fy = active.fyPx
    val cx = active.cxPx
    val cy = active.cyPx
    val skew = active.skewPx

    // row0 (bufferX eq): [a, b, c] ; row1 (bufferY eq): [d, e, f]
    val a = matrix.m00 * fx
    val b = matrix.m00 * skew + matrix.m01 * fy
    val c = matrix.m00 * cx + matrix.m01 * cy + matrix.m02
    val d = matrix.m10 * fx
    val e = matrix.m10 * skew + matrix.m11 * fy
    val f = matrix.m10 * cx + matrix.m11 * cy + matrix.m12

    val fxRaw: Double
    val cxRaw: Double
    val fyRaw: Double
    val cyRaw: Double
    val skewRaw: Double
    if (!axisSwapped) {
        fxRaw = a
        cxRaw = c
        fyRaw = e
        cyRaw = f
        skewRaw = b
    } else {
        fxRaw = b
        cxRaw = c
        fyRaw = d
        cyRaw = f
        skewRaw = e
    }

    val negateXInput = fxRaw < 0.0
    val negateYInput = fyRaw < 0.0

    val values =
        AnalysisBufferIntrinsicsValues(
            fxPx = abs(fxRaw),
            fyPx = abs(fyRaw),
            cxPx = cxRaw,
            cyPx = cyRaw,
            widthPx = bufferWidthPx,
            heightPx = bufferHeightPx,
            axisSwapped = axisSwapped,
            negateXInput = negateXInput,
            negateYInput = negateYInput,
            skewPx = skewRaw,
        )
    return MatrixIntrinsicsMappingResult.Mapped(values, transformClass)
}

/**
 * Converts these buffer-space pinhole values into the FOV-based [CameraIntrinsics] contract CAM-2a
 * already consumes, with `source = `[CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]` and
 * `reference = `[CameraIntrinsicsReference.AnalysisBuffer]`(`[widthPx]`, `[heightPx]`)`.
 *
 * Reuses [fovDegFromFocalLength] for the `fx`/`fy` → FOV conversion — the exact inverse of
 * `dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry`'s own
 * `fx = width / (2 tan(hFov/2))`, so the round trip through this [CameraIntrinsics] value is lossless
 * up to ordinary floating-point precision (`tan(atan(x)) == x`) — unaffected by [axisSwapped]/
 * [negateXInput]/[negateYInput], since [fxPx]/[fyPx] are already the sign-normalized, axis-resolved
 * buffer-X/buffer-Y coefficients by the time they reach this function (see
 * [mapActiveArrayIntrinsicsThroughMatrix]). [fovDegFromFocalLength] is unit-agnostic (a pure ratio), so
 * passing pixel dimensions where its parameter names say millimetres is intentional, not a unit
 * mismatch.
 *
 * [skewPx] is intentionally **not** carried into the returned [CameraIntrinsics] — see
 * [AnalysisBufferIntrinsicsValues.skewPx]'s KDoc for why.
 *
 * @param focalLengthMm the real `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` value used to derive [active]'s
 *   `fx`/`fy` (or, when [quality] is [CameraIntrinsicsQuality.CALIBRATED], the same physical
 *   metadata recorded alongside `LENS_INTRINSIC_CALIBRATION`) — carried through only for
 *   [CameraIntrinsics]'s own optional physical-dimension fields, never re-derived here.
 * @throws IllegalArgumentException if the resulting [CameraIntrinsics] fails its own validation
 *   (e.g. a degenerate mapping producing an out-of-range FOV or a negative principal point).
 */
fun AnalysisBufferIntrinsicsValues.toCameraIntrinsics(
    focalLengthMm: Double,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
    quality: CameraIntrinsicsQuality,
): CameraIntrinsics =
    CameraIntrinsics(
        horizontalFovDeg = fovDegFromFocalLength(widthPx.toDouble(), fxPx),
        verticalFovDeg = fovDegFromFocalLength(heightPx.toDouble(), fyPx),
        focalLengthMm = focalLengthMm,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = sensorHeightMm,
        principalPointXPx = cxPx,
        principalPointYPx = cyPx,
        source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
        reference = CameraIntrinsicsReference.AnalysisBuffer(widthPx, heightPx),
        quality = quality,
        axisSwapped = axisSwapped,
        negateXInput = negateXInput,
        negateYInput = negateYInput,
    )
