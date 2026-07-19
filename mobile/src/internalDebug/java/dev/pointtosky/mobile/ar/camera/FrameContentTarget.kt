package dev.pointtosky.mobile.ar.camera

import kotlin.math.hypot
import kotlin.math.sqrt

/** Conservative default for [FrameContentTargetSpec.minimumBlobClearanceMm] — a real positive physical
 * gap (beyond the sum of the two circles' own radii) between any two printed circles on the target, so
 * printed-ink spread, motion blur, or detector noise can never turn two idealized-tangent circles into
 * one merged connected component in a real photograph. See [FrameContentTargetSpec]'s "Physical
 * non-overlap invariant" KDoc below for the exact invariant this constant defaults into. */
internal const val FRAME_CONTENT_MINIMUM_BLOB_CLEARANCE_MM: Double = 3.0

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
 *
 * ## Printable target geometry (reproducibility fix)
 * The fields below make the physical target this codebase's detector was tuned against fully
 * reproducible from code alone, rather than from an unspecified hand-drawn card: [regularDotDiameterMm]
 * is the printed diameter of an ordinary grid dot, [markerDiameterMm] is derived from it via
 * [markerAreaScaleFactor] (never an independently chosen value that could silently drift out of sync
 * with the area ratio the detector actually validates against — see [FrameContentDetectionTolerances.markerAreaRatioThreshold]),
 * and [markerOffsetXMm]/[markerOffsetYMm] place the marker's centre relative to `R0C0` (the object point
 * at local `(0, 0)`). [init] validates the marker is strictly closer to `R0C0` than to any other grid
 * corner, so a misconfigured offset that would make the physical marker ambiguous fails fast rather than
 * silently producing an unprintable/unusable target. [buildFrameContentTargetSvg] renders this exact
 * geometry as a checked-in-reproducible SVG file a real print run can use.
 *
 * ## Physical non-overlap invariant (printed-circle-overlap fix)
 * Being strictly nearer `R0C0` than every other corner does **not** imply the marker circle is
 * physically separate from the `R0C0` dot circle — a marker centre just barely nearer `R0C0` than the
 * next corner can still sit close enough to `R0C0` that the two printed circles overlap. The detector
 * ([detectFrameContentTargetCorners]) requires exactly `pointCount + 1` separate connected components; an
 * overlapping marker/dot merges into one blob and makes the printed target intrinsically undetectable,
 * regardless of anything else being correctly configured. [init] therefore also validates, using the
 * *real printed radii* (never bare centre-to-centre distance alone), that:
 * - the marker circle clears every regular dot circle by at least [minimumBlobClearanceMm]:
 *   `centerDistanceMm > markerRadiusMm + regularDotRadiusMm + minimumBlobClearanceMm` for every regular
 *   object point;
 * - regular dot circles clear each other by the same margin: `dotSpacingMm > regularDotDiameterMm +
 *   minimumBlobClearanceMm` (the closest any two regular dots ever get is one full [dotSpacingMm] apart,
 *   along a row or column).
 *
 * [minimumBlobClearanceMm] is a real positive margin, not merely `>=` the sum of the two radii — printed
 * ink spread, motion blur, and detector noise can all make two circles that are merely tangent in the
 * idealized SVG geometry appear to touch or merge in a real photograph.
 */
internal data class FrameContentTargetSpec(
    val cornerRows: Int = 4,
    val cornerCols: Int = 5,
    val dotSpacingMm: Double = 25.0,
    /** The printed orientation marker dot's area, as a multiple of a regular grid dot's area — must be
     * distinctly larger (see [FrameContentDetectionTolerances.markerAreaRatioThreshold]) so it can be
     * identified by size alone, independent of its approximate position. */
    val markerAreaScaleFactor: Double = 2.5,
    /** Printed diameter of an ordinary grid dot. */
    val regularDotDiameterMm: Double = 8.0,
    /** The orientation marker's centre X offset from `R0C0` (local `(0, 0)`) — diagonally outward from
     * the grid's own bounding box, matching this file's "offset diagonally outward" KDoc above. */
    val markerOffsetXMm: Double = -15.0,
    /** The orientation marker's centre Y offset from `R0C0` (local `(0, 0)`) — see [markerOffsetXMm]. */
    val markerOffsetYMm: Double = -15.0,
    /** The minimum real, physical gap — beyond the two printed circles' own radii — required between any
     * two printed circles on the target (marker-to-dot and dot-to-dot alike). See this class's "Physical
     * non-overlap invariant" KDoc above. Exported in both the text report and the JSON so the physical
     * target a device report was captured against is fully reproducible, clearance included. */
    val minimumBlobClearanceMm: Double = FRAME_CONTENT_MINIMUM_BLOB_CLEARANCE_MM,
) {
    init {
        require(cornerRows >= 2) { "cornerRows must be >= 2; was $cornerRows" }
        require(cornerCols >= 2) { "cornerCols must be >= 2; was $cornerCols" }
        require(dotSpacingMm > 0.0 && dotSpacingMm.isFinite()) { "dotSpacingMm must be finite and positive; was $dotSpacingMm" }
        require(markerAreaScaleFactor > 1.0 && markerAreaScaleFactor.isFinite()) {
            "markerAreaScaleFactor must be finite and > 1.0; was $markerAreaScaleFactor"
        }
        require(regularDotDiameterMm > 0.0 && regularDotDiameterMm.isFinite()) {
            "regularDotDiameterMm must be finite and positive; was $regularDotDiameterMm"
        }
        require(markerOffsetXMm.isFinite() && markerOffsetYMm.isFinite()) {
            "markerOffsetXMm/markerOffsetYMm must be finite; was ($markerOffsetXMm, $markerOffsetYMm)"
        }
        require(minimumBlobClearanceMm > 0.0 && minimumBlobClearanceMm.isFinite()) {
            "minimumBlobClearanceMm must be finite and positive; was $minimumBlobClearanceMm"
        }
        // The marker must be unambiguously associated with R0C0 by construction (task §3) — validated
        // here, not merely documented, so a misconfigured spec fails fast rather than producing a
        // physically ambiguous printed target.
        val distanceToR0C0 = hypot(markerOffsetXMm, markerOffsetYMm)
        require(distanceToR0C0 > 0.0) { "marker offset must not coincide with R0C0; was (0, 0)" }
        val otherCornersMm =
            listOf(
                (cornerCols - 1) * dotSpacingMm to 0.0,
                0.0 to (cornerRows - 1) * dotSpacingMm,
                (cornerCols - 1) * dotSpacingMm to (cornerRows - 1) * dotSpacingMm,
            )
        otherCornersMm.forEach { (cornerXMm, cornerYMm) ->
            val distanceToOtherCorner = hypot(markerOffsetXMm - cornerXMm, markerOffsetYMm - cornerYMm)
            require(distanceToR0C0 < distanceToOtherCorner) {
                "marker offset ($markerOffsetXMm, $markerOffsetYMm) must be strictly nearer to R0C0 (0, 0) " +
                    "than to every other grid corner — was ${distanceToR0C0}mm from R0C0 vs " +
                    "${distanceToOtherCorner}mm from ($cornerXMm, $cornerYMm)"
            }
        }

        // Physical non-overlap invariant (printed-circle-overlap fix, see this file's "Physical
        // non-overlap invariant" KDoc above) — the detector requires exactly pointCount + 1 separate
        // connected components; an overlapping marker/dot silently merges into one blob. Computed here
        // from the same row/col formula frameContentTargetObjectPoints uses (not via a call to that
        // function, which would read a not-yet-fully-constructed `this`), using the real printed radii,
        // never bare centre-to-centre distance alone.
        val regularDotRadiusMm = regularDotDiameterMm / 2.0
        val markerRadiusMm = markerDiameterMm / 2.0
        for (row in 0 until cornerRows) {
            for (col in 0 until cornerCols) {
                val pointXMm = col * dotSpacingMm
                val pointYMm = row * dotSpacingMm
                val centerDistanceMm = hypot(markerOffsetXMm - pointXMm, markerOffsetYMm - pointYMm)
                val requiredClearanceMm = markerRadiusMm + regularDotRadiusMm + minimumBlobClearanceMm
                require(centerDistanceMm > requiredClearanceMm) {
                    "marker circle (radius=${markerRadiusMm}mm, centre=($markerOffsetXMm, $markerOffsetYMm)) " +
                        "must not overlap or touch regular dot R${row}C$col (radius=${regularDotRadiusMm}mm, " +
                        "centre=($pointXMm, $pointYMm)) — required centreDistance > " +
                        "${requiredClearanceMm}mm (radii + minimumBlobClearanceMm=$minimumBlobClearanceMm), " +
                        "was ${centerDistanceMm}mm"
                }
            }
        }
        require(dotSpacingMm > regularDotDiameterMm + minimumBlobClearanceMm) {
            "dotSpacingMm ($dotSpacingMm) must exceed regularDotDiameterMm + minimumBlobClearanceMm " +
                "(${regularDotDiameterMm + minimumBlobClearanceMm}) so adjacent regular dots never overlap " +
                "or touch"
        }
    }

    val pointCount: Int get() = cornerRows * cornerCols

    /** Printed marker diameter, derived from [markerAreaScaleFactor] (area ratio ⇒ diameter ratio is its
     * square root) so the printed target and the detector's own acceptance ratio can never silently
     * drift apart. */
    val markerDiameterMm: Double get() = regularDotDiameterMm * sqrt(markerAreaScaleFactor)
}

/** Default target used by the device workflow (task §7): a 4x5 dot grid, 25mm spacing — small enough
 * to be fully visible in a handheld close-range shot at both 640x480 and 1280x720. */
internal val DEFAULT_FRAME_CONTENT_TARGET_SPEC = FrameContentTargetSpec()

/** The smallest axis-aligned rectangle, in the target's own local millimetre frame, that contains every
 * printed regular dot and the orientation marker at their real printed sizes (task §3's "total printable
 * target bounds") — not merely their centre points. */
internal data class FrameContentTargetPrintableBounds(
    val minXMm: Double,
    val minYMm: Double,
    val maxXMm: Double,
    val maxYMm: Double,
) {
    val widthMm: Double get() = maxXMm - minXMm
    val heightMm: Double get() = maxYMm - minYMm
}

/** Computes [FrameContentTargetPrintableBounds] for [spec] — includes every regular dot's own radius and
 * the marker's own (larger) radius, so a real print run never clips part of a dot at the target's edge. */
internal fun frameContentTargetPrintableBounds(spec: FrameContentTargetSpec): FrameContentTargetPrintableBounds {
    val regularRadiusMm = spec.regularDotDiameterMm / 2.0
    val markerRadiusMm = spec.markerDiameterMm / 2.0
    val objectPoints = frameContentTargetObjectPoints(spec)
    val minXMm = minOf(objectPoints.minOf { it.xMm - regularRadiusMm }, spec.markerOffsetXMm - markerRadiusMm)
    val maxXMm = maxOf(objectPoints.maxOf { it.xMm + regularRadiusMm }, spec.markerOffsetXMm + markerRadiusMm)
    val minYMm = minOf(objectPoints.minOf { it.yMm - regularRadiusMm }, spec.markerOffsetYMm - markerRadiusMm)
    val maxYMm = maxOf(objectPoints.maxOf { it.yMm + regularRadiusMm }, spec.markerOffsetYMm + markerRadiusMm)
    return FrameContentTargetPrintableBounds(minXMm = minXMm, minYMm = minYMm, maxXMm = maxXMm, maxYMm = maxYMm)
}

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
