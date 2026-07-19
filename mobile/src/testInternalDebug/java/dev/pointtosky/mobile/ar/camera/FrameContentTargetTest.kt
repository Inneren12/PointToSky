package dev.pointtosky.mobile.ar.camera

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Printable target geometry (task §3's "make the physical target reproducible" fix). */
class FrameContentTargetTest {
    @Test
    fun `marker diameter is derived from the regular dot diameter and the area scale factor, never independently chosen`() {
        val spec = FrameContentTargetSpec(regularDotDiameterMm = 8.0, markerAreaScaleFactor = 2.5)
        assertTrue(kotlin.math.abs(spec.markerDiameterMm - 8.0 * sqrt(2.5)) < 1e-9)

        val custom = FrameContentTargetSpec(regularDotDiameterMm = 5.0, markerAreaScaleFactor = 4.0)
        assertTrue(kotlin.math.abs(custom.markerDiameterMm - 5.0 * sqrt(4.0)) < 1e-9)
    }

    @Test
    fun `printable bounds contain every regular dot and the marker at their full printed radius`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        val bounds = frameContentTargetPrintableBounds(spec)
        val regularRadiusMm = spec.regularDotDiameterMm / 2.0
        val markerRadiusMm = spec.markerDiameterMm / 2.0

        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            assertTrue(objectPoint.xMm - regularRadiusMm >= bounds.minXMm - 1e-9, "point ${objectPoint.pointId.label} left edge outside bounds")
            assertTrue(objectPoint.xMm + regularRadiusMm <= bounds.maxXMm + 1e-9, "point ${objectPoint.pointId.label} right edge outside bounds")
            assertTrue(objectPoint.yMm - regularRadiusMm >= bounds.minYMm - 1e-9, "point ${objectPoint.pointId.label} top edge outside bounds")
            assertTrue(objectPoint.yMm + regularRadiusMm <= bounds.maxYMm + 1e-9, "point ${objectPoint.pointId.label} bottom edge outside bounds")
        }
        assertTrue(spec.markerOffsetXMm - markerRadiusMm >= bounds.minXMm - 1e-9)
        assertTrue(spec.markerOffsetXMm + markerRadiusMm <= bounds.maxXMm + 1e-9)
        assertTrue(spec.markerOffsetYMm - markerRadiusMm >= bounds.minYMm - 1e-9)
        assertTrue(spec.markerOffsetYMm + markerRadiusMm <= bounds.maxYMm + 1e-9)
    }

    @Test
    fun `the default marker offset is strictly nearer R0C0 than every other grid corner`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        val distanceToR0C0 = hypot(spec.markerOffsetXMm, spec.markerOffsetYMm)
        val otherCorners =
            listOf(
                (spec.cornerCols - 1) * spec.dotSpacingMm to 0.0,
                0.0 to (spec.cornerRows - 1) * spec.dotSpacingMm,
                (spec.cornerCols - 1) * spec.dotSpacingMm to (spec.cornerRows - 1) * spec.dotSpacingMm,
            )
        otherCorners.forEach { (cornerXMm, cornerYMm) ->
            val distanceToOtherCorner = hypot(spec.markerOffsetXMm - cornerXMm, spec.markerOffsetYMm - cornerYMm)
            assertTrue(distanceToR0C0 < distanceToOtherCorner)
        }
    }

    @Test
    fun `a marker offset ambiguous between two grid corners is rejected at construction, not merely documented`() {
        // Roughly equidistant between R0C0 (0,0) and R0C(cornerCols-1) (100,0) for the default spacing —
        // this is exactly the kind of misconfiguration task §3 requires failing fast, not silently
        // producing a physically ambiguous printed target.
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = 50.0, markerOffsetYMm = -5.0)
        }
    }

    @Test
    fun `a marker offset exactly at R0C0 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = 0.0, markerOffsetYMm = 0.0)
        }
    }

    @Test
    fun `the SVG exporter renders every regular dot, exactly one orientation marker, and physical millimetre dimensions`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        val svg = buildFrameContentTargetSvg(spec)

        assertTrue(svg.contains("<svg "))
        assertTrue(svg.contains("</svg>"))
        val regularDotCount = Regex("data-point-id=").findAll(svg).count()
        assertTrue(regularDotCount == spec.pointCount, "expected ${spec.pointCount} regular dots, found $regularDotCount")
        val markerCount = Regex("data-role=\"orientation-marker\"").findAll(svg).count()
        assertTrue(markerCount == 1, "expected exactly 1 orientation marker, found $markerCount")

        val bounds = frameContentTargetPrintableBounds(spec)
        val marginMm = 10.0
        val expectedWidthMm = bounds.widthMm + marginMm * 2
        val expectedHeightMm = bounds.heightMm + marginMm * 2
        assertTrue(svg.contains("width=\"${expectedWidthMm}mm\""))
        assertTrue(svg.contains("height=\"${expectedHeightMm}mm\""))
    }

    @Test
    fun `every point ID printed in the SVG matches a real object point label`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        val svg = buildFrameContentTargetSvg(spec)
        val expectedLabels = frameContentTargetObjectPoints(spec).map { it.pointId.label }.toSet()
        val printedLabels = Regex("""data-point-id="([^"]+)"""").findAll(svg).map { it.groupValues[1] }.toSet()
        kotlin.test.assertEquals(expectedLabels, printedLabels)
    }

    // ---- Physical non-overlap invariant (printed-circle-overlap fix) ----

    @Test
    fun `the default target passes every physical separation invariant`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        assertTrue(spec.minimumBlobClearanceMm > 0.0 && spec.minimumBlobClearanceMm.isFinite())
        val regularRadiusMm = spec.regularDotDiameterMm / 2.0
        val markerRadiusMm = spec.markerDiameterMm / 2.0
        for (objectPoint in frameContentTargetObjectPoints(spec)) {
            val distance = hypot(spec.markerOffsetXMm - objectPoint.xMm, spec.markerOffsetYMm - objectPoint.yMm)
            assertTrue(
                distance > markerRadiusMm + regularRadiusMm + spec.minimumBlobClearanceMm,
                "marker must clear ${objectPoint.pointId.label} by more than minimumBlobClearanceMm",
            )
        }
        assertTrue(spec.dotSpacingMm > spec.regularDotDiameterMm + spec.minimumBlobClearanceMm)
    }

    @Test
    fun `a marker centre different from R0C0 but overlapping R0C0's printed circle is rejected`() {
        // (-3, -3) is not the coincident-with-R0C0 case (already covered above) and is still strictly
        // nearer R0C0 than every other grid corner, so only the physical non-overlap check can reject it.
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = -3.0, markerOffsetYMm = -3.0)
        }
    }

    @Test
    fun `a marker exactly tangent to R0C0's printed circle - zero clearance - is rejected`() {
        val regularRadiusMm = 8.0 / 2.0
        val markerRadiusMm = 8.0 * sqrt(2.5) / 2.0
        val tangentDistance = markerRadiusMm + regularRadiusMm
        val t = tangentDistance / sqrt(2.0)
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = -t, markerOffsetYMm = -t)
        }
    }

    @Test
    fun `a marker clearance just below the configured minimum is rejected`() {
        val regularRadiusMm = 8.0 / 2.0
        val markerRadiusMm = 8.0 * sqrt(2.5) / 2.0
        val justBelowMinimumClearance = FRAME_CONTENT_MINIMUM_BLOB_CLEARANCE_MM - 0.01
        val distance = markerRadiusMm + regularRadiusMm + justBelowMinimumClearance
        val t = distance / sqrt(2.0)
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = -t, markerOffsetYMm = -t)
        }
    }

    @Test
    fun `marker clearance exactly at the minimum boundary is rejected, strictly above it is accepted`() {
        // Documents the one strictness rule this invariant follows throughout this file: the required
        // inequality is a strict ">", never ">=" - so a clearance exactly equal to minimumBlobClearanceMm
        // is rejected, and only a clearance strictly greater than it is accepted.
        val regularRadiusMm = 8.0 / 2.0
        val markerRadiusMm = 8.0 * sqrt(2.5) / 2.0
        val atBoundaryDistance = markerRadiusMm + regularRadiusMm + FRAME_CONTENT_MINIMUM_BLOB_CLEARANCE_MM
        val tAtBoundary = atBoundaryDistance / sqrt(2.0)
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(markerOffsetXMm = -tAtBoundary, markerOffsetYMm = -tAtBoundary)
        }

        val aboveBoundaryDistance = atBoundaryDistance + 0.01
        val tAboveBoundary = aboveBoundaryDistance / sqrt(2.0)
        // Must not throw.
        FrameContentTargetSpec(markerOffsetXMm = -tAboveBoundary, markerOffsetYMm = -tAboveBoundary)
    }

    @Test
    fun `regularDotDiameterMm too large for dotSpacingMm is rejected`() {
        // Marker pushed far out along the same R0C0-nearest diagonal so only the dot-to-dot spacing
        // invariant is exercised here, not the marker-to-dot one (already covered above).
        assertFailsWith<IllegalArgumentException> {
            FrameContentTargetSpec(
                dotSpacingMm = 25.0,
                regularDotDiameterMm = 25.0,
                markerOffsetXMm = -100.0,
                markerOffsetYMm = -100.0,
            )
        }
    }

    @Test
    fun `every pair of printed circles in the default SVG is separated by at least the exported minimum clearance`() {
        val spec = DEFAULT_FRAME_CONTENT_TARGET_SPEC
        val svg = buildFrameContentTargetSvg(spec)
        val circleRegex = Regex("""<circle cx="([-0-9.eE]+)" cy="([-0-9.eE]+)" r="([-0-9.eE]+)"""")
        val circles =
            circleRegex.findAll(svg).map { match ->
                Triple(match.groupValues[1].toDouble(), match.groupValues[2].toDouble(), match.groupValues[3].toDouble())
            }.toList()
        kotlin.test.assertEquals(spec.pointCount + 1, circles.size)

        for (i in circles.indices) {
            for (j in i + 1 until circles.size) {
                val (ax, ay, ar) = circles[i]
                val (bx, by, br) = circles[j]
                val centerDistance = hypot(ax - bx, ay - by)
                val gap = centerDistance - ar - br
                assertTrue(
                    gap >= spec.minimumBlobClearanceMm - 1e-9,
                    "SVG circle pair ($i, $j) separated by only ${gap}mm, less than the exported " +
                        "minimumBlobClearanceMm=${spec.minimumBlobClearanceMm}mm",
                )
            }
        }
    }

    /** Renders [spec] as a synthetic image — the same flat-luma filled-circle convention
     * `FrameContentCornerDetectorTest.renderSyntheticTarget` uses, minus the rotation/override options
     * that test needs and this one does not. */
    private fun renderSyntheticTarget(
        spec: FrameContentTargetSpec,
        pxPerMm: Double = 3.0,
        marginMm: Double = 10.0,
        darkLuma: Int = 20,
        lightLuma: Int = 200,
    ): LumaBuffer {
        val bounds = frameContentTargetPrintableBounds(spec)
        val widthPx = ((bounds.widthMm + marginMm * 2) * pxPerMm).toInt()
        val heightPx = ((bounds.heightMm + marginMm * 2) * pxPerMm).toInt()
        val shiftXMm = marginMm - bounds.minXMm
        val shiftYMm = marginMm - bounds.minYMm
        val data = ByteArray(widthPx * heightPx) { lightLuma.toByte() }

        fun paintCircle(
            localXMm: Double,
            localYMm: Double,
            radiusMm: Double,
        ) {
            val cxPx = (localXMm + shiftXMm) * pxPerMm
            val cyPx = (localYMm + shiftYMm) * pxPerMm
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
        paintCircle(spec.markerOffsetXMm, spec.markerOffsetYMm, spec.markerDiameterMm / 2.0)
        return LumaBuffer(data, widthPx, heightPx, widthPx)
    }

    @Test
    fun `a custom valid target spec still renders and detects synthetically`() {
        val customSpec =
            FrameContentTargetSpec(
                cornerRows = 3,
                cornerCols = 3,
                dotSpacingMm = 30.0,
                markerAreaScaleFactor = 3.0,
                regularDotDiameterMm = 6.0,
                markerOffsetXMm = -12.0,
                markerOffsetYMm = -12.0,
                minimumBlobClearanceMm = 2.0,
            )
        val luma = renderSyntheticTarget(customSpec)
        val result = kotlin.test.assertIs<FrameContentDetectionResult.Detected>(detectFrameContentTargetCorners(luma, customSpec))
        kotlin.test.assertEquals(customSpec.pointCount, result.points.size)
        for (objectPoint in frameContentTargetObjectPoints(customSpec)) {
            assertTrue(result.points.any { it.pointId == objectPoint.pointId })
        }
    }
}
