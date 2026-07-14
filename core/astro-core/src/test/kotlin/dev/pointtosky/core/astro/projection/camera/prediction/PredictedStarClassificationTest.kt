package dev.pointtosky.core.astro.projection.camera.prediction

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Section D anchors: source-crop and display-viewport classification.
 *
 * All fixtures below use a `1000x500` buffer with a `90 deg / 90 deg` FOV (`fx=500, fy=250,
 * cx=500, cy=250` — the same worked example as [PinholeProjectionModelTest]), so the projected
 * buffer-space `imagePoint` for each (rotation, star) pair is a clean, hand-derivable point:
 * center `(500,250)`, left edge `(0,250)`, right edge `(1000,250)`, top edge `(500,0)`, or bottom
 * edge `(500,500)` — see [TestGeometryFixtures] for the exact spherical-astronomy derivation of each
 * star/rotation pair. This lets every test below reason about crop/viewport containment using known,
 * simple numbers instead of a general arbitrary projection.
 *
 * Per [dev.pointtosky.core.astro.projection.camera.CropScaleTransform], both the source-crop and
 * viewport boundary checks are **inclusive** — a point exactly on an edge counts as inside (§D6, §D8).
 */
class PredictedStarClassificationTest {
    private val instant: Instant = Instant.parse("2024-04-04T04:00:00Z")
    private val lonDeg = 20.0

    private fun projectSingle(
        rotationMatrix: FloatArray,
        star: EquatorialStarDirection,
        context: StarProjectionContext,
        rotationDegrees: Int = 0,
        cropRectLeftPx: Int? = null,
        cropRectTopPx: Int? = null,
        cropRectRightPx: Int? = null,
        cropRectBottomPx: Int? = null,
        viewportWidthPx: Int = 1000,
        viewportHeightPx: Int = 500,
    ): PredictedStarProjection {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationDegrees = rotationDegrees,
                cropRectLeftPx = cropRectLeftPx,
                cropRectTopPx = cropRectTopPx,
                cropRectRightPx = cropRectRightPx,
                cropRectBottomPx = cropRectBottomPx,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                rotationMatrix = rotationMatrix,
            )
        return (projectStars(listOf(star), context, geometry) as StarPredictionBatchResult.Ready).projections.single()
    }

    // Center (500,250): camera facing the north horizon exactly, star at the north horizon.
    private fun centerFixture() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

    // Left edge (0,250): camera facing 45 deg east of north, same north-horizon star.
    private fun leftEdgeFixture() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

    // Right edge (1000,250): camera facing 45 deg west of north, same north-horizon star.
    private fun rightEdgeFixture() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

    // Top edge (500,0): camera facing north, star 45 deg above the north horizon.
    private fun topEdgeFixture() = meridianTransitStar(instant, lonDeg, latDeg = 0.0, decDeg = 45.0, lowerCulmination = false)

    // --- Full crop --------------------------------------------------------------------------------

    @Test
    fun `full crop, matching viewport - center point is visible`() {
        val (star, context) = centerFixture()
        val result = projectSingle(NORTH_UPRIGHT_ROTATION_MATRIX, star, context)
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertNotNull(result.imagePoint)
        assertApprox(500.0, result.imagePoint!!.x)
        assertApprox(250.0, result.imagePoint.y)
        assertApprox(500.0, result.displayPoint!!.x)
        assertApprox(250.0, result.displayPoint.y)
    }

    // --- Non-zero crop origin ------------------------------------------------------------------------

    @Test
    fun `non-zero crop origin - point inside the crop is visible`() {
        val (star, context) = leftEdgeFixture()
        val result =
            projectSingle(
                NORTHEAST_UPRIGHT_ROTATION_MATRIX,
                star,
                context,
                cropRectLeftPx = 0,
                cropRectTopPx = 50,
                cropRectRightPx = 1000,
                cropRectBottomPx = 450,
                viewportWidthPx = 1000,
                viewportHeightPx = 400,
            )
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertApprox(0.0, result.imagePoint!!.x)
        assertApprox(250.0, result.imagePoint.y)
        assertApprox(0.0, result.displayPoint!!.x)
        assertApprox(200.0, result.displayPoint.y) // crop-local y = 250 - 50
    }

    // --- Point inside the full buffer but outside the crop ---------------------------------------------

    @Test
    fun `point inside the full buffer but outside a non-zero-origin crop is OUTSIDE_IMAGE`() {
        val (star, context) = leftEdgeFixture()
        val result =
            projectSingle(
                NORTHEAST_UPRIGHT_ROTATION_MATRIX,
                star,
                context,
                cropRectLeftPx = 200,
                cropRectTopPx = 0,
                cropRectRightPx = 700,
                cropRectBottomPx = 500,
            )
        assertEquals(PredictedStarClassification.OUTSIDE_IMAGE, result.classification)
        assertNotNull(result.imagePoint) // geometrically computed regardless of visibility
        assertApprox(0.0, result.imagePoint!!.x)
        assertNotNull(result.displayPoint) // never null just because it is outside the crop
    }

    // --- FILL_CENTER horizontal crop ---------------------------------------------------------------

    @Test
    fun `FILL_CENTER horizontal crop excludes a point inside the full source crop`() {
        val (star, context) = leftEdgeFixture()
        val result =
            projectSingle(
                NORTHEAST_UPRIGHT_ROTATION_MATRIX,
                star,
                context,
                viewportWidthPx = 500,
                viewportHeightPx = 500,
            )
        assertEquals(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT, result.classification)
        assertApprox(0.0, result.imagePoint!!.x) // inside the (full) source crop
        assertApprox(-250.0, result.displayPoint!!.x) // pushed left of the viewport by the horizontal center crop
    }

    // --- FILL_CENTER vertical crop -----------------------------------------------------------------

    @Test
    fun `FILL_CENTER vertical crop excludes a point inside the full source crop`() {
        val (star, context) = topEdgeFixture()
        val result =
            projectSingle(
                NORTH_UPRIGHT_ROTATION_MATRIX,
                star,
                context,
                viewportWidthPx = 2000,
                viewportHeightPx = 200,
            )
        assertEquals(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT, result.classification)
        assertApprox(500.0, result.imagePoint!!.x)
        assertApprox(0.0, result.imagePoint.y) // inside the (full) source crop
        assertApprox(-400.0, result.displayPoint!!.y) // pushed above the viewport by the vertical center crop
    }

    // --- Exact viewport edge policy (inclusive) -----------------------------------------------------

    @Test
    fun `a point exactly on the viewport edge is visible (inclusive boundary)`() {
        val (star, context) = leftEdgeFixture()
        val result = projectSingle(NORTHEAST_UPRIGHT_ROTATION_MATRIX, star, context)
        assertApprox(0.0, result.displayPoint!!.x) // exactly on the left edge of a [0,1000] viewport
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
    }

    // --- Point just outside the viewport -------------------------------------------------------------

    @Test
    fun `a point just outside the viewport by a fraction of a pixel is not visible`() {
        val (star, context) = rightEdgeFixture()
        val result =
            projectSingle(
                NORTHWEST_UPRIGHT_ROTATION_MATRIX,
                star,
                context,
                viewportWidthPx = 999,
                viewportHeightPx = 500,
            )
        assertEquals(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT, result.classification)
        assertTrue(result.displayPoint!!.x > 999.0, "expected ${result.displayPoint.x} to exceed the 999px viewport width")
    }

    // --- All four frame rotations, coupled with the matching attitude (Blocker 1 fix) ----------------
    //
    // The previous version of this test held ONE fixed, synthetic display-aligned rotation matrix
    // constant while independently varying frame.rotationDegrees across all four values - exactly the
    // "mixed basis" bug this fix closes (a real device changes both the sensor attitude, via
    // `remapForDisplay`, and frame.rotationDegrees together). This version builds all four attitude
    // matrices from ONE base pose via [remapColumnsForDisplayRotationDegrees] (mirroring
    // `remapForDisplay`'s exact column permutation) and pairs each with the corresponding
    // [pairedFrameRotationDegrees] value - see `CameraStarProjectionTest`'s "coupled rotations" section
    // for the full derivation and the off-axis (screen-right/up) anchors; this test focuses on
    // classification specifically: the on-axis star must classify VISIBLE_IN_VIEWPORT, at the exact
    // viewport center, for a fixed (non-rotation-matching) viewport, across all four real pairings.

    @Test
    fun `coupled attitude and frame rotationDegrees - on-axis star is VISIBLE_IN_VIEWPORT at center for all four`() {
        val (star, context) = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

        for (displayRotationDegrees in listOf(0, 90, 180, 270)) {
            val attitude = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, displayRotationDegrees)
            val frameRotationDegrees = pairedFrameRotationDegrees(displayRotationDegrees)
            val result =
                projectSingle(
                    attitude,
                    star,
                    context,
                    rotationDegrees = frameRotationDegrees,
                    viewportWidthPx = 1000, // fixed viewport for every display rotation, see comment above
                    viewportHeightPx = 500,
                )
            assertEquals(
                PredictedStarClassification.VISIBLE_IN_VIEWPORT,
                result.classification,
                "displayRotationDegrees=$displayRotationDegrees (frame.rotationDegrees=$frameRotationDegrees)",
            )
            assertApprox(500.0, result.displayPoint!!.x)
            assertApprox(250.0, result.displayPoint.y)
        }
    }

    private fun assertApprox(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-6,
    ) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }
}
