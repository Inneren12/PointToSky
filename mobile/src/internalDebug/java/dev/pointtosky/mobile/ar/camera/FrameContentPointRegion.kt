package dev.pointtosky.mobile.ar.camera

/** CAM-2c frame-content correspondence experiment (`internalDebug`-only). Which part of the analysis
 * buffer a pixel coordinate falls into — used both for exported detected points (task §1) and for
 * per-region residual summaries (task §5). */
internal enum class PointRegion {
    CENTER,
    EDGE,
    CORNER,
}

/**
 * Fraction of [widthPx]/[heightPx], measured inward from each edge, that counts as "near that edge"
 * (task §5/§7: "Thresholds must be exported and justified"). `0.2` means the outer 20% of each axis is
 * "near" that axis's edges — a conventional camera-calibration center/edge/corner split (comparable to
 * the inner-80%/outer-20% bands used by classic lens-distortion test charts), not a value tuned to any
 * particular observed dataset. Exported alongside every report so a reader can judge whether it is
 * appropriate for their own target/session, never silently assumed.
 */
internal const val DEFAULT_REGION_EDGE_FRACTION: Double = 0.2

/**
 * Classifies ([xPx], [yPx]) against a [widthPx] x [heightPx] buffer: [PointRegion.CORNER] when it is
 * within [edgeFraction] of the near edge on *both* axes, [PointRegion.EDGE] when within [edgeFraction]
 * on exactly one axis, [PointRegion.CENTER] otherwise. Pure geometry — does not know or care whether
 * ([xPx], [yPx]) is itself inside `[0, widthPx] x [0, heightPx]`; a caller projecting a point outside
 * the buffer should apply its own out-of-bounds rejection separately (see `FrameContentProjection.kt`).
 */
internal fun classifyPointRegion(
    xPx: Double,
    yPx: Double,
    widthPx: Int,
    heightPx: Int,
    edgeFraction: Double = DEFAULT_REGION_EDGE_FRACTION,
): PointRegion {
    require(widthPx > 0) { "widthPx must be positive; was $widthPx" }
    require(heightPx > 0) { "heightPx must be positive; was $heightPx" }
    require(edgeFraction in 0.0..0.5) { "edgeFraction must be within [0, 0.5]; was $edgeFraction" }

    val nearLeft = xPx <= widthPx * edgeFraction
    val nearRight = xPx >= widthPx * (1.0 - edgeFraction)
    val nearTop = yPx <= heightPx * edgeFraction
    val nearBottom = yPx >= heightPx * (1.0 - edgeFraction)
    val nearHorizontalEdge = nearLeft || nearRight
    val nearVerticalEdge = nearTop || nearBottom

    return when {
        nearHorizontalEdge && nearVerticalEdge -> PointRegion.CORNER
        nearHorizontalEdge || nearVerticalEdge -> PointRegion.EDGE
        else -> PointRegion.CENTER
    }
}
