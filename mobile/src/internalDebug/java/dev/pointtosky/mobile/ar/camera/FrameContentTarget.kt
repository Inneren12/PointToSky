package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only).
 *
 * This codebase has no ChArUco/AprilTag/ArUco detection, no OpenCV dependency, and no JNI/native
 * module of any kind (confirmed by repo-wide search before this experiment was added) — adding real
 * ChArUco or AprilTag decoding would require a brand-new native/CV dependency, out of scope for a
 * single internal-debug diagnostic. This experiment instead defines one new, minimal, first-of-its-kind
 * **planar dot-grid target**: a flat grid of solid, high-contrast circular dots at known physical
 * spacing. A dot grid was chosen over a checkerboard specifically because connected-component blob
 * centroid detection ([detectFrameContentTargetCorners]) is simpler and more robust to implement
 * correctly in pure Kotlin than checkerboard saddle-point detection, and because — unlike a plain
 * checkerboard — a fully-visible rectangular grid still permits deterministic row/column correspondence
 * by simple sort-and-chunk, without needing ArUco-style per-marker IDs. **This is not ChArUco or
 * AprilTag** — there is no per-point ID encoding.
 *
 * ## Orientation marker (correspondence-identity fix)
 * A plain symmetric `cornerRows x cornerCols` dot grid has **no orientation identity**: viewed
 * upside-down (a 180-degree in-plane rotation, physically realizable simply by rotating the printed
 * card, and completely indistinguishable from the un-rotated view in the dot pattern alone), the grid
 * looks identical, so sort-Y/chunk/sort-X would confidently assign a *plausible but silently wrong*
 * row/column correspondence — each detected point paired with the wrong object point, point-symmetric
 * around the grid center. This is now fixed by one extra, distinctly larger **orientation marker dot**
 * printed near the (`row=0`, `col=0`) corner, offset diagonally outward from the grid's own bounding
 * box (so it is never mistaken for a grid dot by position, and its larger area means it is never
 * mistaken for one by size either — see [detectFrameContentTargetCorners]). [markerAreaScaleFactor]
 * documents the printed marker's area relative to a regular grid dot; the exact millimetre offset does
 * not matter for detection (only relative proximity to one grid corner matters), but a real printed
 * target should place it clearly closer to the (0,0) corner than to any other corner.
 *
 * The target is a single flat plane (`zMm = 0` for every object point in its own local frame), so pose
 * can be estimated with a planar-homography model (see `FrameContentPoseMath.kt`) rather than a general
 * PnP solver.
 */
internal data class FrameContentTargetSpec(
    val cornerRows: Int = 4,
    val cornerCols: Int = 5,
    val dotSpacingMm: Double = 25.0,
    /** The printed orientation marker dot's area, as a multiple of a regular grid dot's area — must be
     * distinctly larger (see [FrameContentDetectionTolerances.markerAreaRatioThreshold]) so it can be
     * identified by size alone, independent of its approximate position. */
    val markerAreaScaleFactor: Double = 2.5,
) {
    init {
        require(cornerRows >= 2) { "cornerRows must be >= 2; was $cornerRows" }
        require(cornerCols >= 2) { "cornerCols must be >= 2; was $cornerCols" }
        require(dotSpacingMm > 0.0 && dotSpacingMm.isFinite()) { "dotSpacingMm must be finite and positive; was $dotSpacingMm" }
        require(markerAreaScaleFactor > 1.0 && markerAreaScaleFactor.isFinite()) {
            "markerAreaScaleFactor must be finite and > 1.0; was $markerAreaScaleFactor"
        }
    }

    val pointCount: Int get() = cornerRows * cornerCols
}

/** Default target used by the device workflow (task §7): a 4x5 dot grid, 25mm spacing — small enough
 * to be fully visible in a handheld close-range shot at both 640x480 and 1280x720. */
internal val DEFAULT_FRAME_CONTENT_TARGET_SPEC = FrameContentTargetSpec()

/**
 * Identifies one target point by its row/column position in [FrameContentTargetSpec]'s grid — never a
 * bare integer index, so a report can never silently transpose row/column meaning.
 */
internal data class FrameContentPointId(
    val row: Int,
    val col: Int,
) {
    init {
        require(row >= 0) { "row must be non-negative; was $row" }
        require(col >= 0) { "col must be non-negative; was $col" }
    }

    val label: String get() = "R${row}C$col"
}

/** One target object point in the target's own local plane, `zMm` always `0.0` (planar target). */
internal data class FrameContentObjectPoint(
    val pointId: FrameContentPointId,
    val xMm: Double,
    val yMm: Double,
    val zMm: Double = 0.0,
)

/** The full, deterministic, row-major set of object points for [spec] — the same list every caller
 * gets for the same [spec], never randomized or reordered. */
internal fun frameContentTargetObjectPoints(spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC): List<FrameContentObjectPoint> =
    (0 until spec.cornerRows).flatMap { row ->
        (0 until spec.cornerCols).map { col ->
            FrameContentObjectPoint(
                pointId = FrameContentPointId(row, col),
                xMm = col * spec.dotSpacingMm,
                yMm = row * spec.dotSpacingMm,
                zMm = 0.0,
            )
        }
    }
