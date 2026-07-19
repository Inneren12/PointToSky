package dev.pointtosky.mobile.ar.camera

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §1). Pure, Android-free
 * detection of [FrameContentTargetSpec]'s dot-grid target directly in `ImageProxy` analysis-buffer
 * (luma-plane) pixel coordinates — never through `PreviewView`/display coordinates (task §1's explicit
 * constraint). Operates on a plain [LumaBuffer] so it is unit-testable from synthetic byte arrays,
 * without a real `ImageProxy`; the Android glue that extracts `ImageProxy.planes[0]` into a [LumaBuffer]
 * lives in `FrameContentCorrespondenceScreen.kt`.
 *
 * ## Algorithm (deliberately simple — see `FrameContentTarget.kt`'s file KDoc for why)
 * 1. Threshold the luma plane at [FrameContentDetectionTolerances.darkThreshold] to find "dark" pixels
 *    (the target's dots are printed dark-on-light).
 * 2. Group dark pixels into 4-connected blobs via iterative flood fill (never recursive — avoids stack
 *    depth issues on a large contiguous dark region).
 * 3. Keep blobs whose pixel count falls within [FrameContentDetectionTolerances.minBlobAreaPx] and
 *    [FrameContentDetectionTolerances.maxBlobAreaFractionOfImage] of the image area — rejects both
 *    single-pixel noise and a whole dark background misdetected as one giant blob.
 * 4. If, and only if, the number of surviving blobs exactly equals [FrameContentTargetSpec.pointCount],
 *    sort by Y ascending, chunk into [FrameContentTargetSpec.cornerRows] groups of
 *    [FrameContentTargetSpec.cornerCols], sort each group by X ascending, and assign row/column IDs by
 *    that raster order. This assumes the *entire* grid is visible and not rotated far from upright —
 *    an explicit, documented limitation (see `FrameContentTarget.kt`), not silently assumed to be
 *    robust to partial occlusion or heavy rotation. Any other blob count returns
 *    [FrameContentDetectionResult.InsufficientOrAmbiguousGrid] rather than guessing a correspondence.
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

internal enum class CornerRefinementStatus {
    SUBPIXEL_REFINED,
    COARSE_ONLY,
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
) {
    init {
        require(darkThreshold in 0..255) { "darkThreshold must be within [0, 255]; was $darkThreshold" }
        require(minBlobAreaPx > 0) { "minBlobAreaPx must be positive; was $minBlobAreaPx" }
        require(maxBlobAreaFractionOfImage in 0.0..1.0) {
            "maxBlobAreaFractionOfImage must be within [0, 1]; was $maxBlobAreaFractionOfImage"
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

/**
 * Detects [spec]'s dot-grid target in [luma] (task §1). See file KDoc for the algorithm and its
 * explicit scope limitations.
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

    if (candidateBlobs.size != spec.pointCount) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason =
                "expected exactly ${spec.pointCount} candidate blobs (${spec.cornerRows}x${spec.cornerCols} grid) " +
                    "after area filtering, found ${candidateBlobs.size}",
            rawBlobCount = candidateBlobs.size,
        )
    }

    val sortedByY = candidateBlobs.sortedBy { it.weightedCentroidY }
    val rows = sortedByY.chunked(spec.cornerCols)
    if (rows.size != spec.cornerRows || rows.any { it.size != spec.cornerCols }) {
        return FrameContentDetectionResult.InsufficientOrAmbiguousGrid(
            reason = "candidate blob count matched pointCount but could not chunk cleanly into " +
                "${spec.cornerRows}x${spec.cornerCols}",
            rawBlobCount = candidateBlobs.size,
        )
    }

    val points =
        rows.flatMapIndexed { rowIndex, rowBlobs ->
            rowBlobs.sortedBy { it.weightedCentroidX }.mapIndexed { colIndex, blob ->
                val expectedAreaPx = tolerances.minBlobAreaPx.coerceAtLeast(1)
                val confidence = (blob.pixelCount.toDouble() / (expectedAreaPx * 4.0)).coerceIn(0.0, 1.0)
                DetectedTargetPoint(
                    pointId = FrameContentPointId(rowIndex, colIndex),
                    bufferXPx = blob.weightedCentroidX,
                    bufferYPx = blob.weightedCentroidY,
                    confidence = confidence,
                    refinementStatus =
                        if (blob.pixelCount >= tolerances.subpixelMinBlobAreaPx) {
                            CornerRefinementStatus.SUBPIXEL_REFINED
                        } else {
                            CornerRefinementStatus.COARSE_ONLY
                        },
                    region = classifyPointRegion(blob.weightedCentroidX, blob.weightedCentroidY, luma.widthPx, luma.heightPx),
                )
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
