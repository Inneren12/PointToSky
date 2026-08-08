package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.prediction.BufferOpticalCameraVector
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the published ray→angle step: that it agrees with the closed form wherever the closed form is
 * trustworthy, that it keeps its precision where the closed form does not — which is the reason it is
 * `atan2` and not `acos` — that it enforces its unit-ray precondition instead of computing an angle for
 * a vector that has no direction, and that it is literally the same function [AnalysisBufferScale]'s own
 * angular extents are measured with rather than a public copy of it.
 */
class CameraRayAngleTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480
    private val horizontalFovDeg = 66.0
    private val verticalFovDeg = 52.0

    private fun scale(): AnalysisBufferScale =
        AnalysisBufferScale.forGeometry(
            buildTestGeometry(
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
                viewportWidthPx = bufferWidthPx,
                viewportHeightPx = bufferHeightPx,
                intrinsicsResolution = intrinsics(),
            ),
        )

    private fun intrinsics(): CameraIntrinsicsResolution =
        analysisBufferIntrinsics(
            referenceWidthPx = bufferWidthPx,
            referenceHeightPx = bufferHeightPx,
            horizontalFovDeg = horizontalFovDeg,
            verticalFovDeg = verticalFovDeg,
        )

    @Test
    fun `agrees with acos of the dot product for well-separated rays`() {
        val scale = scale()

        // Far enough apart that acos is on the steep part of the cosine and is itself accurate, so it is
        // a fair independent reference here — unlike the sub-pixel case below.
        val pairs =
            listOf(
                PixelPoint(0.0, 0.0) to PixelPoint(640.0, 480.0),
                PixelPoint(17.5, 402.25) to PixelPoint(603.0, 61.5),
                PixelPoint(320.0, 240.0) to PixelPoint(0.0, 240.0),
            )
        for ((first, second) in pairs) {
            val a = scale.cameraRayFor(first)
            val b = scale.cameraRayFor(second)

            assertEquals(acosOfDot(a, b), angleBetweenRad(a, b), 1e-12, "for $first -> $second")
        }
    }

    @Test
    fun `matches hand-built unit vectors of known separation across the whole range`() {
        val z = BufferOpticalCameraVector(0.0, 0.0, 1.0)

        // Collinear, perpendicular, and antiparallel — the three angles that need no closed form at all.
        assertEquals(0.0, angleBetweenRad(z, z))
        assertEquals(PI / 2.0, angleBetweenRad(z, BufferOpticalCameraVector(1.0, 0.0, 0.0)), 1e-15)
        assertEquals(PI / 2.0, angleBetweenRad(z, BufferOpticalCameraVector(0.0, 1.0, 0.0)), 1e-15)
        assertEquals(PI, angleBetweenRad(z, BufferOpticalCameraVector(0.0, 0.0, -1.0)), 1e-15)

        // ...and a sweep of constructed separations, where the construction *is* the reference. The
        // upper end is beyond anything two rays from cameraRayFor can reach (both are forward-facing),
        // and is here because the documented range is [0, π], not [0, π/2].
        for (expectedRad in listOf(1e-3, 0.05, 0.5, 1.0, PI / 2.0, 2.5, 3.0)) {
            val tilted = BufferOpticalCameraVector(sin(expectedRad), 0.0, cos(expectedRad))

            assertEquals(expectedRad, angleBetweenRad(z, tilted), 1e-14, "at $expectedRad rad")
        }
    }

    @Test
    fun `keeps full precision a fraction of a pixel apart, where acos of the dot product does not`() {
        val scale = scale()
        val fx = scale.focalLengthXPx

        // A sixty-fourth of a pixel along the optical axis: the separation regime a star matcher lives
        // in. With the axis centred and no orientation flags, the true angle is exactly atan(dx / fx).
        // A power of two so that `principalPointXPx + dxPx` is exact and the pixel offset the model
        // recovers is the offset asked for — otherwise the cancellation in that subtraction, not the
        // angle formula, would be what the numbers below measure.
        val dxPx = 1.0 / 64.0
        val expectedRad = atan(dxPx / fx)
        val a = scale.cameraRayFor(PixelPoint(scale.principalPointXPx, scale.principalPointYPx))
        val b = scale.cameraRayFor(PixelPoint(scale.principalPointXPx + dxPx, scale.principalPointYPx))

        val helperError = abs(angleBetweenRad(a, b) - expectedRad) / expectedRad
        val acosError = abs(acosOfDot(a, b) - expectedRad) / expectedRad

        assertTrue(
            helperError < 1e-13,
            "the atan2 form should match the analytic reference here; relative error was $helperError",
        )
        assertTrue(
            acosError > 1e-9,
            "acos of the dot product should already be visibly wrong at $dxPx px; " +
                "relative error was $acosError",
        )
        assertTrue(
            acosError > 1e4 * maxOf(helperError, 1e-16),
            "the gap is the whole reason this function is atan2; helper=$helperError acos=$acosError",
        )
    }

    @Test
    fun `stays accurate at separations where acos collapses to zero entirely`() {
        // Ten nanoradians: cos of it rounds to exactly 1.0 in double, so acos(a . b) returns 0 and every
        // digit of the answer is gone. This is the failure the atan2 form exists to avoid.
        val expectedRad = 1e-8
        val a = BufferOpticalCameraVector(0.0, 0.0, 1.0)
        val b = BufferOpticalCameraVector(sin(expectedRad), 0.0, cos(expectedRad))

        assertEquals(expectedRad, angleBetweenRad(a, b), expectedRad * 1e-9)
        assertEquals(0.0, acosOfDot(a, b), "the fixture must actually be past acos's resolution")
    }

    @Test
    fun `is symmetric, zero against the same unit ray, and bounded to the documented range`() {
        val scale = scale()
        val rays =
            listOf(
                scale.opticalAxisRay,
                scale.cameraRayFor(PixelPoint(0.0, 0.0)),
                scale.cameraRayFor(PixelPoint(640.0, 0.0)),
                scale.cameraRayFor(PixelPoint(417.3, 118.9)),
                BufferOpticalCameraVector(0.0, 0.0, -1.0),
                BufferOpticalCameraVector(-1.0 / sqrt(3.0), 1.0 / sqrt(3.0), -1.0 / sqrt(3.0)),
            )

        for (a in rays) {
            assertEquals(0.0, angleBetweenRad(a, a), "a ray against itself must be exactly zero")
            for (b in rays) {
                val angle = angleBetweenRad(a, b)

                assertEquals(angle, angleBetweenRad(b, a), "must not depend on argument order")
                assertTrue(angle in 0.0..PI, "$angle rad is outside [0, PI]")
            }
        }
    }

    @Test
    fun `is the same function the angular extents are already measured with`() {
        val scale = scale()

        // Bit-exact, not within a tolerance: an equality that survives a second implementation of the
        // same formula is not evidence of anything. These must be the same call.
        assertEquals(
            angleBetweenRad(scale.opticalAxisRay, scale.cameraRayFor(PixelPoint(0.0, scale.principalPointYPx))),
            scale.leftAngularExtentRad,
        )
        assertEquals(
            angleBetweenRad(
                scale.opticalAxisRay,
                scale.cameraRayFor(PixelPoint(scale.imageWidthPx, scale.principalPointYPx)),
            ),
            scale.rightAngularExtentRad,
        )
        assertEquals(
            listOf(
                PixelPoint(0.0, 0.0),
                PixelPoint(scale.imageWidthPx, 0.0),
                PixelPoint(0.0, scale.imageHeightPx),
                PixelPoint(scale.imageWidthPx, scale.imageHeightPx),
            ).maxOf { corner -> angleBetweenRad(scale.opticalAxisRay, scale.cameraRayFor(corner)) },
            scale.enclosingConeRadiusRad,
        )
    }

    @Test
    fun `rejects the zero vector rather than reporting an angle for a direction that does not exist`() {
        val valid = scale().opticalAxisRay
        val zero = BufferOpticalCameraVector(0.0, 0.0, 0.0)

        // atan2(0, 0) is 0.0, so before the precondition was enforced this returned a perfectly
        // plausible "the rays are parallel" for a vector that points nowhere at all.
        assertFailsWith<IllegalArgumentException> { angleBetweenRad(zero, valid) }
        val fromSecondArgument = assertFailsWith<IllegalArgumentException> { angleBetweenRad(valid, zero) }
        assertFailsWith<IllegalArgumentException> { angleBetweenRad(zero, zero) }

        assertTrue(
            fromSecondArgument.message.orEmpty().contains("argument b"),
            "the failure must name which argument was malformed; was ${fromSecondArgument.message}",
        )
    }

    @Test
    fun `rejects finite vectors that are measurably not unit length`() {
        val valid = scale().opticalAxisRay

        // Right direction, wrong length: a scaled ray still has a well-defined direction, so this one
        // would have produced the *correct* angle. It is refused anyway — along the sanctioned path
        // every ray is already unit, so a scaled one means the geometry was assembled wrong upstream,
        // and quietly normalizing it would turn that bug into a plausible number.
        for (nonUnit in listOf(
            BufferOpticalCameraVector(2.0, 0.0, 0.0),
            BufferOpticalCameraVector(0.5, 0.0, 0.0),
            BufferOpticalCameraVector(0.0, 0.0, 1.001),
            BufferOpticalCameraVector(1.0, 1.0, 1.0),
        )) {
            assertFailsWith<IllegalArgumentException>("expected $nonUnit to be refused") {
                angleBetweenRad(nonUnit, valid)
            }
            assertFailsWith<IllegalArgumentException>("expected $nonUnit to be refused") {
                angleBetweenRad(valid, nonUnit)
            }
        }
    }

    @Test
    fun `rejects finite vectors whose squared norm overflows or underflows`() {
        val valid = scale().opticalAxisRay

        // Every component here is finite, so BufferOpticalCameraVector's own constructor check passes.
        // The squares are what leave the representable range — to Infinity for the large ones and to
        // exactly 0.0 for the small one — which would put Infinity or a fabricated zero into atan2.
        for (extreme in listOf(
            BufferOpticalCameraVector(1e300, 1e300, 0.0),
            BufferOpticalCameraVector(1e200, 0.0, 0.0),
            BufferOpticalCameraVector(Double.MAX_VALUE, 0.0, 0.0),
            BufferOpticalCameraVector(1e-300, 0.0, 0.0),
            BufferOpticalCameraVector(Double.MIN_VALUE, 0.0, 0.0),
        )) {
            assertFailsWith<IllegalArgumentException>("expected $extreme to be refused") {
                angleBetweenRad(extreme, valid)
            }
            assertFailsWith<IllegalArgumentException>("expected $extreme to be refused") {
                angleBetweenRad(valid, extreme)
            }
        }
    }

    @Test
    fun `tolerates floating-point noise around unit length but not a real scaling`() {
        val axis = scale().opticalAxisRay

        // The tolerance has to absorb the last few bits of a normalization without absorbing anything
        // that could be a genuinely mis-scaled vector. These two bracket that, three orders either side
        // of the 1e-6 bound, so the constant cannot be loosened or tightened by much unnoticed.
        val noisy = BufferOpticalCameraVector(axis.x, axis.y, axis.z * (1.0 + 1e-9))
        val scaled = BufferOpticalCameraVector(axis.x, axis.y, axis.z * (1.0 + 1e-3))

        assertEquals(0.0, angleBetweenRad(noisy, axis), 1e-9)
        assertFailsWith<IllegalArgumentException> { angleBetweenRad(scaled, axis) }
    }

    @Test
    fun `accepts every ray cameraRayFor produces, on and off axis and through the orientation flags`() {
        // The precondition must never fire on the sanctioned path. This also measures the margin the
        // 1e-6 tolerance actually has: unprojectToCameraRay lands within a couple of ulps of unit,
        // which is the number UNIT_RAY_NORM_SQUARED_TOLERANCE's KDoc is chosen against.
        var worstNormSquaredError = 0.0
        for (scale in listOf(scale(), offCentreScale(), offCentreScale(axisSwapped = true, negateXInput = true))) {
            val points =
                listOf(
                    PixelPoint(0.0, 0.0),
                    PixelPoint(scale.imageWidthPx, 0.0),
                    PixelPoint(0.0, scale.imageHeightPx),
                    PixelPoint(scale.imageWidthPx, scale.imageHeightPx),
                    PixelPoint(scale.principalPointXPx, scale.principalPointYPx),
                    PixelPoint(417.3, 118.9),
                    // Outside the frame: cameraRayFor never clamps, so a matcher can legitimately hold
                    // one of these, and the precondition must not be what rejects it.
                    PixelPoint(-250.0, -180.0),
                    PixelPoint(scale.imageWidthPx + 900.0, scale.imageHeightPx + 700.0),
                )
            for (point in points) {
                val ray = scale.cameraRayFor(point)
                worstNormSquaredError =
                    maxOf(worstNormSquaredError, abs(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z - 1.0))

                // Would throw if the precondition were too tight for its own canonical producer.
                val angle = angleBetweenRad(scale.opticalAxisRay, ray)
                assertTrue(angle in 0.0..PI, "$angle rad is outside [0, PI] for $point")
            }
        }

        assertTrue(
            worstNormSquaredError <= 4.5e-16,
            "cameraRayFor should land within a couple of ulps of unit; worst was $worstNormSquaredError",
        )
    }

    @Test
    fun `the angular extents and enclosing cone still need no special casing`() {
        // The extents and the cone feed cameraRayFor output straight into angleBetweenRad. If the
        // precondition were wrong for that path these would throw rather than fail an assertion.
        for (scale in listOf(scale(), offCentreScale(), offCentreScale(negateYInput = true))) {
            assertTrue(scale.horizontalFieldOfViewRad > 0.0)
            assertTrue(scale.verticalFieldOfViewRad > 0.0)
            assertTrue(scale.enclosingConeRadiusRad > 0.0)
        }
    }

    /** The naive form a consumer would otherwise write, kept here as the thing being measured against. */
    private fun acosOfDot(
        a: BufferOpticalCameraVector,
        b: BufferOpticalCameraVector,
    ): Double = acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0))

    /**
     * A calibrated scale whose optical axis is deliberately nowhere near the raster centre, optionally
     * with the orientation flags set — the fixture shape `AnalysisBufferScaleTest` already uses, so the
     * rays fed to the precondition above are the awkward ones rather than the symmetric default.
     */
    private fun offCentreScale(
        axisSwapped: Boolean = false,
        negateXInput: Boolean = false,
        negateYInput: Boolean = false,
    ): AnalysisBufferScale =
        AnalysisBufferScale.forGeometry(
            buildTestGeometry(
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
                viewportWidthPx = bufferWidthPx,
                viewportHeightPx = bufferHeightPx,
                intrinsicsResolution =
                    CameraIntrinsicsResolution.Resolved(
                        CameraIntrinsics(
                            horizontalFovDeg = horizontalFovDeg,
                            verticalFovDeg = verticalFovDeg,
                            focalLengthMm = 4.0,
                            sensorWidthMm = 5.0,
                            sensorHeightMm = 4.0,
                            principalPointXPx = 214.0,
                            principalPointYPx = 301.0,
                            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                            reference = CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx),
                            axisSwapped = axisSwapped,
                            negateXInput = negateXInput,
                            negateYInput = negateYInput,
                        ),
                    ),
            ),
        )
}
