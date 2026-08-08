package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
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
import kotlin.test.assertTrue

/**
 * Pins the published ray→angle step: that it agrees with the closed form wherever the closed form is
 * trustworthy, that it keeps its precision where the closed form does not — which is the reason it is
 * `atan2` and not `acos` — and that it is literally the same function [AnalysisBufferScale]'s own
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

        assertTrue(helperError < 1e-13, "atan2 form should be exact here; relative error was $helperError")
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
    fun `is symmetric, zero against itself, and bounded to the documented range`() {
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

    /** The naive form a consumer would otherwise write, kept here as the thing being measured against. */
    private fun acosOfDot(
        a: BufferOpticalCameraVector,
        b: BufferOpticalCameraVector,
    ): Double = acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0))
}
