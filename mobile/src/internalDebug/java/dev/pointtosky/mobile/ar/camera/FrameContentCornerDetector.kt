package dev.pointtosky.mobile.ar.camera

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §1/§6). Pure, Android-free
 * detection of [FrameContentTargetSpec]'s dot-grid target directly in `ImageProxy` analysis-buffer
 * (luma-plane) pixel coordinates — never through `PreviewView`/display coordinates (task §1's explicit
 * constraint). Operates on a plain [LumaBuffer] so it is unit-testable from synthetic byte arrays,
 * without a real `ImageProxy`; the Android glue that extracts `ImageProxy.planes[0]` into a [LumaBuffer]
 * lives in `FrameContentCameraPreview.kt`.
 *
 * ## Algorithm
 * 1. Threshold the luma plane at [FrameContentDetectionTolerances.darkThreshold] to find "dark" pixels
 *    (the target's dots are printed dark-on-light).
 * 2. Group dark pixels into 4-connected blobs via iterative flood fill (never recursive — avoids stack
 *    depth issues on a large contiguous dark region), each with an intensity-weighted (sub-pixel)
 *    centroid.
 * 3. Keep blobs whose pixel count falls within [FrameContentDetectionTolerances.minBlobAreaPx] and
 *    [FrameContentDetectionTolerances.maxBlobAreaFractionOfImage] of the image area, and require
 *    *exactly* `pointCount + 1` of them (the grid dots plus one orientation marker dot — see
 *    `FrameContentTarget.kt`'s own KDoc for why the marker exists).
 * 4. Identify the orientation marker as the single blob distinctly larger
 *    ([FrameContentDetectionTolerances.markerAreaRatioThreshold]) than the median candidate area —
 *    never inferred from an assumed position. Anything other than exactly one such blob is
 *    [FrameContentDetectionResult.OrientationAmbiguous], never a guess.
 * 5. Sort the remaining (grid) blobs by Y, chunk into [FrameContentTargetSpec.cornerRows] groups of
 *    [FrameContentTargetSpec.cornerCols], sort each group by X — this is a **raster** order relative to
 *    the image, not yet the target's own row/column labelling.
 * 6. Validate row separation (no Y-overlap between adjacent raster rows) and inter-point spacing
 *    consistency (every within-row/between-row gap within a bounded ratio of the median gap) — rejects
 *    strong perspective and accidental row-interleaving as [FrameContentDetectionResult.InsufficientOrAmbiguousGrid]
 *    rather than silently assigning a plausible-but-wrong correspondence.
 * 7. Resolve true orientation: the raster grid's four corners are compared against the marker blob's
 *    position; the corner closest to the marker becomes `(row=0, col=0)`. Requires a clear confidence
 *    margin ([FrameContentDetectionTolerances.markerCornerConfidenceRatio]) between the nearest and
 *    second-nearest corner, else [FrameContentDetectionResult.OrientationAmbiguous]. This is exactly
 *    what resolves the 180-degree ambiguity a plain symmetric grid cannot: see `FrameContentTarget.kt`.
 *
 * Never returns [FrameContentDetectionResult.Detected] with semantic point IDs from blob count alone —
 * every gate above must pass first.
 */

/** A single luma (Y) plane, exactly as read from `ImageProxy.planes[0]` — 8-bit intensity per pixel,
 * [rowStridePx] may exceed [widthPx] (row padding), never assumed equal to it. */
internal data class LumaBuffer(
    val data: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val rowStridePx: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be positive; was $heightPx" }
        require(rowStridePx >= widthPx) { "rowStridePx ($rowStridePx) must be >= widthPx ($widthPx)" }
        require(data.size >= rowStridePx * (heightPx - 1) + widthPx) {
            "data too small for widthPx=$widthPx heightPx=$heightPx rowStridePx=$rowStridePx"
        }
    }

    fun lumaAt(
        x: Int,
        y: Int,
    ): Int = data[y * rowStridePx + x].toInt() and 0xFF
}

/**
 * Honest names (fix for task §7 — a weighted blob centroid with a fractional coordinate is not a
 * separately *refined* corner; these describe what was actually computed, not a claimed refinement
 * stage). [WEIGHTED_CENTROID_SUBPIXEL_ESTIMATE] for a blob large enough
 * ([FrameContentDetectionTolerances.subpixelMinBlobAreaPx]) that its intensity-weighted centroid is a
 * reasonably reliable sub-pixel estimate; [COARSE_CENTROID] for a smaller blob whose centroid is still
 * computed the same way but is less reliable. Neither name claims a separate refinement pass ran.
 */
internal enum class CornerRefinementStatus {
    WEIGHTED_CENTROID_SUBPIXEL_ESTIMATE,
    COARSE_CENTROID,
}

/** Which raster corner of the detected grid the orientation marker was found nearest to (task §6). */
internal enum class GridCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/** One detected target point, in exact `ImageProxy` analysis-buffer pixel coordinates (task §1). */
internal data class DetectedTargetPoint(
    val pointId: FrameContentPointId,
    val bufferXPx: Double,
    val bufferYPx: Double,
    val confidence: Double,
    val refinementStatus: CornerRefinementStatus,
    val region: PointRegion,
)

internal data class FrameContentDetectionTolerances(
    val darkThreshold: Int = 90,
    val minBlobAreaPx: Int = 6,
    val maxBlobAreaFractionOfImage: Double = 0.02,
    val subpixelMinBlobAreaPx: Int = 12,
    /** The orientation marker blob's area must be at least this many times the candidate set's median
     * area to be identified as the marker (task §6) — never inferred from position alone. */
    val markerAreaRatioThreshold: Double = 1.5,
    /** The marker's distance to the nearest raster corner must be at least this many times smaller than
     * its distance to the second-nearest corner before orientation is considered resolved (task §6) —
     * else [FrameContentDetectionResult.OrientationAmbiguous]. */
    val markerCornerConfidenceRatio: Double = 1.5,
    /** Every within-row/between-row spacing gap must fall within
     * `[medianGap * spacingConsistencyMinRatio, medianGap * spacingConsistencyMaxRatio]` (task §6) —
     * rejects strong perspective / inconsistent spacing rather than silently accepting it. */
    val spacingConsistencyMinRatio: Double = 0.5,
    val spacingConsistencyMaxRatio: Double = 1.8,
) {
    init {
        require(darkThreshold in 0..255) { "darkThreshold must be within [0, 255]; was $darkThreshold" }
        require(minBlobAreaPx > 0) { "minBlobAreaPx must be positive; was $minBlobAreaPx" }
        require(maxBlobAreaFractionOfImage in 0.0..1.0) {
            "maxBlobAreaFractionOfImage must be within [0, 1]; was $maxBlobAreaFractionOfImage"
        }
        require(markerAreaRatioThreshold > 1.0) {
            "markerAreaRatioThreshold must be > 1.0; was $markerAreaRatioThreshold"
        }
        require(markerCornerConfidenceRatio > 1.0) {
            "markerCornerConfidenceRatio must be > 1.0; was $markerCornerConfidenceRatio"
        }
        require(spacingConsistencyMinRatio in 0.0..1.0) {
            "spacingConsistencyMinRatio must be within [0, 1]; was $spacingConsistencyMinRatio"
        }
        require(spacingConsistencyMaxRatio >= 1.0) {
            "spacingConsistencyMaxRatio must be >= 1.0; was $spacingConsistencyMaxRatio"
        }
    }
}

internal val DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES = FrameContentDetectionTolerances()

internal sealed interface FrameContentDetectionResult {
    data class Detected(
        val points: List<DetectedTargetPoint>,
    ) : FrameContentDetectionResult

    data class InsufficientOrAmbiguousGrid(
        val reason: String,
        val rawBlobCount: Int,
    ) : FrameContentDetectionResult

    /** Correspondence identity could not be established — the candidate grid blobs and/or the
     * orientation marker were found, but not with enough confidence to know which raster corner is the
     * target's true `(row=0, col=0)` origin (task §6). Never falls back to a guessed orientation. */
    data class OrientationAmbiguous(
        val reason: String,
        val rawBlobCount: Int,
        val markerCandidateCount: Int,
    ) : FrameContentDetectionResult
}

private data class Blob(
    val pixelCount: Int,
    val weightedCentroidX: Double,
    val weightedCentroidY: Double,
)

/** Finds 4-connected dark blobs in [luma] below [tolerances].darkThreshold via iterative flood fill,
 * computing each blob's intensity-weighted (sub-pixel) centroid using `(darkThreshold - luma)` as the
 * per-pixel weight (darker pixels count more, biasing the centroid toward the blob's true dark core). */
private fun findDarkBlobs(
    luma: LumaBuffer,
    tolerances: FrameContentDetectionTolerances,
): List<Blob> {
    val visited = BooleanArray(luma.widthPx * luma.heightPx)
    val blobs = mutableListOf<Blob>()
    val stack = ArrayDeque<Int>()

    for (startY in 0 until luma.heightPx) {
        for (startX in 0 until luma.widthPx) {
            val startIndex = startY * luma.widthPx + startX
            if (visited[startIndex]) continue
            if (luma.lumaAt(startX, startY) >= tolerances.darkThreshold) {
                visited[startIndex] = true
                continue
            }

            stack.clear()
            stack.addLast(startIndex)
            visited[startIndex] = true
            var pixelCount = 0
            var weightSum = 0.0
            var weightedX = 0.0
            var weightedY = 0.0

            while (stack.isNotEmpty()) {
                val index = stack.removeLast()
                val x = index % luma.widthPx
                val y = index / luma.widthPx
                val lumaValue = luma.lumaAt(x, y)
                val weight = (tolerances.darkThreshold - lumaValue).coerceAtLeast(1).toDouble()
                pixelCount += 1
                weightSum += weight
                weightedX += weight * (x + 0.5)
                weightedY += weight * (y + 0.5)

                for ((dx, dy) in NEIGHBOR_OFFSETS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || nx >= luma.widthPx || ny < 0 || ny >= luma.heightPx) continue
                    val nIndex = ny * luma.widthPx + nx
                    if (visited[nIndex]) continue
                    if (luma.lumaAt(nx, ny) >= tolerances.darkThreshold) {
                        visited[nIndex] = true
                        continue
                    }
                    visited[nIndex] = true
                    stack.addLast(nIndex)
                }
            }

            if (weightSum > 0.0) {
                blobs.add(Blob(pixelCount, weightedX / weightSum, weightedY / weightSum))
            }
        }
    }
    return blobs
}

private val NEIGHBOR_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

private fun buildDetectedPoint(
    blob: Blob,
    trueRow: Int,
    trueCol: Int,
    luma: LumaBuffer,
    tolerances: FrameContentDetectionTolerances,
): DetectedTargetPoint {
    val expectedAreaPx = tolerances.minBlobAreaPx.coerceAtLeast(1)
    val confidence = (blob.pixelCount.toDouble() / (expectedAreaPx * 4.0)).coerceIn(0.0, 1.0)
    return DetectedTargetPoint(
        pointId = FrameContentPointId(trueRow, trueCol),
        bufferXPx = blob.weightedCentroidX,
        bufferYPx = blob.weightedCentroidY,
        confidence = confidence,
        refinementStatus =
            if (blob.pixelCount >= tolerances.subpixelMinBlobAreaPx) {
                CornerRefinementStatus.WEIGHTED_CENTROID_SUBPIXEL_ESTIMATE
            } else {
                CornerRefinementStatus.COARSE_CENTROID
            },
        region = classifyPointRegion(blob.weightedCentroidX, blob.weightedCentroidY, luma.widthPx, luma.heightPx),
    )
}

/** Remaps a raster `(row, col)` position into the target's own true `(row, col)` labelling, given which
 * raster corner the orientation marker was found nearest to (task §6). [GridCorner.TOP_LEFT] is
 * identity (no rotation); [GridCorner.BOTTOM_RIGHT] is the 180-degree case a plain symmetric grid
 * cannot otherwise resolve; [GridCorner.TOP_RIGHT]/[GridCorner.BOTTOM_LEFT] are handled for
 * completeness (not expected for a rigid, front-facing planar target, since a 90-degree-family rotation
 * changes which raster axis has `cornerRows` vs `cornerCols` points and is rejected earlier by the
 * row-count/chunk-size check) but never assumed unreachable. */
private fun trueRowCol(
    originCorner: GridCorner,
    rasterRow: Int,
    rasterCol: Int,
    cornerRows: Int,
    cornerCols: Int,
): Pair<Int, Int> =
    when (originCorner) {
        GridCorner.TOP_LEFT -> rasterRow to rasterCol
        GridCorner.BOTTOM_RIGHT -> (cornerRows - 1 - rasterRow) to (cornerCols - 1 - rasterCol)
        GridCorner.TOP_RIGHT -> rasterRow to (cornerCols - 1 - rasterCol)
        GridCorner.BOTTOM_LEFT -> (cornerRows - 1 - rasterRow) to rasterCol
    }

/**
 * Detects [spec]'s dot-grid target in [luma] (task §1/§6). See file KDoc for the algorithm and
 * `FrameContentTarget.kt` for the target design and its orientation marker.
 */
internal fun detectFrameContentTargetCorners(
    luma: LumaBuffer,
    spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
    tolerances: FrameContentDetectionTolerances = DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES,
): FrameContentDetectionResult {
    val imageAreaPx = luma.widthPx.toLong() * luma.heightPx.toLong()
    val maxAreaPx = (imageAreaPx * tolerances.maxBlobAreaFractionOfImage).toLong()

    val candidateBlobs =
        findDarkBlobs(luma, tolerances)
            .filter { it.pixelCount >= tolerances.minBlobAreaPx && it.pixelCount <= maxAreaPx }

    val expectedCandidateCount = spec.pointCount + 1 // grid dots + 1 orientation marker
    if (candidateBlobs.size != expectedCandidateCount) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason =
                "expected exactly $expectedCandidateCount candidate blobs (${spec.pointCount} grid dots " +
                    "in a ${spec.cornerRows}x${spec.cornerCols} grid + 1 orientation marker) after area " +
                    "filtering, found ${candidateBlobs.size}",
            rawBlobCount = candidateBlobs.size,
        )
    }

    // Identify the orientation marker: the single blob distinctly larger than the candidate set's
    // median area — never inferred from an assumed position (task §6).
    val sortedByArea = candidateBlobs.sortedBy { it.pixelCount }
    val medianAreaPx = sortedByArea[sortedByArea.size / 2].pixelCount.toDouble()
    val markerCandidates = candidateBlobs.filter { it.pixelCount >= medianAreaPx * tolerances.markerAreaRatioThreshold }
    if (markerCandidates.size != 1) {
        return FrameContentDetectionResult.OrientationAmbiguous(
            reason =
                "expected exactly 1 blob at least ${tolerances.markerAreaRatioThreshold}x the median " +
                    "candidate area (${medianAreaPx}px) to serve as the orientation marker, found " +
                    "${markerCandidates.size}",
            rawBlobCount = candidateBlobs.size,
            markerCandidateCount = markerCandidates.size,
        )
    }
    val markerBlob = markerCandidates.single()
    val gridBlobs = candidateBlobs - markerBlob

    val sortedByY = gridBlobs.sortedBy { it.weightedCentroidY }
    val rasterRows = sortedByY.chunked(spec.cornerCols)
    if (rasterRows.size != spec.cornerRows || rasterRows.any { it.size != spec.cornerCols }) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason = "candidate grid blob count matched pointCount but could not chunk cleanly into " +
                "${spec.cornerRows}x${spec.cornerCols}",
            rawBlobCount = candidateBlobs.size,
        )
    }

    // Row separation: adjacent raster rows must not overlap in Y — rejects strong perspective / row
    // -interleaving rather than silently sorting mixed-row points together (task §6).
    for (i in 0 until rasterRows.size - 1) {
        val maxYThisRow = rasterRows[i].maxOf { it.weightedCentroidY }
        val minYNextRow = rasterRows[i + 1].minOf { it.weightedCentroidY }
        if (maxYThisRow >= minYNextRow) {
            return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
                reason = "raster row $i and row ${i + 1} overlap in Y ($maxYThisRow >= $minYNextRow) — rows " +
                    "are not cleanly separated (possible strong perspective or row-interleaving)",
                rawBlobCount = candidateBlobs.size,
            )
        }
    }

    val rasterRowsSortedByX = rasterRows.map { row -> row.sortedBy { it.weightedCentroidX } }

    // Spacing consistency: every within-row X gap and every between-row Y-centroid gap must fall within
    // a bounded ratio of the median gap (task §6) — catches strong perspective distorting spacing.
    val withinRowGaps = rasterRowsSortedByX.flatMap { row -> row.zipWithNext { a, b -> b.weightedCentroidX - a.weightedCentroidX } }
    val rowCentroidYs = rasterRowsSortedByX.map { row -> row.sumOf { it.weightedCentroidY } / row.size }
    val betweenRowGaps = rowCentroidYs.zipWithNext { a, b -> b - a }
    val allGaps = withinRowGaps + betweenRowGaps
    if (allGaps.isEmpty() || allGaps.any { it <= 0.0 }) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason = "non-positive inter-point spacing gap detected — invalid grid geometry",
            rawBlobCount = candidateBlobs.size,
        )
    }
    val medianGap = allGaps.sorted().let { it[it.size / 2] }
    val inconsistentGap =
        allGaps.any { it < medianGap * tolerances.spacingConsistencyMinRatio || it > medianGap * tolerances.spacingConsistencyMaxRatio }
    if (inconsistentGap) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason = "inconsistent inter-point spacing (possible strong perspective) — at least one gap " +
                "falls outside [${tolerances.spacingConsistencyMinRatio}x, ${tolerances.spacingConsistencyMaxRatio}x] " +
                "the median gap (${medianGap}px)",
            rawBlobCount = candidateBlobs.size,
        )
    }

    // Orientation resolution: which raster corner is the marker nearest to, with a required confidence
    // margin over the second-nearest corner (task §6 — this is what resolves the 180-degree ambiguity a
    // plain symmetric grid cannot).
    val corners =
        mapOf(
            GridCorner.TOP_LEFT to rasterRowsSortedByX.first().first(),
            GridCorner.TOP_RIGHT to rasterRowsSortedByX.first().last(),
            GridCorner.BOTTOM_LEFT to rasterRowsSortedByX.last().first(),
            GridCorner.BOTTOM_RIGHT to rasterRowsSortedByX.last().last(),
        )
    val distancesToMarker =
        corners.entries
            .map { (corner, blob) ->
                corner to distancePx(blob.weightedCentroidX, blob.weightedCentroidY, markerBlob.weightedCentroidX, markerBlob.weightedCentroidY)
            }
            .sortedBy { it.second }
    val nearest = distancesToMarker[0]
    val secondNearest = distancesToMarker[1]
    if (nearest.second <= 0.0 || secondNearest.second < nearest.second * tolerances.markerCornerConfidenceRatio) {
        return FrameContentDetectionResult.OrientationAmbiguous(
            reason = "orientation marker is not clearly closer to one grid corner than the others " +
                "(nearest=${nearest.second}px to ${nearest.first}, second-nearest=${secondNearest.second}px " +
                "to ${secondNearest.first}, required ratio=${tolerances.markerCornerConfidenceRatio}x)",
            rawBlobCount = candidateBlobs.size,
            markerCandidateCount = 1,
        )
    }
    val originCorner = nearest.first

    val points =
        rasterRowsSortedByX.flatMapIndexed { rasterRow, row ->
            row.mapIndexed { rasterCol, blob ->
                val (trueRow, trueCol) = trueRowCol(originCorner, rasterRow, rasterCol, spec.cornerRows, spec.cornerCols)
                buildDetectedPoint(blob, trueRow, trueCol, luma, tolerances)
            }
        }

    return FrameContentDetectionResult.Detected(points)
}

/** Unused directly by detection math; retained so callers computing an expected dot pixel radius from
 * [FrameContentTargetSpec] have one shared, documented formula rather than re-deriving it ad hoc. */
internal fun expectedDotAreaPx(dotRadiusPx: Double): Double = PI * dotRadiusPx * dotRadiusPx

internal fun distancePx(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
): Double = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
