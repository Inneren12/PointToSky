package dev.pointtosky.core.astro.projection.camera

import kotlin.math.max

/**
 * Pure, Android-independent image↔display mapping for `PreviewView.ScaleType.FILL_CENTER` (CAM-1e).
 *
 * Describes how a camera image / crop rectangle is rotated, uniformly scaled, center-cropped, and
 * positioned inside a display viewport, and maps points **both directions** exactly (up to
 * floating-point tolerance):
 *
 * ```text
 * image/buffer pixel  ── imageToDisplay ──▶  display pixel
 * display pixel        ── displayToImage ──▶  image/buffer pixel
 * ```
 *
 * This type contains **only geometry**. It reads no pixels, does no interpolation, consumes no
 * timestamp pairs or intrinsics, and is not wired into the AR renderer — the overlay still uses the
 * legacy fixed-FOV projection. See `docs/camera_coordinate_calibration_contract.md` §9 (CAM-1e).
 *
 * ## Coordinate spaces (all origin top-left, `+x` right, `+y` down; continuous edge coordinates)
 *  1. **Buffer space** — raw `bufferWidth × bufferHeight` from `ImageProxy`. The public image-side
 *     API ([imageToDisplay] input, [displayToImage] output) is **buffer-relative**.
 *  2. **Crop-local space** — buffer coordinates translated by the crop origin: `(x − left, y − top)`
 *     over `[0, cropWidth] × [0, cropHeight]`. Internal to the forward/inverse pipeline.
 *  3. **Rotated image space** — crop-local coordinates rotated clockwise by [rotationDegrees];
 *     dimensions swap for 90°/270° (see [rotatedSourceSize]).
 *  4. **Display / viewport space** — `PreviewView`/Compose overlay pixels over [viewportSize].
 *
 * ## Forward mapping order ([imageToDisplay])
 *  1. translate buffer → crop-local (`− sourceCrop.left`, `− sourceCrop.top`);
 *  2. rotate crop-local clockwise by [rotationDegrees];
 *  3. multiply by [uniformScale];
 *  4. add the centered [displayOffsetX]/[displayOffsetY].
 *
 * ## Inverse mapping order ([displayToImage]) — undoes the above in reverse
 *  1. subtract [displayOffsetX]/[displayOffsetY];
 *  2. divide by [uniformScale];
 *  3. un-rotate (inverse clockwise rotation);
 *  4. translate crop-local → buffer (`+ sourceCrop.left`, `+ sourceCrop.top`).
 *
 * Results are **never rounded to integers** and inverse results are **never clamped** to the crop or
 * viewport — clamping would hide geometry errors and break reversibility. Visibility is reported
 * separately (see [isImagePointVisible], [isDisplayPointInsideVisibleImage], [visibleImageRect]).
 *
 * ## Caveat (CAM-1e §7)
 * This is the intended *pure* FILL_CENTER contract using the metadata currently available. It is
 * valid only if the analyzed crop/rotation geometry represents the same camera-stream framing the
 * `Preview` surface uses. CameraX may apply additional transformation metadata depending on
 * implementation/version/device; physical-device overlay validation remains required. This mapping
 * is **not** ground truth until device-validated.
 *
 * @property sourceCrop active source region in **buffer** coordinates (equals the full buffer when
 *   no crop metadata is present). Not assumed to start at `(0, 0)`.
 * @property sourceBufferSize full analyzed buffer size; [sourceCrop] must lie within it.
 * @property rotationDegrees clockwise rotation required to bring the buffer into display orientation;
 *   one of `0`, `90`, `180`, `270` (mirrors `ImageProxy.imageInfo.rotationDegrees`).
 * @property rotatedSourceSize the crop's size after rotation — `(cropWidth, cropHeight)` for
 *   `0°/180°`, swapped for `90°/270°`.
 * @property viewportSize display/overlay size the source is scaled into.
 * @property uniformScale single FILL_CENTER scale factor applied to both axes; `max` of the two
 *   per-axis ratios, so the scaled source always fully covers the viewport. Strictly positive.
 * @property displayOffsetX centered horizontal offset; may be negative when the source is cropped
 *   horizontally (never clamped).
 * @property displayOffsetY centered vertical offset; may be negative when the source is cropped
 *   vertically (never clamped).
 */
data class CropScaleTransform(
    val sourceCrop: PixelRect,
    val sourceBufferSize: PixelSize,
    val rotationDegrees: Int,
    val rotatedSourceSize: PixelSize,
    val viewportSize: PixelSize,
    val uniformScale: Double,
    val displayOffsetX: Double,
    val displayOffsetY: Double,
) {
    init {
        require(rotationDegrees in VALID_ROTATIONS_DEG) {
            "rotationDegrees must be one of $VALID_ROTATIONS_DEG; was $rotationDegrees"
        }
        require(uniformScale.isFinite() && uniformScale > 0.0) {
            "uniformScale must be finite and strictly positive; was $uniformScale"
        }
        require(displayOffsetX.isFinite()) { "displayOffsetX must be finite; was $displayOffsetX" }
        require(displayOffsetY.isFinite()) { "displayOffsetY must be finite; was $displayOffsetY" }
        require(
            sourceCrop.left >= -EDGE_EPS &&
                sourceCrop.top >= -EDGE_EPS &&
                sourceCrop.right <= sourceBufferSize.width + EDGE_EPS &&
                sourceCrop.bottom <= sourceBufferSize.height + EDGE_EPS,
        ) {
            "sourceCrop $sourceCrop must lie within the buffer " +
                "(${sourceBufferSize.width}x${sourceBufferSize.height})"
        }
        val expected = rotatedCropSize(sourceCrop, rotationDegrees)
        require(
            approxEquals(rotatedSourceSize.width, expected.width) &&
                approxEquals(rotatedSourceSize.height, expected.height),
        ) {
            "rotatedSourceSize $rotatedSourceSize is inconsistent with sourceCrop " +
                "${sourceCrop.width}x${sourceCrop.height} rotated by $rotationDegrees " +
                "(expected ${expected.width}x${expected.height})"
        }
    }

    /**
     * Maps a **buffer-space** image point to display/viewport coordinates. Points outside the crop
     * (or outside the visible center-cropped region) still map mathematically — visibility is a
     * separate query, see [isImagePointVisible].
     */
    fun imageToDisplay(point: PixelPoint): PixelPoint {
        val cropLocalX = point.x - sourceCrop.left
        val cropLocalY = point.y - sourceCrop.top
        val (rx, ry) = rotateClockwise(cropLocalX, cropLocalY, sourceCrop.width, sourceCrop.height, rotationDegrees)
        return PixelPoint(
            x = rx * uniformScale + displayOffsetX,
            y = ry * uniformScale + displayOffsetY,
        )
    }

    /**
     * Maps a display/viewport point back to **buffer-space** image coordinates — the exact inverse
     * of [imageToDisplay]. The result is never clamped to the crop or viewport, so a display point
     * outside the viewport legitimately produces a buffer point outside the crop.
     */
    fun displayToImage(point: PixelPoint): PixelPoint {
        val rotatedX = (point.x - displayOffsetX) / uniformScale
        val rotatedY = (point.y - displayOffsetY) / uniformScale
        val (lx, ly) = unrotateClockwise(rotatedX, rotatedY, sourceCrop.width, sourceCrop.height, rotationDegrees)
        return PixelPoint(
            x = lx + sourceCrop.left,
            y = ly + sourceCrop.top,
        )
    }

    /**
     * Maps a buffer-space rectangle to its display bounding rectangle. Because [rotationDegrees] is
     * a multiple of 90°, an axis-aligned buffer rectangle maps to an axis-aligned display rectangle
     * exactly, so mapping the four corners and taking their bounds is lossless.
     */
    fun imageRectToDisplay(rect: PixelRect): PixelRect {
        val corners =
            listOf(
                imageToDisplay(PixelPoint(rect.left, rect.top)),
                imageToDisplay(PixelPoint(rect.right, rect.top)),
                imageToDisplay(PixelPoint(rect.right, rect.bottom)),
                imageToDisplay(PixelPoint(rect.left, rect.bottom)),
            )
        return PixelRect(
            left = corners.minOf { it.x },
            top = corners.minOf { it.y },
            right = corners.maxOf { it.x },
            bottom = corners.maxOf { it.y },
        )
    }

    /**
     * The whole viewport — FILL_CENTER always fully covers it, so the visible display region is
     * always the full `(0, 0, viewportWidth, viewportHeight)` rectangle. Provided for symmetry with
     * [visibleImageRect].
     */
    val visibleDisplayRect: PixelRect
        get() = PixelRect(0.0, 0.0, viewportSize.width, viewportSize.height)

    /**
     * The buffer-space sub-rectangle of [sourceCrop] actually shown in the viewport after the
     * center crop — the inverse image of the viewport. When the source is wider than the viewport
     * this is narrower than [sourceCrop] (horizontal crop); when taller, shorter (vertical crop).
     * Equal to [sourceCrop] only when the aspect ratios match. Computed from the four viewport
     * corners, which is exact for 90°-multiple rotations.
     */
    val visibleImageRect: PixelRect
        get() {
            val corners =
                listOf(
                    displayToImage(PixelPoint(0.0, 0.0)),
                    displayToImage(PixelPoint(viewportSize.width, 0.0)),
                    displayToImage(PixelPoint(viewportSize.width, viewportSize.height)),
                    displayToImage(PixelPoint(0.0, viewportSize.height)),
                )
            return PixelRect(
                left = corners.minOf { it.x },
                top = corners.minOf { it.y },
                right = corners.maxOf { it.x },
                bottom = corners.maxOf { it.y },
            )
        }

    /**
     * True if a **buffer-space** image [point] is actually visible: it must lie inside [sourceCrop]
     * *and* its display mapping must fall inside the viewport (a point inside the crop but removed
     * by the center crop is not visible). [tolerancePx] absorbs sub-pixel float noise only.
     */
    fun isImagePointVisible(
        point: PixelPoint,
        tolerancePx: Double = DEFAULT_VISIBILITY_TOLERANCE_PX,
    ): Boolean {
        if (!sourceCrop.contains(point, tolerancePx)) return false
        return isDisplayInsideViewport(imageToDisplay(point), tolerancePx)
    }

    /**
     * True if a **display-space** [point] is inside the viewport *and* inverse-maps into
     * [sourceCrop]. Under FILL_CENTER the scaled source always covers the viewport, so in practice
     * this reduces to "inside the viewport"; the crop check is kept for robustness and symmetry
     * with [isImagePointVisible].
     */
    fun isDisplayPointInsideVisibleImage(
        point: PixelPoint,
        tolerancePx: Double = DEFAULT_VISIBILITY_TOLERANCE_PX,
    ): Boolean {
        if (!isDisplayInsideViewport(point, tolerancePx)) return false
        return sourceCrop.contains(displayToImage(point), tolerancePx)
    }

    private fun isDisplayInsideViewport(
        point: PixelPoint,
        tolerancePx: Double,
    ): Boolean =
        point.x >= -tolerancePx &&
            point.x <= viewportSize.width + tolerancePx &&
            point.y >= -tolerancePx &&
            point.y <= viewportSize.height + tolerancePx

    companion object {
        private val VALID_ROTATIONS_DEG = setOf(0, 90, 180, 270)

        /** Tolerance (px) for validation/consistency checks over integer-derived doubles. */
        private const val EDGE_EPS = 1e-6

        /**
         * Default sub-pixel tolerance for the boolean visibility helpers — absorbs floating-point
         * noise from the inverse mapping without materially widening the visible region.
         */
        const val DEFAULT_VISIBILITY_TOLERANCE_PX: Double = 1e-6

        /**
         * Builds a FILL_CENTER transform from pure geometry. Prefer
         * [createFillCenterCropScaleTransform] when starting from [CameraFrameMetadata].
         *
         * FILL_CENTER scale/offset contract:
         * ```text
         * scale   = max(viewportW / rotatedW, viewportH / rotatedH)
         * offsetX = (viewportW − rotatedW · scale) / 2      // may be negative (center crop)
         * offsetY = (viewportH − rotatedH · scale) / 2      // may be negative (center crop)
         * ```
         *
         * @throws IllegalArgumentException via the validating value types / init block for a
         *   non-positive viewport or source, an out-of-bounds crop, or an invalid rotation. Nothing
         *   is clamped.
         */
        fun fillCenter(
            sourceCrop: PixelRect,
            sourceBufferSize: PixelSize,
            rotationDegrees: Int,
            viewportSize: PixelSize,
        ): CropScaleTransform {
            require(rotationDegrees in VALID_ROTATIONS_DEG) {
                "rotationDegrees must be one of $VALID_ROTATIONS_DEG; was $rotationDegrees"
            }
            val rotated = rotatedCropSize(sourceCrop, rotationDegrees)
            val scale =
                max(
                    viewportSize.width / rotated.width,
                    viewportSize.height / rotated.height,
                )
            val scaledWidth = rotated.width * scale
            val scaledHeight = rotated.height * scale
            return CropScaleTransform(
                sourceCrop = sourceCrop,
                sourceBufferSize = sourceBufferSize,
                rotationDegrees = rotationDegrees,
                rotatedSourceSize = rotated,
                viewportSize = viewportSize,
                uniformScale = scale,
                displayOffsetX = (viewportSize.width - scaledWidth) / 2.0,
                displayOffsetY = (viewportSize.height - scaledHeight) / 2.0,
            )
        }

        private const val DEGREES_PER_QUARTER_TURN = 90

        /**
         * Clockwise quarter-turns (`0`, `1`, `2`, `3`) for a validated rotation of `0`/`90`/`180`/
         * `270` degrees. Switching on the turn index keeps the rotation formulas free of literal
         * `270` (rotation values are validated upstream, so the index is always in `0..3`).
         */
        private fun quarterTurns(rotationDegrees: Int): Int = rotationDegrees / DEGREES_PER_QUARTER_TURN

        /** Size of [crop] after a clockwise rotation of [rotationDegrees]; swaps for 90°/270°. */
        private fun rotatedCropSize(
            crop: PixelRect,
            rotationDegrees: Int,
        ): PixelSize =
            when (quarterTurns(rotationDegrees)) {
                0, 2 -> PixelSize(crop.width, crop.height) // 0°, 180°
                else -> PixelSize(crop.height, crop.width) // 90°, 270°
            }

        /**
         * Clockwise rotation of crop-local `(x, y)` with unrotated crop size `(w, h)`, in continuous
         * edge coordinates:
         * ```text
         *   0°: (x,            y)
         *  90°: (h − y,        x)
         * 180°: (w − x,        h − y)
         * 270°: (y,            w − x)
         * ```
         */
        private fun rotateClockwise(
            x: Double,
            y: Double,
            w: Double,
            h: Double,
            rotationDegrees: Int,
        ): Pair<Double, Double> =
            when (quarterTurns(rotationDegrees)) {
                0 -> x to y // 0°
                1 -> (h - y) to x // 90°
                2 -> (w - x) to (h - y) // 180°
                else -> y to (w - x) // 270°
            }

        /** Exact inverse of [rotateClockwise]: rotated `(xr, yr)` back to crop-local `(x, y)`. */
        private fun unrotateClockwise(
            xr: Double,
            yr: Double,
            w: Double,
            h: Double,
            rotationDegrees: Int,
        ): Pair<Double, Double> =
            when (quarterTurns(rotationDegrees)) {
                0 -> xr to yr // 0°
                1 -> yr to (h - xr) // 90°
                2 -> (w - xr) to (h - yr) // 180°
                else -> (w - yr) to xr // 270°
            }

        private fun approxEquals(
            a: Double,
            b: Double,
        ): Boolean = kotlin.math.abs(a - b) <= EDGE_EPS
    }
}

/**
 * Builds the pure FILL_CENTER [CropScaleTransform] for one [CameraFrameMetadata] frame and a display
 * viewport, with **no Android dependency and no side effects** (CAM-1e §8).
 *
 * The source crop is [frame]'s crop rect when present, or the full buffer bounds `(0, 0,
 * bufferWidth, bufferHeight)` when absent — the crop rect is honoured even when it equals the full
 * buffer, and is never assumed to start at `(0, 0)`. [CameraFrameMetadata.rotationDegrees] is taken
 * as the clockwise rotation required to bring the buffer into display orientation.
 *
 * @throws IllegalArgumentException if [viewportWidthPx] or [viewportHeightPx] is not strictly
 *   positive (invalid crop/rotation are already rejected by [CameraFrameMetadata] itself).
 */
fun createFillCenterCropScaleTransform(
    frame: CameraFrameMetadata,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): CropScaleTransform {
    require(viewportWidthPx > 0) { "viewportWidthPx must be strictly positive; was $viewportWidthPx" }
    require(viewportHeightPx > 0) { "viewportHeightPx must be strictly positive; was $viewportHeightPx" }

    val bufferSize = PixelSize(frame.bufferWidthPx.toDouble(), frame.bufferHeightPx.toDouble())
    // CameraFrameMetadata guarantees the four crop fields are all-present or all-absent, so a single
    // non-null left implies the rest are present (asserted with requireNotNull, not a 4-way &&).
    val sourceCrop =
        frame.cropRectLeftPx?.let { left ->
            PixelRect(
                left = left.toDouble(),
                top = requireNotNull(frame.cropRectTopPx).toDouble(),
                right = requireNotNull(frame.cropRectRightPx).toDouble(),
                bottom = requireNotNull(frame.cropRectBottomPx).toDouble(),
            )
        } ?: PixelRect(0.0, 0.0, bufferSize.width, bufferSize.height)

    return CropScaleTransform.fillCenter(
        sourceCrop = sourceCrop,
        sourceBufferSize = bufferSize,
        rotationDegrees = frame.rotationDegrees,
        viewportSize = PixelSize(viewportWidthPx.toDouble(), viewportHeightPx.toDouble()),
    )
}
