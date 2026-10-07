package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.match.angleBetweenRad
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forward/inverse round trips for [PinholeProjectionModel.unprojectToCameraRay] against the **real**
 * production model — no reimplementation of the projection anywhere in this file.
 *
 * The orientation flags are the whole reason the inverse belongs on the model rather than in a
 * consumer: [PinholeProjectionModel.axisSwapped] decides which normalized component is multiplied by
 * which focal length, and [PinholeProjectionModel.negateXInput]/[PinholeProjectionModel.negateYInput]
 * decide with which sign. An inverse that ignored any of them would still round-trip perfectly for the
 * default `false, false, false` model — which is why every combination is exercised here, on a model
 * that also has an off-centre principal point and `fx != fy`, so a flag cannot cancel out against a
 * symmetry of the fixture.
 */
class PinholeProjectionModelUnprojectTest {
    @Test
    fun `round trips a centred, square-focal-length model`() {
        assertRoundTripsEveryRay(model(fx = 500.0, fy = 500.0, cx = 320.0, cy = 240.0))
    }

    @Test
    fun `round trips an off-centre principal point`() {
        assertRoundTripsEveryRay(model(fx = 500.0, fy = 500.0, cx = 201.5, cy = 311.25))
    }

    @Test
    fun `round trips when fx and fy differ`() {
        assertRoundTripsEveryRay(model(fx = 493.7, fy = 612.1, cx = 320.0, cy = 240.0))
    }

    @Test
    fun `round trips with axisSwapped`() {
        assertRoundTripsEveryRay(asymmetricModel(axisSwapped = true))
    }

    @Test
    fun `round trips with negateXInput only`() {
        assertRoundTripsEveryRay(asymmetricModel(negateXInput = true))
    }

    @Test
    fun `round trips with negateYInput only`() {
        assertRoundTripsEveryRay(asymmetricModel(negateYInput = true))
    }

    @Test
    fun `round trips with axisSwapped and both negations`() {
        assertRoundTripsEveryRay(asymmetricModel(axisSwapped = true, negateXInput = true, negateYInput = true))
    }

    @Test
    fun `round trips every combination of the three orientation flags`() {
        for (swapped in listOf(false, true)) {
            for (negateX in listOf(false, true)) {
                for (negateY in listOf(false, true)) {
                    assertRoundTripsEveryRay(
                        asymmetricModel(axisSwapped = swapped, negateXInput = negateX, negateYInput = negateY),
                    )
                }
            }
        }
    }

    @Test
    fun `the returned ray is a forward-facing unit vector`() {
        val model = asymmetricModel(axisSwapped = true, negateYInput = true)

        for (point in samplePixels()) {
            val ray = model.unprojectToCameraRay(point)

            val length = sqrt(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z)
            assertEquals(1.0, length, UNIT_TOLERANCE, "ray at $point is not unit length")
            assertTrue(ray.z > 0.0, "ray at $point must face forward; was $ray")
        }
    }

    @Test
    fun `the pixel round trip is the identity as well`() {
        val model = asymmetricModel(axisSwapped = true, negateXInput = true)

        for (point in samplePixels()) {
            val ray = model.unprojectToCameraRay(point)
            val back = model.project(normalizedX = ray.x / ray.z, normalizedY = ray.y / ray.z)

            assertEquals(point.x, back.x, PIXEL_TOLERANCE_PX, "x did not survive pixel -> ray -> pixel")
            assertEquals(point.y, back.y, PIXEL_TOLERANCE_PX, "y did not survive pixel -> ray -> pixel")
        }
    }

    @Test
    fun `ignoring axisSwapped would be caught`() {
        // The guard on this whole file: prove the fixtures can actually tell a flag-aware inverse from a
        // flag-blind one, so a passing round trip is evidence rather than a symmetry of the numbers.
        val swapped = asymmetricModel(axisSwapped = true)
        val notSwapped = asymmetricModel(axisSwapped = false)
        val point = PixelPoint(410.3, 172.9)

        val a = swapped.unprojectToCameraRay(point)
        val b = notSwapped.unprojectToCameraRay(point)

        assertTrue(
            abs(a.x - b.x) > 1e-3 || abs(a.y - b.y) > 1e-3,
            "the fixture cannot distinguish axisSwapped; got $a and $b",
        )
    }

    @Test
    fun `ignoring a negation flag would be caught`() {
        val negated = asymmetricModel(negateXInput = true)
        val plain = asymmetricModel(negateXInput = false)
        val point = PixelPoint(410.3, 172.9)

        val a = negated.unprojectToCameraRay(point)
        val b = plain.unprojectToCameraRay(point)

        assertTrue(
            abs(a.x - b.x) > 1e-3 || abs(a.y - b.y) > 1e-3,
            "the fixture cannot distinguish negateXInput; got $a and $b",
        )
    }

    @Test
    fun `two buffer pixels give an angular separation from the dot product of their rays`() {
        // The property a geometric matcher is actually built on: pixel -> canonical ray -> angle. On a
        // centred, square model the expected value is available in closed form from the pixel offsets,
        // so this is checked against arithmetic the inverse itself never performs.
        val focalLengthPx = 500.0
        val model = model(fx = focalLengthPx, fy = focalLengthPx, cx = 320.0, cy = 240.0)

        // Collinear through the principal point, on opposite sides: the separation is then the sum of
        // each point's own off-axis angle, which is arithmetic the dot product never performs.
        val left = PixelPoint(320.0 - 100.0, 240.0)
        val right = PixelPoint(320.0 + 60.0, 240.0)
        val collinearRad = separationRad(model, left, right)
        assertEquals(atan(100.0 / focalLengthPx) + atan(60.0 / focalLengthPx), collinearRad, 1e-9)

        // Perpendicular offsets from the axis, where that sum would be wrong: each ray has one zero
        // tangent-plane component, so their dot product is 1 and the angle is acos(1 / (|a| * |b|)).
        val above = PixelPoint(320.0, 240.0 - 100.0)
        val beside = PixelPoint(320.0 + 60.0, 240.0)
        val perpendicularRad = separationRad(model, above, beside)
        val u = 100.0 / focalLengthPx
        val v = 60.0 / focalLengthPx
        assertEquals(acos(1.0 / (sqrt(1.0 + u * u) * sqrt(1.0 + v * v))), perpendicularRad, 1e-9)
        assertTrue(
            perpendicularRad < collinearRad,
            "perpendicular offsets must subtend less than collinear ones of the same sizes",
        )

        // And these are real angles, not plate-scale approximations: the on-axis linearisation is
        // visibly off at this offset, which is why a matcher must use rays rather than
        // radiansPerPixelOnAxis.
        val plateScaleGuess = (100.0 + 60.0) / focalLengthPx
        assertTrue(
            abs(plateScaleGuess - collinearRad) > 1e-4,
            "the fixture should expose the plate-scale approximation error; guess=$plateScaleGuess exact=$collinearRad",
        )
    }

    @Test
    fun `returns a unit forward ray for pixels at the extremes of the finite range`() {
        // Squaring the raw normalized components overflows long before Double does: each of these is a
        // perfectly legal PixelPoint, and the unscaled sqrt(x^2 + y^2 + 1) turned them into (0, 0, 0) —
        // finite, so nothing threw, but not a direction either.
        val model = asymmetricModel(axisSwapped = true, negateXInput = true, negateYInput = true)

        for (point in extremePixels()) {
            val ray = model.unprojectToCameraRay(point)

            assertTrue(
                ray.x.isFinite() && ray.y.isFinite() && ray.z.isFinite(),
                "components must be finite at $point; was $ray",
            )
            assertTrue(ray.z > 0.0, "ray at $point must face forward; was $ray")

            val normSquared = ray.x * ray.x + ray.y * ray.y + ray.z * ray.z
            assertEquals(1.0, normSquared, UNIT_TOLERANCE, "ray at $point is not unit length; was $ray")

            // The consumer-side gate on the same promise: angleBetweenRad refuses anything that is not a
            // unit ray, so this is the producer and the consumer agreeing rather than a restatement.
            assertEquals(0.0, angleBetweenRad(ray, ray), "a ray against itself must be zero at $point")
        }
    }

    @Test
    fun `an extreme pixel keeps its direction, not merely unit length`() {
        // Unit length alone is a weak claim — (0, 0, 1) would satisfy it for every input. The direction
        // is what the ray is for, so check the ratios the inverse is defined by: x/z and y/z must come
        // back as the normalized components that produced the pixel. Going through the model's own
        // forward map keeps the orientation flags out of the expectation, so this asserts the ratio
        // rather than re-deriving the algebra.
        val model = asymmetricModel(axisSwapped = true, negateXInput = true)

        for ((normalizedX, normalizedY) in listOf(
            1.0e300 to -3.0e299,
            -7.5e250 to 2.5e250,
            1.0e150 to 4.0e-3,
            2.0e40 to -6.0e40,
        )) {
            val pixel = model.project(normalizedX = normalizedX, normalizedY = normalizedY)
            val ray = model.unprojectToCameraRay(pixel)

            // Relative, because these magnitudes have no meaningful absolute tolerance.
            assertEquals(1.0, (ray.x / ray.z) / normalizedX, 1e-12, "x/z ratio lost for $normalizedX")
            assertEquals(1.0, (ray.y / ray.z) / normalizedY, 1e-12, "y/z ratio lost for $normalizedY")
        }
    }

    @Test
    fun `ordinary pixels are bit-for-bit unaffected by the scale-safe normalization`() {
        // Whenever both normalized components fall inside the unit square — every pixel of a real frame,
        // and every pixel this file samples — the scale factor is exactly 1.0 and the arithmetic reduces
        // to what it was before. Asserted as exact equality against the pre-fix expression, not a
        // tolerance, so a future rewrite that merely stays "close enough" on the ordinary path is caught.
        // The flags are off here so the normalized components are `(px - c) / f` by definition, which is
        // what lets the expected value be written without re-deriving the flag logic.
        val model = model(fx = 493.7, fy = 612.1, cx = 201.5, cy = 311.25)

        for (point in samplePixels()) {
            val normalizedX = (point.x - 201.5) / 493.7
            val normalizedY = (point.y - 311.25) / 612.1
            assertTrue(
                abs(normalizedX) <= 1.0 && abs(normalizedY) <= 1.0,
                "this fixture must stay on the scale-factor-1.0 path to mean anything; was $point",
            )
            val length = sqrt(normalizedX * normalizedX + normalizedY * normalizedY + 1.0)

            val ray = model.unprojectToCameraRay(point)

            assertEquals(normalizedX / length, ray.x, "x drifted at $point")
            assertEquals(normalizedY / length, ray.y, "y drifted at $point")
            assertEquals(1.0 / length, ray.z, "z drifted at $point")
        }
    }

    /**
     * Finite pixels chosen to break the normalization rather than the model: `Double.MAX_VALUE` in each
     * sign, both axes at once, one huge axis beside an ordinary one, and large off-frame coordinates in
     * both signs. Every one of these is accepted by [PixelPoint], and with these focal lengths every one
     * of them squares to `Infinity` in the unscaled form.
     */
    private fun extremePixels(): List<PixelPoint> =
        listOf(
            PixelPoint(Double.MAX_VALUE, 240.0),
            PixelPoint(-Double.MAX_VALUE, 240.0),
            PixelPoint(320.0, Double.MAX_VALUE),
            PixelPoint(Double.MAX_VALUE, Double.MAX_VALUE),
            PixelPoint(-Double.MAX_VALUE, Double.MAX_VALUE),
            PixelPoint(Double.MAX_VALUE, -Double.MAX_VALUE),
            PixelPoint(1.0e200, -4.0e200),
            PixelPoint(-1.0e200, 4.0e200),
            PixelPoint(1.0e300, 97.1),
            PixelPoint(-5.5e175, -2.25e175),
        )

    /** The angle between the rays of two buffer pixels, straight from the dot product of the unit rays. */
    private fun separationRad(
        model: PinholeProjectionModel,
        a: PixelPoint,
        b: PixelPoint,
    ): Double {
        val rayA = model.unprojectToCameraRay(a)
        val rayB = model.unprojectToCameraRay(b)
        return acos((rayA.x * rayB.x + rayA.y * rayB.y + rayA.z * rayB.z).coerceIn(-1.0, 1.0))
    }

    /**
     * Takes rays out of the model's own forward map and requires the inverse to return them: for each
     * sampled direction, `ray -> project -> unproject` must recover the same normalized camera
     * direction.
     */
    private fun assertRoundTripsEveryRay(model: PinholeProjectionModel) {
        for (ray in sampleRays()) {
            val projection = projectBufferOpticalDirection(ray)
            val inFront = projection as CameraDirectionProjection.InFront
            val pixel = model.project(inFront.normalizedX, inFront.normalizedY)

            val recovered = model.unprojectToCameraRay(pixel)

            val expected = normalize(ray)
            assertEquals(expected.x, recovered.x, RAY_TOLERANCE, "x for $ray through $model")
            assertEquals(expected.y, recovered.y, RAY_TOLERANCE, "y for $ray through $model")
            assertEquals(expected.z, recovered.z, RAY_TOLERANCE, "z for $ray through $model")
        }
    }

    /** A model with an off-centre principal point and `fx != fy`, so no flag can hide behind a symmetry. */
    private fun asymmetricModel(
        axisSwapped: Boolean = false,
        negateXInput: Boolean = false,
        negateYInput: Boolean = false,
    ): PinholeProjectionModel =
        model(
            fx = 493.7,
            fy = 612.1,
            cx = 201.5,
            cy = 311.25,
            axisSwapped = axisSwapped,
            negateXInput = negateXInput,
            negateYInput = negateYInput,
        )

    private fun model(
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        axisSwapped: Boolean = false,
        negateXInput: Boolean = false,
        negateYInput: Boolean = false,
    ): PinholeProjectionModel =
        PinholeProjectionModel(
            focalLengthXPx = fx,
            focalLengthYPx = fy,
            principalPointXPx = cx,
            principalPointYPx = cy,
            imageWidthPx = 640.0,
            imageHeightPx = 480.0,
            axisSwapped = axisSwapped,
            negateXInput = negateXInput,
            negateYInput = negateYInput,
        )

    /**
     * Forward-facing directions, deliberately asymmetric in both axes and in both signs: the on-axis ray
     * plus off-axis ones that share no magnitude, so a swapped or negated component cannot coincide with
     * the value it should have had.
     */
    private fun sampleRays(): List<BufferOpticalCameraVector> =
        listOf(
            BufferOpticalCameraVector(0.0, 0.0, 1.0),
            BufferOpticalCameraVector(0.17, -0.31, 1.0),
            BufferOpticalCameraVector(-0.23, 0.09, 1.0),
            BufferOpticalCameraVector(0.41, 0.13, 1.0),
            BufferOpticalCameraVector(-0.36, -0.28, 1.0),
            BufferOpticalCameraVector(0.05, 0.44, 1.0),
            // A ray given at a depth other than 1, to prove the inverse recovers a direction rather than
            // a point: only the normalized direction is meaningful.
            BufferOpticalCameraVector(0.62, -0.48, 3.7),
        )

    private fun samplePixels(): List<PixelPoint> =
        listOf(
            PixelPoint(0.0, 0.0),
            PixelPoint(320.0, 240.0),
            PixelPoint(639.5, 479.5),
            PixelPoint(410.3, 172.9),
            PixelPoint(97.1, 388.4),
            // Outside the image: a candidate that projected just off-frame is still a meaningful ray.
            PixelPoint(-40.0, 520.0),
        )

    private fun normalize(ray: BufferOpticalCameraVector): BufferOpticalCameraVector {
        val length = sqrt(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z)
        return BufferOpticalCameraVector(ray.x / length, ray.y / length, ray.z / length)
    }

    private companion object {
        const val RAY_TOLERANCE = 1e-12
        const val UNIT_TOLERANCE = 1e-12
        const val PIXEL_TOLERANCE_PX = 1e-9
    }
}
