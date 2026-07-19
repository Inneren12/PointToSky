package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §3). Deterministic SVG
 * exporter for [FrameContentTargetSpec] — printing this exact file (an SVG viewer/printer that honours
 * the document's own physical `width`/`height` in millimetres reproduces the target at true size) is
 * what makes a device evidence run reproducible: the printed dot spacing, regular-dot diameter, and
 * orientation-marker size/position always match the [FrameContentTargetSpec] a report was captured
 * against, never an ad hoc hand-drawn substitute.
 *
 * Every device report exports the exact [FrameContentTargetSpec] used (see
 * `FrameContentCorrespondenceExport.kt`'s `TARGET`/`target` sections) — this function is the
 * corresponding *generator*, so that spec is always reproducible as a physical object, not just as a
 * number in a report.
 */
internal fun buildFrameContentTargetSvg(
    spec: FrameContentTargetSpec = DEFAULT_FRAME_CONTENT_TARGET_SPEC,
    marginMm: Double = 10.0,
): String {
    require(marginMm >= 0.0 && marginMm.isFinite()) { "marginMm must be finite and non-negative; was $marginMm" }
    val bounds = frameContentTargetPrintableBounds(spec)
    val widthMm = bounds.widthMm + marginMm * 2
    val heightMm = bounds.heightMm + marginMm * 2
    // Shifts every local-frame coordinate so the printable content (including the margin) starts at
    // SVG-document origin (0, 0) — the SVG's own coordinate system, never the target's local mm frame.
    val shiftXMm = marginMm - bounds.minXMm
    val shiftYMm = marginMm - bounds.minYMm

    return buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<svg xmlns="http://www.w3.org/2000/svg" width="${widthMm}mm" height="${heightMm}mm" """ +
                """viewBox="0 0 $widthMm $heightMm">""",
        )
        appendLine("""  <!-- CAM-2c frame-content correspondence experiment: printable dot-grid target -->""")
        appendLine(
            """  <!-- cornerRows=${spec.cornerRows} cornerCols=${spec.cornerCols} dotSpacingMm=${spec.dotSpacingMm} """ +
                """regularDotDiameterMm=${spec.regularDotDiameterMm} markerAreaScaleFactor=${spec.markerAreaScaleFactor} """ +
                """markerDiameterMm=${spec.markerDiameterMm} markerOffsetXMm=${spec.markerOffsetXMm} """ +
                """markerOffsetYMm=${spec.markerOffsetYMm} minimumBlobClearanceMm=${spec.minimumBlobClearanceMm} -->""",
        )
        appendLine("""  <rect x="0" y="0" width="$widthMm" height="$heightMm" fill="white"/>""")
        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            val cx = objectPoint.xMm + shiftXMm
            val cy = objectPoint.yMm + shiftYMm
            appendLine(
                """  <circle cx="$cx" cy="$cy" r="${spec.regularDotDiameterMm / 2.0}" fill="black" """ +
                    """data-point-id="${objectPoint.pointId.label}"/>""",
            )
        }
        val markerCx = spec.markerOffsetXMm + shiftXMm
        val markerCy = spec.markerOffsetYMm + shiftYMm
        appendLine(
            """  <circle cx="$markerCx" cy="$markerCy" r="${spec.markerDiameterMm / 2.0}" fill="black" """ +
                """data-role="orientation-marker"/>""",
        )
        appendLine("</svg>")
    }
}
