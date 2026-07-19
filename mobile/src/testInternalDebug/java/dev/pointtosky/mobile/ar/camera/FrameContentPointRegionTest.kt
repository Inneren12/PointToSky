package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameContentPointRegionTest {
    @Test
    fun `dead center classifies as CENTER`() {
        assertEquals(PointRegion.CENTER, classifyPointRegion(320.0, 240.0, 640, 480))
    }

    @Test
    fun `near left edge but away from top-bottom classifies as EDGE`() {
        assertEquals(PointRegion.EDGE, classifyPointRegion(5.0, 240.0, 640, 480))
    }

    @Test
    fun `near top-left corner classifies as CORNER`() {
        assertEquals(PointRegion.CORNER, classifyPointRegion(2.0, 2.0, 640, 480))
    }

    @Test
    fun `near bottom-right corner classifies as CORNER`() {
        assertEquals(PointRegion.CORNER, classifyPointRegion(638.0, 478.0, 640, 480))
    }

    @Test
    fun `just outside the edge fraction classifies as CENTER`() {
        // edgeFraction defaults to 0.2, so x=129 is just past the 128px (0.2*640) boundary.
        assertEquals(PointRegion.CENTER, classifyPointRegion(129.0, 240.0, 640, 480))
    }

    @Test
    fun `custom edge fraction changes the boundary`() {
        // With edgeFraction=0.3, x=50 is inside the left 30% band (nearLeft) but y=240 (buffer center)
        // is outside both the top and bottom 30% bands — exactly one axis near an edge, so EDGE.
        assertEquals(PointRegion.EDGE, classifyPointRegion(50.0, 240.0, 640, 480, edgeFraction = 0.3))
    }
}
