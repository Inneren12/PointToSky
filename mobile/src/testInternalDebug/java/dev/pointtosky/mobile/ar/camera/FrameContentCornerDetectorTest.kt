package dev.pointtosky.mobile.ar.camera

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §2/§3/§6). Exercises
 * [detectFrameContentTargetCorners] against a **synthetically rendered** [LumaBuffer] — flat-luma filled
 * circles at exact, computable pixel positions derived from [FrameContentTargetSpec]'s own millimetre
 * geometry — rather than a real camera frame (this container has no camera hardware; see
 * `docs/validation/cam_2c_pixel9_evidence.md`). This is the first test file to actually render and
 * detect a target, rather than only exercising the residual/hypothesis/verdict math against
 * hand-constructed [DetectedTargetPoint] fixtures.
 */
class FrameContentCornerDetectorTest {
    private val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
    private val pxPerMm = 3.0
    private val marginMm = 10.0
    private val darkLuma = 20
    private val lightLuma = 200

    /** Renders [spec] as a synthetic image: flat-luma filled circles for every regular dot and the
     * marker, at exact pixel positions derived from the target's own millimetre geometry — the same
     * [frameContentTargetPrintableBounds]/margin convention [buildFrameContentTargetSvg] uses, so the
     * rendered image is a faithful (if not photorealistic) stand-in for a real photograph of the printed
     * target. [rotate180] simulates photographing the same physical target upside-down — a 180-degree
     * in-plane rotation about the canvas centre, exactly the ambiguity a plain symmetric grid cannot
     * resolve without the orientation marker (task §6). [markerOffsetOverrideMm]/[markerRadiusOverrideMm]
     * let a test render an out-of-spec marker (wrong size/position) to exercise
     * [FrameContentDetectionResult.OrientationAmbiguous] without needing an invalid [FrameContentTargetSpec]
     * (which [FrameContentTargetSpec.init] itself now rejects) — the detector never reads
     * [FrameContentTargetSpec.markerOffsetXMm]/[FrameContentTargetSpec.markerOffsetYMm] directly (task
     * §3's "the detector does not need to use the physical marker offset in its pixel math"), so this is
     * a legitimate way to probe its behavior independent of the spec's own validated geometry. */
    private fun renderSyntheticTarget(
        markerOffsetOverrideMm: Pair<Double, Double>? = null,
        markerRadiusOverrideMm: Double? = null,
        rotate180: Boolean = false,
    ): LumaBuffer {
        val bounds = frameContentTargetPrintableBounds(spec)
        val widthPx = ((bounds.widthMm + marginMm * 2) * pxPerMm).toInt()
        val heightPx = ((bounds.heightMm + marginMm * 2) * pxPerMm).toInt()
        val canvasWidthMm = widthPx / pxPerMm
        val canvasHeightMm = heightPx / pxPerMm
        val shiftXMm = marginMm - bounds.minXMm
        val shiftYMm = marginMm - bounds.minYMm
        val data = ByteArray(widthPx * heightPx) { lightLuma.toByte() }

        fun paintCircle(
            localXMm: Double,
            localYMm: Double,
            radiusMm: Double,
        ) {
            var xMm = localXMm + shiftXMm
            var yMm = localYMm + shiftYMm
            if (rotate180) {
                xMm = canvasWidthMm - xMm
                yMm = canvasHeightMm - yMm
            }
            val cxPx = xMm * pxPerMm
            val cyPx = yMm * pxPerMm
            val radiusPx = radiusMm * pxPerMm
            val minX = (cxPx - radiusPx).toInt().coerceAtLeast(0)
            val maxX = (cxPx + radiusPx).toInt().coerceAtMost(widthPx - 1)
            val minY = (cyPx - radiusPx).toInt().coerceAtLeast(0)
            val maxY = (cyPx + radiusPx).toInt().coerceAtMost(heightPx - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val dx = x + 0.5 - cxPx
                    val dy = y + 0.5 - cyPx
                    if (dx * dx + dy * dy <= radiusPx * radiusPx) {
                        data[y * widthPx + x] = darkLuma.toByte()
                    }
                }
            }
        }

        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            paintCircle(objectPoint.xMm, objectPoint.yMm, spec.regularDotDiameterMm / 2.0)
        }
        val (markerXMm, markerYMm) = markerOffsetOverrideMm ?: (spec.markerOffsetXMm to spec.markerOffsetYMm)
        val markerRadiusMm = markerRadiusOverrideMm ?: (spec.markerDiameterMm / 2.0)
        paintCircle(markerXMm, markerYMm, markerRadiusMm)

        return LumaBuffer(data, widthPx, heightPx, widthPx)
    }

    /** The exact pixel position [renderSyntheticTarget] placed [objectPoint] at — used to verify a
     * detected point's buffer coordinates, not just its assigned point ID, actually correspond to the
     * real rendered blob (never merely "the count matched"). */
    private fun expectedBufferPosition(
        localXMm: Double,
        localYMm: Double,
        rotate180: Boolean,
    ): Pair<Double, Double> {
        val bounds = frameContentTargetPrintableBounds(spec)
        val widthPx = ((bounds.widthMm + marginMm * 2) * pxPerMm).toInt()
        val heightPx = ((bounds.heightMm + marginMm * 2) * pxPerMm).toInt()
        val canvasWidthMm = widthPx / pxPerMm
        val canvasHeightMm = heightPx / pxPerMm
        val shiftXMm = marginMm - bounds.minXMm
        val shiftYMm = marginMm - bounds.minYMm
        var xMm = localXMm + shiftXMm
        var yMm = localYMm + shiftYMm
        if (rotate180) {
            xMm = canvasWidthMm - xMm
            yMm = canvasHeightMm - yMm
        }
        return (xMm * pxPerMm) to (yMm * pxPerMm)
    }

    @Test
    fun `an unrotated render resolves every point to its true semantic ID at its real rendered position`() {
        val luma = renderSyntheticTarget()
        val result = assertIs<FrameContentDetectionResult.Detected>(detectFrameContentTargetCorners(luma, spec))
        assertEquals(spec.pointCount, result.points.size)

        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            val detected = result.points.single { it.pointId == objectPoint.pointId }
            val (expectedX, expectedY) = expectedBufferPosition(objectPoint.xMm, objectPoint.yMm, rotate180 = false)
            assertTrue(kotlin.math.abs(detected.bufferXPx - expectedX) < 2.0, "X mismatch for ${objectPoint.pointId.label}")
            assertTrue(kotlin.math.abs(detected.bufferYPx - expectedY) < 2.0, "Y mismatch for ${objectPoint.pointId.label}")
        }
        assertEquals(GridCorner.TOP_LEFT, result.orientationEvidence.resolvedOriginCorner)
    }

    @Test
    fun `a 180-degree rotated render resolves to the exact same semantic point IDs at their new true positions`() {
        // The physical target is identical; only the photograph is upside-down. Without the orientation
        // marker, sort-Y/sort-X alone would confidently assign every point to the WRONG (point-symmetric)
        // object point — this is exactly the correspondence-identity bug task §6 fixed.
        val luma = renderSyntheticTarget(rotate180 = true)
        val result = assertIs<FrameContentDetectionResult.Detected>(detectFrameContentTargetCorners(luma, spec))
        assertEquals(spec.pointCount, result.points.size)

        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            val detected = result.points.single { it.pointId == objectPoint.pointId }
            val (expectedX, expectedY) = expectedBufferPosition(objectPoint.xMm, objectPoint.yMm, rotate180 = true)
            assertTrue(kotlin.math.abs(detected.bufferXPx - expectedX) < 2.0, "X mismatch for ${objectPoint.pointId.label}")
            assertTrue(kotlin.math.abs(detected.bufferYPx - expectedY) < 2.0, "Y mismatch for ${objectPoint.pointId.label}")
        }
        assertEquals(GridCorner.BOTTOM_RIGHT, result.orientationEvidence.resolvedOriginCorner)
    }

    @Test
    fun `a marker rendered the same size as a regular dot is never accepted from blob count alone`() {
        val luma = renderSyntheticTarget(markerRadiusOverrideMm = spec.regularDotDiameterMm / 2.0)
        val result = detectFrameContentTargetCorners(luma, spec)
        val ambiguous = assertIs<FrameContentDetectionResult.OrientationAmbiguous>(result)
        assertEquals(0, ambiguous.markerCandidateCount)
    }

    @Test
    fun `a marker equidistant between two grid corners remains OrientationAmbiguous, never a guessed orientation`() {
        // Placed above the midpoint of the top edge — exactly equidistant between the TOP_LEFT and
        // TOP_RIGHT raster corners for this spec's own spacing (confidence ratio 1.0, well under the
        // required threshold). The Y offset must clear the marker's own radius plus a regular dot's
        // radius, or the two blobs would touch and merge into one during flood fill, changing the total
        // candidate blob count instead of exercising the corner-confidence gate this test targets.
        val topEdgeMidpointXMm = (spec.cornerCols - 1) * spec.dotSpacingMm / 2.0
        val clearOfGridYMm = -(spec.markerDiameterMm / 2.0 + spec.regularDotDiameterMm / 2.0 + 2.0)
        val luma = renderSyntheticTarget(markerOffsetOverrideMm = topEdgeMidpointXMm to clearOfGridYMm)
        val result = detectFrameContentTargetCorners(luma, spec)
        assertIs<FrameContentDetectionResult.OrientationAmbiguous>(result)
    }

    @Test
    fun `an accepted detection exports the exact orientation evidence that justified it`() {
        val luma = renderSyntheticTarget()
        val result = assertIs<FrameContentDetectionResult.Detected>(detectFrameContentTargetCorners(luma, spec))
        val evidence = result.orientationEvidence

        assertTrue(evidence.markerAreaPx > 0)
        assertTrue(evidence.medianGridDotAreaPx > 0.0)
        assertTrue(
            evidence.observedMarkerAreaRatio >= DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES.markerAreaRatioThreshold,
            "observed ratio ${evidence.observedMarkerAreaRatio} must have cleared the acceptance threshold",
        )
        assertTrue(
            evidence.observedCornerConfidenceRatio >= DEFAULT_FRAME_CONTENT_DETECTION_TOLERANCES.markerCornerConfidenceRatio,
        )
        assertEquals(GridCorner.TOP_LEFT, evidence.resolvedOriginCorner)

        val gridGeometry = result.gridGeometryEvidence
        assertTrue(gridGeometry.minAdjacentRowSeparationPx > 0.0)
        assertTrue(gridGeometry.minWithinRowGapPx > 0.0)
        assertTrue(gridGeometry.minBetweenRowGapPx > 0.0)
    }

    @Test
    fun `the target's design marker area ratio and the detector's actual observed ratio are separate, independently computed fields`() {
        // Render a marker at a real, distinctly-larger-than-regular area ratio (1.8x) that is NOT the
        // target spec's own design ratio (2.5x) — proves observedMarkerAreaRatio is measured from the
        // actual rendered blob, never merely echoing FrameContentTargetSpec.markerAreaScaleFactor.
        val renderedAreaRatio = 1.8
        val overrideRadiusMm = (spec.regularDotDiameterMm / 2.0) * sqrt(renderedAreaRatio)
        val luma = renderSyntheticTarget(markerRadiusOverrideMm = overrideRadiusMm)
        val result = assertIs<FrameContentDetectionResult.Detected>(detectFrameContentTargetCorners(luma, spec))

        assertNotEquals(spec.markerAreaScaleFactor, result.orientationEvidence.observedMarkerAreaRatio)
        assertTrue(
            kotlin.math.abs(result.orientationEvidence.observedMarkerAreaRatio - renderedAreaRatio) < 0.3,
            "expected observedMarkerAreaRatio near $renderedAreaRatio, was ${result.orientationEvidence.observedMarkerAreaRatio}",
        )
    }
}
