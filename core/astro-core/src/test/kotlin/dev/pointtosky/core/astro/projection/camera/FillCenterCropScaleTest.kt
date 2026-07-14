package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic property-style round-trip coverage for the CAM-1e FILL_CENTER [CropScaleTransform]
 * (CAM-1e "Round-trip property-style coverage"). For every rotation and several aspect-ratio and
 * crop-origin combinations, asserts the forward and inverse mappings are exact inverses over a fixed
 * grid of representative points:
 *
 * ```text
 * displayToImage(imageToDisplay(p)) ≈ p
 * imageToDisplay(displayToImage(q)) ≈ q
 * ```
 *
 * No property-testing library is used — the point grids and scenarios are enumerated deterministically.
 */
class FillCenterCropScaleTest {
    private val roundTripTol = 1e-6

    private data class Scenario(
        val name: String,
        val crop: PixelRect,
        val buffer: PixelSize,
        val viewport: PixelSize,
    )

    private fun fullBuffer(
        name: String,
        width: Double,
        height: Double,
        viewport: PixelSize,
    ) = Scenario(name, PixelRect(0.0, 0.0, width, height), PixelSize(width, height), viewport)

    private val scenarios =
        listOf(
            fullBuffer("square source, square viewport", 1000.0, 1000.0, PixelSize(720.0, 720.0)),
            fullBuffer("wide source, square viewport", 1920.0, 1080.0, PixelSize(1080.0, 1080.0)),
            fullBuffer("tall source, square viewport", 1080.0, 1920.0, PixelSize(1080.0, 1080.0)),
            fullBuffer("wide source, tall viewport", 4000.0, 3000.0, PixelSize(1080.0, 2160.0)),
            fullBuffer("tall source, wide viewport", 3000.0, 4000.0, PixelSize(2160.0, 1080.0)),
            Scenario(
                "non-zero crop origin, wide viewport",
                crop = PixelRect(100.0, 200.0, 1900.0, 1400.0),
                buffer = PixelSize(2000.0, 1500.0),
                viewport = PixelSize(1280.0, 720.0),
            ),
        )

    private val rotations = listOf(0, 90, 180, 270)

    /** Fractions along each axis: corners, edge midpoints, center, and arbitrary interior points. */
    private val fractions = listOf(0.0, 0.25, 0.5, 0.5001, 0.73, 1.0)

    @Test
    fun `image to display to image is identity for all rotations and scenarios`() {
        for (scenario in scenarios) {
            for (rotation in rotations) {
                val t =
                    CropScaleTransform.fillCenter(
                        sourceCrop = scenario.crop,
                        sourceBufferSize = scenario.buffer,
                        rotationDegrees = rotation,
                        viewportSize = scenario.viewport,
                    )
                for (fx in fractions) {
                    for (fy in fractions) {
                        val p =
                            PixelPoint(
                                x = scenario.crop.left + fx * scenario.crop.width,
                                y = scenario.crop.top + fy * scenario.crop.height,
                            )
                        val back = t.displayToImage(t.imageToDisplay(p))
                        val where = "${scenario.name} rot $rotation image->display->image at ($fx,$fy)"
                        assertEquals(p.x, back.x, roundTripTol, "$where x")
                        assertEquals(p.y, back.y, roundTripTol, "$where y")
                    }
                }
            }
        }
    }

    @Test
    fun `display to image to display is identity for all rotations and scenarios`() {
        for (scenario in scenarios) {
            for (rotation in rotations) {
                val t =
                    CropScaleTransform.fillCenter(
                        sourceCrop = scenario.crop,
                        sourceBufferSize = scenario.buffer,
                        rotationDegrees = rotation,
                        viewportSize = scenario.viewport,
                    )
                for (fx in fractions) {
                    for (fy in fractions) {
                        // Sample display points across (and slightly beyond) the viewport.
                        val q =
                            PixelPoint(
                                x = (fx - 0.1) * scenario.viewport.width,
                                y = (fy - 0.1) * scenario.viewport.height,
                            )
                        val back = t.imageToDisplay(t.displayToImage(q))
                        val where = "${scenario.name} rot $rotation display->image->display at ($fx,$fy)"
                        assertEquals(q.x, back.x, roundTripTol, "$where x")
                        assertEquals(q.y, back.y, roundTripTol, "$where y")
                    }
                }
            }
        }
    }

    @Test
    fun `viewport is always fully covered so every viewport point maps into the crop`() {
        for (scenario in scenarios) {
            for (rotation in rotations) {
                val t =
                    CropScaleTransform.fillCenter(
                        sourceCrop = scenario.crop,
                        sourceBufferSize = scenario.buffer,
                        rotationDegrees = rotation,
                        viewportSize = scenario.viewport,
                    )
                // Every corner of the viewport inverse-maps inside the source crop (FILL_CENTER
                // fully covers the viewport, so no viewport point falls outside the source).
                val corners =
                    listOf(
                        PixelPoint(0.0, 0.0),
                        PixelPoint(scenario.viewport.width, 0.0),
                        PixelPoint(scenario.viewport.width, scenario.viewport.height),
                        PixelPoint(0.0, scenario.viewport.height),
                    )
                corners.forEach { corner ->
                    assertTrue(
                        t.isDisplayPointInsideVisibleImage(corner),
                        "${scenario.name} rot $rotation viewport corner $corner should be inside the visible image",
                    )
                    assertTrue(
                        scenario.crop.contains(t.displayToImage(corner), tolerancePx = 1e-6),
                        "${scenario.name} rot $rotation viewport corner $corner inverse should lie in the crop",
                    )
                }
            }
        }
    }

    // ---- Factory from CameraFrameMetadata --------------------------------------------------------

    @Test
    fun `factory derives full buffer crop when metadata has no crop rect`() {
        val frame =
            CameraFrameMetadata(
                timestampNanos = 42L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                rotationDegrees = 0,
            )
        val t = createFillCenterCropScaleTransform(frame, viewportWidthPx = 1080, viewportHeightPx = 1080)
        assertEquals(PixelRect(0.0, 0.0, 1920.0, 1080.0), t.sourceCrop)
        assertEquals(PixelSize(1920.0, 1080.0), t.sourceBufferSize)
    }

    @Test
    fun `factory carries rotation and non-zero crop from metadata`() {
        val frame =
            CameraFrameMetadata(
                timestampNanos = 42L,
                bufferWidthPx = 4000,
                bufferHeightPx = 3000,
                rotationDegrees = 270,
                cropRectLeftPx = 120,
                cropRectTopPx = 60,
                cropRectRightPx = 3880,
                cropRectBottomPx = 2940,
            )
        val t = createFillCenterCropScaleTransform(frame, viewportWidthPx = 1080, viewportHeightPx = 1920)
        assertEquals(270, t.rotationDegrees)
        assertEquals(PixelRect(120.0, 60.0, 3880.0, 2940.0), t.sourceCrop)
        // rotation 270 swaps the crop dimensions.
        assertEquals(PixelSize(2880.0, 3760.0), t.rotatedSourceSize)
    }
}
