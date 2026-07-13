package dev.pointtosky.core.astro.projection.camera

/**
 * Pure, Android-independent pixel-geometry value types shared by the CAM-1e image↔display mapping
 * ([CropScaleTransform]). These are deliberately plain `Double`-valued types — never Android
 * `PointF`/`Rect`/`RectF`/`Size`/`Matrix` nor any Compose type — so the whole mapping layer is
 * JVM-testable outside the Android UI and carries no orientation/DPI assumptions of its own.
 *
 * Coordinates are **continuous image-edge coordinates**, not pixel-center indices: a point may sit
 * anywhere in `[0, width]`/`[0, height]` (edges inclusive), the top-left buffer corner is `(0, 0)`
 * and the bottom-right buffer corner is `(width, height)`. This avoids the `-1` magic constants that
 * pixel-center coordinates (`[0, W-1] × [0, H-1]`) would force into the inverse transform and viewport
 * scaling. All axes are `+x` right, `+y` down, matching `ImageProxy`/`PreviewView`.
 *
 * Every value is validated eagerly on construction: non-finite values, non-positive sizes, and
 * unordered rectangles are rejected with [IllegalArgumentException] rather than silently clamped or
 * NaN-propagated (CAM-1e §1 "no silent clamping").
 */
data class PixelPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite()) { "x must be finite; was $x" }
        require(y.isFinite()) { "y must be finite; was $y" }
    }
}

/**
 * A strictly-positive pixel size. Both dimensions must be finite and `> 0`: a zero-area source or
 * viewport is a caller error, never a value this type silently accepts.
 */
data class PixelSize(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite()) { "width must be finite; was $width" }
        require(height.isFinite()) { "height must be finite; was $height" }
        require(width > 0.0) { "width must be strictly positive; was $width" }
        require(height > 0.0) { "height must be strictly positive; was $height" }
    }
}

/**
 * An ordered pixel rectangle in continuous edge coordinates. [left] < [right] and [top] < [bottom]
 * strictly — a degenerate (zero-width or zero-height) rectangle is rejected, matching [PixelSize].
 *
 * @property left inclusive left edge (smaller x).
 * @property top inclusive top edge (smaller y).
 * @property right inclusive right edge (larger x).
 * @property bottom inclusive bottom edge (larger y).
 */
data class PixelRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "all rect edges must be finite; was ($left, $top, $right, $bottom)"
        }
        require(left < right) {
            "rect must be ordered horizontally (left < right); was left=$left, right=$right"
        }
        require(top < bottom) {
            "rect must be ordered vertically (top < bottom); was top=$top, bottom=$bottom"
        }
    }

    /** Width in pixels, always strictly positive by the ordering invariant. */
    val width: Double get() = right - left

    /** Height in pixels, always strictly positive by the ordering invariant. */
    val height: Double get() = bottom - top

    /**
     * True if [point] lies within this rectangle with edges inclusive. [tolerancePx] widens the
     * bounds symmetrically to absorb sub-pixel floating-point noise from an inverse mapping; it is
     * never used to clamp a result, only to classify one.
     */
    fun contains(
        point: PixelPoint,
        tolerancePx: Double = 0.0,
    ): Boolean =
        point.x >= left - tolerancePx &&
            point.x <= right + tolerancePx &&
            point.y >= top - tolerancePx &&
            point.y <= bottom + tolerancePx
}
