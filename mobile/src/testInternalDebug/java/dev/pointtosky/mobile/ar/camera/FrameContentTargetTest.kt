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
}
