package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the CAM-1e FILL_CENTER [CropScaleTransform]: exact scale/offset arithmetic,
 * per-rotation forward/inverse mapping, non-zero crop origin, center-crop visibility, and boundary
 * rejection. No Android type is involved; nothing is clamped or rounded.
 */
class CropScaleTransformTest {
    private val tol = 1e-9

    private fun assertPointEquals(
        expectedX: Double,
        expectedY: Double,
        actual: PixelPoint,
        tolerance: Double = tol,
        message: String = "",
    ) {
        assertEquals(expectedX, actual.x, tolerance, "$message x")
        assertEquals(expectedY, actual.y, tolerance, "$message y")
    }

    private fun frame(
        bufferWidthPx: Int,
        bufferHeightPx: Int,
        rotationDegrees: Int = 0,
        cropRectLeftPx: Int? = null,
        cropRectTopPx: Int? = null,
        cropRectRightPx: Int? = null,
        cropRectBottomPx: Int? = null,
    ) = CameraFrameMetadata(
        timestampNanos = 0L,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        rotationDegrees = rotationDegrees,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
    )

    // ---- Basic FILL_CENTER scale/offset ---------------------------------------------------------

    @Test
    fun `same aspect ratio scales uniformly with zero offsets`() {
        val t = createFillCenterCropScaleTransform(frame(1000, 1000), viewportWidthPx = 500, viewportHeightPx = 500)

        assertEquals(0.5, t.uniformScale, tol)
        assertEquals(0.0, t.displayOffsetX, tol)
        assertEquals(0.0, t.displayOffsetY, tol)
        assertPointEquals(250.0, 250.0, t.imageToDisplay(PixelPoint(500.0, 500.0)), message = "center")
        assertPointEquals(0.0, 0.0, t.imageToDisplay(PixelPoint(0.0, 0.0)), message = "origin")
        assertPointEquals(500.0, 500.0, t.imageToDisplay(PixelPoint(1000.0, 1000.0)), message = "far corner")
    }

    @Test
    fun `source wider than viewport crops horizontally with negative offsetX and zero offsetY`() {
        // 1920x1080 into 1080x1080: scale = max(1080/1920, 1080/1080) = 1.
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertEquals(1.0, t.uniformScale, tol)
        assertEquals(-420.0, t.displayOffsetX, tol)
        assertEquals(0.0, t.displayOffsetY, tol)
        assertEquals(1920.0, t.rotatedSourceSize.width, tol)
        assertEquals(1080.0, t.rotatedSourceSize.height, tol)
        // Image center maps to viewport center.
        assertPointEquals(540.0, 540.0, t.imageToDisplay(PixelPoint(960.0, 540.0)), message = "center")
    }

    @Test
    fun `source taller than viewport crops vertically with zero offsetX and negative offsetY`() {
        // 1080x1920 into 1080x1080: scale = max(1080/1080, 1080/1920) = 1.
        val t = createFillCenterCropScaleTransform(frame(1080, 1920), viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertEquals(1.0, t.uniformScale, tol)
        assertEquals(0.0, t.displayOffsetX, tol)
        assertEquals(-420.0, t.displayOffsetY, tol)
        assertPointEquals(540.0, 540.0, t.imageToDisplay(PixelPoint(540.0, 960.0)), message = "center")
    }

    @Test
    fun `negative offset is never clamped to zero`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)
        assertTrue(t.displayOffsetX < 0.0, "offsetX must stay negative, was ${t.displayOffsetX}")
    }

    // ---- Rotation --------------------------------------------------------------------------------

    /** Expected forward mapping of the 4 crop corners + center per rotation, chosen so scale=1, offset=0. */
    @Test
    fun `rotation maps corners and center with swapped dimensions and exact round-trip`() {
        // Unrotated crop W=40, H=30 (non-square, so 90/270 dimension swap is observable).
        data class Case(
            val rotation: Int,
            val rotatedW: Double,
            val rotatedH: Double,
            // buffer corner -> expected display corner
            val corners: Map<PixelPoint, PixelPoint>,
            val center: PixelPoint,
        )

        val cases =
            listOf(
                Case(
                    rotation = 0,
                    rotatedW = 40.0,
                    rotatedH = 30.0,
                    corners =
                        mapOf(
                            PixelPoint(0.0, 0.0) to PixelPoint(0.0, 0.0),
                            PixelPoint(40.0, 0.0) to PixelPoint(40.0, 0.0),
                            PixelPoint(40.0, 30.0) to PixelPoint(40.0, 30.0),
                            PixelPoint(0.0, 30.0) to PixelPoint(0.0, 30.0),
                        ),
                    center = PixelPoint(20.0, 15.0),
                ),
                Case(
                    rotation = 90,
                    rotatedW = 30.0,
                    rotatedH = 40.0,
                    corners =
                        mapOf(
                            PixelPoint(0.0, 0.0) to PixelPoint(30.0, 0.0),
                            PixelPoint(40.0, 0.0) to PixelPoint(30.0, 40.0),
                            PixelPoint(40.0, 30.0) to PixelPoint(0.0, 40.0),
                            PixelPoint(0.0, 30.0) to PixelPoint(0.0, 0.0),
                        ),
                    center = PixelPoint(15.0, 20.0),
                ),
                Case(
                    rotation = 180,
                    rotatedW = 40.0,
                    rotatedH = 30.0,
                    corners =
                        mapOf(
                            PixelPoint(0.0, 0.0) to PixelPoint(40.0, 30.0),
                            PixelPoint(40.0, 0.0) to PixelPoint(0.0, 30.0),
                            PixelPoint(40.0, 30.0) to PixelPoint(0.0, 0.0),
                            PixelPoint(0.0, 30.0) to PixelPoint(40.0, 0.0),
                        ),
                    center = PixelPoint(20.0, 15.0),
                ),
                Case(
                    rotation = 270,
                    rotatedW = 30.0,
                    rotatedH = 40.0,
                    corners =
                        mapOf(
                            PixelPoint(0.0, 0.0) to PixelPoint(0.0, 40.0),
                            PixelPoint(40.0, 0.0) to PixelPoint(0.0, 0.0),
                            PixelPoint(40.0, 30.0) to PixelPoint(30.0, 0.0),
                            PixelPoint(0.0, 30.0) to PixelPoint(30.0, 40.0),
                        ),
                    center = PixelPoint(15.0, 20.0),
                ),
            )

        for (case in cases) {
            // viewport == rotated source size so scale = 1 and offsets = 0: forward == pure rotation.
            val viewport = PixelSize(case.rotatedW, case.rotatedH)
            val t =
                CropScaleTransform.fillCenter(
                    sourceCrop = PixelRect(0.0, 0.0, 40.0, 30.0),
                    sourceBufferSize = PixelSize(40.0, 30.0),
                    rotationDegrees = case.rotation,
                    viewportSize = viewport,
                )

            assertEquals(case.rotatedW, t.rotatedSourceSize.width, tol, "rot ${case.rotation} rotatedW")
            assertEquals(case.rotatedH, t.rotatedSourceSize.height, tol, "rot ${case.rotation} rotatedH")
            assertEquals(1.0, t.uniformScale, tol, "rot ${case.rotation} scale")
            assertEquals(0.0, t.displayOffsetX, tol, "rot ${case.rotation} offsetX")
            assertEquals(0.0, t.displayOffsetY, tol, "rot ${case.rotation} offsetY")

            for ((image, expectedDisplay) in case.corners) {
                val display = t.imageToDisplay(image)
                assertPointEquals(
                    expectedDisplay.x,
                    expectedDisplay.y,
                    display,
                    message = "rot ${case.rotation} corner $image",
                )
                // Inverse restores the original buffer corner.
                val back = t.displayToImage(display)
                assertPointEquals(image.x, image.y, back, message = "rot ${case.rotation} inverse of $image")
            }

            // Crop center always maps to viewport center under scale=1/offset=0.
            val displayCenter = t.imageToDisplay(PixelPoint(20.0, 15.0))
            assertPointEquals(
                case.center.x,
                case.center.y,
                displayCenter,
                message = "rot ${case.rotation} center",
            )
            assertPointEquals(
                20.0,
                15.0,
                t.displayToImage(displayCenter),
                message = "rot ${case.rotation} inverse center",
            )
        }
    }

    // ---- Non-zero crop origin --------------------------------------------------------------------

    @Test
    fun `non-zero crop origin maps crop-local coordinates and inverse restores buffer coordinates`() {
        // buffer 2000x1500, crop left=100, top=200, right=1900, bottom=1400 -> cropW=1800, cropH=1200.
        val t =
            createFillCenterCropScaleTransform(
                frame(
                    bufferWidthPx = 2000,
                    bufferHeightPx = 1500,
                    cropRectLeftPx = 100,
                    cropRectTopPx = 200,
                    cropRectRightPx = 1900,
                    cropRectBottomPx = 1400,
                ),
                viewportWidthPx = 1800,
                viewportHeightPx = 1200,
            )

        // Crop is honoured (not the full buffer) and origin is not assumed to be (0,0).
        assertEquals(PixelRect(100.0, 200.0, 1900.0, 1400.0), t.sourceCrop)
        assertEquals(1.0, t.uniformScale, tol)
        assertEquals(0.0, t.displayOffsetX, tol)
        assertEquals(0.0, t.displayOffsetY, tol)

        // Crop origin (buffer 100,200) is crop-local (0,0) -> display (0,0).
        assertPointEquals(0.0, 0.0, t.imageToDisplay(PixelPoint(100.0, 200.0)), message = "crop origin")
        // Crop center (buffer 1000,800) -> viewport center (900,600).
        assertPointEquals(900.0, 600.0, t.imageToDisplay(PixelPoint(1000.0, 800.0)), message = "crop center")
        // Inverse restores exact buffer-relative coordinates.
        assertPointEquals(100.0, 200.0, t.displayToImage(PixelPoint(0.0, 0.0)), message = "inverse crop origin")
        assertPointEquals(1000.0, 800.0, t.displayToImage(PixelPoint(900.0, 600.0)), message = "inverse crop center")
    }

    @Test
    fun `crop rect equal to full buffer is honoured not ignored`() {
        val withCrop =
            createFillCenterCropScaleTransform(
                frame(
                    1920,
                    1080,
                    cropRectLeftPx = 0,
                    cropRectTopPx = 0,
                    cropRectRightPx = 1920,
                    cropRectBottomPx = 1080,
                ),
                viewportWidthPx = 1080,
                viewportHeightPx = 1080,
            )
        assertEquals(PixelRect(0.0, 0.0, 1920.0, 1080.0), withCrop.sourceCrop)
    }

    @Test
    fun `absent crop rect derives full buffer bounds`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)
        assertEquals(PixelRect(0.0, 0.0, 1920.0, 1080.0), t.sourceCrop)
    }

    // ---- Center-crop visibility ------------------------------------------------------------------

    @Test
    fun `horizontal center crop visible region and corner inverse mapping`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)

        // Only the central 1080-wide band of the 1920 source is shown.
        assertEquals(PixelRect(420.0, 0.0, 1500.0, 1080.0), t.visibleImageRect)
        // Viewport corners inverse-map into the visible source region, not the crop corners.
        assertPointEquals(420.0, 0.0, t.displayToImage(PixelPoint(0.0, 0.0)), message = "viewport TL")
        assertPointEquals(1500.0, 1080.0, t.displayToImage(PixelPoint(1080.0, 1080.0)), message = "viewport BR")
        // visibleDisplayRect is always the full viewport under FILL_CENTER.
        assertEquals(PixelRect(0.0, 0.0, 1080.0, 1080.0), t.visibleDisplayRect)
    }

    @Test
    fun `vertical center crop visible region and corner inverse mapping`() {
        val t = createFillCenterCropScaleTransform(frame(1080, 1920), viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertEquals(PixelRect(0.0, 420.0, 1080.0, 1500.0), t.visibleImageRect)
        assertPointEquals(0.0, 420.0, t.displayToImage(PixelPoint(0.0, 0.0)), message = "viewport TL")
        assertPointEquals(1080.0, 1500.0, t.displayToImage(PixelPoint(1080.0, 1080.0)), message = "viewport BR")
    }

    @Test
    fun `image point inside viewport is visible and one cropped out by center crop is not`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)

        // Center of the source is shown.
        assertTrue(t.isImagePointVisible(PixelPoint(960.0, 540.0)), "center should be visible")
        // A source point inside the crop but left of the visible band (x=100 < 420) is cropped out.
        assertTrue(t.sourceCrop.contains(PixelPoint(100.0, 540.0)), "point is inside the crop")
        assertFalse(t.isImagePointVisible(PixelPoint(100.0, 540.0)), "center-cropped point must be invisible")
    }

    @Test
    fun `display point inside viewport is inside visible image and one outside is not`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)

        assertTrue(t.isDisplayPointInsideVisibleImage(PixelPoint(540.0, 540.0)), "viewport center")
        assertTrue(t.isDisplayPointInsideVisibleImage(PixelPoint(0.0, 0.0)), "viewport corner")
        assertFalse(t.isDisplayPointInsideVisibleImage(PixelPoint(-1.0, 540.0)), "left of viewport")
        assertFalse(t.isDisplayPointInsideVisibleImage(PixelPoint(1081.0, 540.0)), "right of viewport")
    }

    @Test
    fun `points outside the viewport are not silently clamped by the inverse mapping`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)

        // display x=-100 inverse-maps to buffer x = (-100 - (-420)) / 1 = 320, NOT clamped to the
        // visible band left edge 420.
        val back = t.displayToImage(PixelPoint(-100.0, 0.0))
        assertEquals(320.0, back.x, tol)
        assertTrue(back.x < t.visibleImageRect.left, "inverse must not be clamped into the visible region")
    }

    // ---- Rect mapping ----------------------------------------------------------------------------

    @Test
    fun `imageRectToDisplay maps a buffer rect to its display bounding rect`() {
        val t = createFillCenterCropScaleTransform(frame(1000, 1000), viewportWidthPx = 500, viewportHeightPx = 500)
        val display = t.imageRectToDisplay(PixelRect(200.0, 400.0, 600.0, 800.0))
        assertEquals(PixelRect(100.0, 200.0, 300.0, 400.0), display)
    }

    // ---- Boundaries and validation ---------------------------------------------------------------

    @Test
    fun `zero or negative viewport dimensions are rejected`() {
        listOf(0, -1, -1080).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("viewportWidth=$bad") {
                createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = bad, viewportHeightPx = 1080)
            }
            assertFailsWith<IllegalArgumentException>("viewportHeight=$bad") {
                createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = bad)
            }
        }
    }

    @Test
    fun `invalid rotation is rejected by the metadata model`() {
        listOf(-90, 1, 45, 360).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("rotation=$bad") { frame(1920, 1080, rotationDegrees = bad) }
        }
    }

    @Test
    fun `invalid crop is rejected by the metadata model`() {
        // right <= left
        assertFailsWith<IllegalArgumentException> {
            frame(1920, 1080, cropRectLeftPx = 500, cropRectTopPx = 0, cropRectRightPx = 400, cropRectBottomPx = 100)
        }
        // crop exceeds buffer
        assertFailsWith<IllegalArgumentException> {
            frame(1920, 1080, cropRectLeftPx = 0, cropRectTopPx = 0, cropRectRightPx = 1921, cropRectBottomPx = 1080)
        }
    }

    @Test
    fun `fillCenter rejects an invalid rotation directly`() {
        assertFailsWith<IllegalArgumentException> {
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 100.0, 100.0),
                sourceBufferSize = PixelSize(100.0, 100.0),
                rotationDegrees = 45,
                viewportSize = PixelSize(50.0, 50.0),
            )
        }
    }

    // The public creation API takes only source geometry, rotation, and viewport geometry — there is
    // no parameter through which a caller can supply uniformScale, rotatedSourceSize, or the offsets;
    // the factory derives them. (The private constructor makes any other instantiation a compile
    // error, so this is proven by the source API, not a runtime test.) The tests below prove the
    // derivation is correct.

    @Test
    fun `fillCenter derives the rotated source size`() {
        // 0 and 180 keep the crop's own dimensions; 90 and 270 swap them.
        val crop = PixelRect(0.0, 0.0, 4000.0, 3000.0)
        val buffer = PixelSize(4000.0, 3000.0)
        val viewport = PixelSize(1080.0, 1080.0)
        mapOf(
            0 to PixelSize(4000.0, 3000.0),
            90 to PixelSize(3000.0, 4000.0),
            180 to PixelSize(4000.0, 3000.0),
            270 to PixelSize(3000.0, 4000.0),
        ).forEach { (rotation, expected) ->
            val t = CropScaleTransform.fillCenter(crop, buffer, rotation, viewport)
            assertEquals(expected, t.rotatedSourceSize, "rotation=$rotation")
        }
    }

    @Test
    fun `fillCenter derives the uniform scale as the max of the per-axis ratios`() {
        // 1920x1080 into 1080x1080: max(1080/1920, 1080/1080) = 1.0
        val wider = CropScaleTransform.fillCenter(
            PixelRect(0.0, 0.0, 1920.0, 1080.0),
            PixelSize(1920.0, 1080.0),
            0,
            PixelSize(1080.0, 1080.0),
        )
        assertEquals(1.0, wider.uniformScale, tol)
        // 1000x1000 into 500x500: max(0.5, 0.5) = 0.5
        val square = CropScaleTransform.fillCenter(
            PixelRect(0.0, 0.0, 1000.0, 1000.0),
            PixelSize(1000.0, 1000.0),
            0,
            PixelSize(500.0, 500.0),
        )
        assertEquals(0.5, square.uniformScale, tol)
    }

    @Test
    fun `fillCenter derives centered offsets, one negative under center crop`() {
        val t = CropScaleTransform.fillCenter(
            PixelRect(0.0, 0.0, 1920.0, 1080.0),
            PixelSize(1920.0, 1080.0),
            0,
            PixelSize(1080.0, 1080.0),
        )
        // scaledW = 1920, scaledH = 1080 -> offsetX = (1080-1920)/2 = -420, offsetY = 0
        assertEquals(-420.0, t.displayOffsetX, tol)
        assertEquals(0.0, t.displayOffsetY, tol)
    }

    @Test
    fun `fillCenter rejects a crop with a slightly negative origin`() {
        assertFailsWith<IllegalArgumentException> {
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(-0.5, 0.0, 100.0, 100.0),
                sourceBufferSize = PixelSize(100.0, 100.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(50.0, 50.0),
            )
        }
    }

    @Test
    fun `fillCenter rejects a crop right slightly beyond the buffer width`() {
        assertFailsWith<IllegalArgumentException> {
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 100.5, 100.0),
                sourceBufferSize = PixelSize(100.0, 100.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(50.0, 50.0),
            )
        }
    }

    @Test
    fun `fillCenter rejects a crop bottom slightly beyond the buffer height`() {
        assertFailsWith<IllegalArgumentException> {
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 100.0, 100.5),
                sourceBufferSize = PixelSize(100.0, 100.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(50.0, 50.0),
            )
        }
    }

    @Test
    fun `fillCenter accepts a crop exactly on the buffer bounds`() {
        // Strict bounds are inclusive of the exact edge; full-buffer crop must be accepted.
        val t = CropScaleTransform.fillCenter(
            sourceCrop = PixelRect(0.0, 0.0, 100.0, 100.0),
            sourceBufferSize = PixelSize(100.0, 100.0),
            rotationDegrees = 0,
            viewportSize = PixelSize(50.0, 50.0),
        )
        assertEquals(PixelRect(0.0, 0.0, 100.0, 100.0), t.sourceCrop)
    }

    @Test
    fun `visibility helpers reject an invalid tolerance instead of returning false`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)
        val badTolerances = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        badTolerances.forEach { bad ->
            assertFailsWith<IllegalArgumentException>("isImagePointVisible tol=$bad") {
                t.isImagePointVisible(PixelPoint(960.0, 540.0), tolerancePx = bad)
            }
            assertFailsWith<IllegalArgumentException>("isDisplayPointInsideVisibleImage tol=$bad") {
                t.isDisplayPointInsideVisibleImage(PixelPoint(540.0, 540.0), tolerancePx = bad)
            }
        }
    }

    @Test
    fun `visibility helpers accept zero and positive finite tolerance`() {
        val t = createFillCenterCropScaleTransform(frame(1920, 1080), viewportWidthPx = 1080, viewportHeightPx = 1080)
        assertTrue(t.isImagePointVisible(PixelPoint(960.0, 540.0), tolerancePx = 0.0))
        assertTrue(t.isImagePointVisible(PixelPoint(960.0, 540.0), tolerancePx = 2.5))
        assertTrue(t.isDisplayPointInsideVisibleImage(PixelPoint(540.0, 540.0), tolerancePx = 0.0))
        assertTrue(t.isDisplayPointInsideVisibleImage(PixelPoint(540.0, 540.0), tolerancePx = 2.5))
    }

    @Test
    fun `source crop and viewport edges map finitely with no NaN or infinity`() {
        val t =
            createFillCenterCropScaleTransform(
                frame(
                    1920,
                    1080,
                    cropRectLeftPx = 100,
                    cropRectTopPx = 50,
                    cropRectRightPx = 1800,
                    cropRectBottomPx = 1000,
                ),
                viewportWidthPx = 1080,
                viewportHeightPx = 1080,
            )
        val cropEdges =
            listOf(
                PixelPoint(100.0, 50.0),
                PixelPoint(1800.0, 50.0),
                PixelPoint(1800.0, 1000.0),
                PixelPoint(100.0, 1000.0),
            )
        cropEdges.forEach { p ->
            val d = t.imageToDisplay(p)
            assertTrue(d.x.isFinite() && d.y.isFinite(), "crop edge $p mapped non-finite: $d")
        }
        val viewportEdges =
            listOf(
                PixelPoint(0.0, 0.0),
                PixelPoint(1080.0, 0.0),
                PixelPoint(1080.0, 1080.0),
                PixelPoint(0.0, 1080.0),
            )
        viewportEdges.forEach { p ->
            val i = t.displayToImage(p)
            assertTrue(i.x.isFinite() && i.y.isFinite(), "viewport edge $p mapped non-finite: $i")
        }
    }

    @Test
    fun `very large but finite dimensions produce finite mappings and exact round-trips`() {
        val t =
            createFillCenterCropScaleTransform(
                frame(100_000, 80_000, rotationDegrees = 90),
                viewportWidthPx = 20_000,
                viewportHeightPx = 20_000,
            )
        val p = PixelPoint(37_123.0, 51_777.0)
        val display = t.imageToDisplay(p)
        assertTrue(display.x.isFinite() && display.y.isFinite(), "display non-finite: $display")
        val back = t.displayToImage(display)
        assertPointEquals(p.x, p.y, back, tolerance = 1e-6, message = "large round-trip")
    }
}
