package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.MatrixIntrinsicsMappingResult
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix
import dev.pointtosky.core.astro.projection.camera.toCameraIntrinsics
import java.time.Instant
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CAM-2c §8/§10: a synthetic, "Pixel-9-like" fixture (a hand-chosen, round-number active
 * array/sensor/focal-length combination in the ballpark of a real phone main camera — not a claim
 * about the real device's exact calibration numbers, which this sandboxed environment has no way to
 * measure) proving:
 *  - the centre ray remains centred, under both the calibrated and legacy models, and through the
 *    full [projectStars] pipeline;
 *  - off-axis rays preserve the expected (tangent, not linear) angular-to-pixel scale under the
 *    calibrated model;
 *  - the legacy fixed-FOV fallback produces a *measurably different* off-axis pixel position than
 *    the calibrated model for the exact same ray and buffer — the class of bug CAM-2c fixes (real
 *    device evidence: off-axis predicted markers diverge systematically from legacy stars, while the
 *    centre marker aligns).
 */
class CalibratedAnalysisBufferProjectionTest {
    private val eps = 1e-6
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    /**
     * A Pixel-9-like main camera: 4032x3024 active array (4:3), a 6.4x4.8mm physical sensor (the
     * same 4:3 aspect), 3.6mm focal length, no sensor crop — mapped onto a 640x480 analysis buffer
     * (also 4:3, so the crop/scale step is a pure uniform downscale with no principal-point shift).
     */
    private val calibratedIntrinsics =
        run {
            val active =
                activeArrayIntrinsicsFromFocalLength(
                    focalLengthMm = 3.6,
                    sensorWidthMm = 6.4,
                    sensorHeightMm = 4.8,
                    activeArrayWidthPx = 4032,
                    activeArrayHeightPx = 3024,
                )
            val noCropMatrix =
                SensorToBufferMatrix3(
                    m00 = bufferWidthPx / 4032.0, m01 = 0.0, m02 = 0.0,
                    m10 = 0.0, m11 = bufferHeightPx / 3024.0, m12 = 0.0,
                    m20 = 0.0, m21 = 0.0, m22 = 1.0,
                )
            val mapping = mapActiveArrayIntrinsicsThroughMatrix(active, noCropMatrix, bufferWidthPx, bufferHeightPx)
            val bufferValues = (mapping as MatrixIntrinsicsMappingResult.Mapped).values
            bufferValues.toCameraIntrinsics(
                focalLengthMm = 3.6,
                sensorWidthMm = 6.4,
                sensorHeightMm = 4.8,
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            )
        }

    private val legacyIntrinsics = legacyFallbackCameraIntrinsics(bufferWidthPx, bufferHeightPx)

    private fun pinholeFor(intrinsics: CameraIntrinsics): PinholeProjectionModel {
        val fx = bufferWidthPx / (2.0 * tan(Math.toRadians(intrinsics.horizontalFovDeg) / 2.0))
        val fy = bufferHeightPx / (2.0 * tan(Math.toRadians(intrinsics.verticalFovDeg) / 2.0))
        return PinholeProjectionModel(
            focalLengthXPx = fx,
            focalLengthYPx = fy,
            principalPointXPx = intrinsics.principalPointXPx ?: (bufferWidthPx / 2.0),
            principalPointYPx = intrinsics.principalPointYPx ?: (bufferHeightPx / 2.0),
            imageWidthPx = bufferWidthPx.toDouble(),
            imageHeightPx = bufferHeightPx.toDouble(),
        )
    }

    /** Camera-plane normalized coordinates for a ray [azimuthOffsetDeg] east of a north-facing, upright camera. */
    private fun rayAt(azimuthOffsetDeg: Double): CameraDirectionProjection.InFront {
        val world = localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(azimuthOffsetDeg), altitudeRad = 0.0)
        val device = worldToDeviceVector(NORTH_UPRIGHT_ROTATION_MATRIX, world)
        val displayOptical = DeviceToOpticalCameraTransform.apply(device)
        val bufferOptical = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, rotationDegrees = 0)
        return assertIs<CameraDirectionProjection.InFront>(projectBufferOpticalDirection(bufferOptical))
    }

    @Test
    fun `centre ray remains centred under both the calibrated and legacy models`() {
        val centre = rayAt(azimuthOffsetDeg = 0.0)

        val calibratedPoint = pinholeFor(calibratedIntrinsics).project(centre.normalizedX, centre.normalizedY)
        val legacyPoint = pinholeFor(legacyIntrinsics).project(centre.normalizedX, centre.normalizedY)

        assertEquals(bufferWidthPx / 2.0, calibratedPoint.x, eps)
        assertEquals(bufferHeightPx / 2.0, calibratedPoint.y, eps)
        assertEquals(bufferWidthPx / 2.0, legacyPoint.x, eps)
        assertEquals(bufferHeightPx / 2.0, legacyPoint.y, eps)
    }

    @Test
    fun `off-axis rays preserve the expected tangent angular-to-pixel scale under the calibrated model`() {
        val pinhole = pinholeFor(calibratedIntrinsics)
        val near = rayAt(azimuthOffsetDeg = 5.0)
        val far = rayAt(azimuthOffsetDeg = 10.0)

        val nearOffsetPx = pinhole.project(near.normalizedX, near.normalizedY).x - bufferWidthPx / 2.0
        val farOffsetPx = pinhole.project(far.normalizedX, far.normalizedY).x - bufferWidthPx / 2.0

        assertTrue(nearOffsetPx > 0.0, "5 deg east of boresight must be right of centre, was $nearOffsetPx")
        // A pinhole model is fx*tan(angle) - convex, not linear - so doubling the angle slightly more
        // than doubles the pixel offset for these small angles. Bounds are loose around the true
        // ~2.016x ratio (tan(10deg)/tan(5deg)) so this asserts the right shape, not a brittle exact
        // float match.
        assertTrue(farOffsetPx > nearOffsetPx * 1.5, "10 deg offset ($farOffsetPx px) must be well past 1.5x the 5 deg offset ($nearOffsetPx px)")
        assertTrue(farOffsetPx < nearOffsetPx * 2.5, "10 deg offset ($farOffsetPx px) must stay close to the tangent-scaled 5 deg offset ($nearOffsetPx px)")
        // Direct check against the exact tangent formula the pinhole model is defined by.
        val expectedFarOffsetPx = pinholeFor(calibratedIntrinsics).focalLengthXPx * tan(Math.toRadians(10.0))
        assertEquals(expectedFarOffsetPx, farOffsetPx, eps)
    }

    @Test
    fun `the legacy fallback produces a measurably different off-axis pixel position than the calibrated model`() {
        val offAxis = rayAt(azimuthOffsetDeg = 10.0)

        val calibratedOffsetPx = pinholeFor(calibratedIntrinsics).project(offAxis.normalizedX, offAxis.normalizedY).x - bufferWidthPx / 2.0
        val legacyOffsetPx = pinholeFor(legacyIntrinsics).project(offAxis.normalizedX, offAxis.normalizedY).x - bufferWidthPx / 2.0

        assertTrue(
            abs(calibratedOffsetPx - legacyOffsetPx) > 10.0,
            "calibrated ($calibratedOffsetPx px) and legacy ($legacyOffsetPx px) off-axis positions for the same ray " +
                "must differ by more than 10px - this is the exact class of divergence CAM-2c fixes",
        )
    }

    // --- Full pipeline: CAM-2a must accept the new calibrated reference, not just the raw pinhole math ---

    private val instant: Instant = Instant.parse("2024-07-07T07:00:00Z")
    private val lonDeg = -10.0

    /** Star exactly on the north horizon - NORTH_UPRIGHT_ROTATION_MATRIX's forward axis. */
    private fun forwardAxisStar() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

    @Test
    fun `projectStars accepts the calibrated CAMERA_CHARACTERISTICS AnalysisBuffer reference and projects the forward-axis star to centre`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
                viewportWidthPx = bufferWidthPx,
                viewportHeightPx = bufferHeightPx,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = CameraIntrinsicsResolution.Resolved(calibratedIntrinsics),
            )

        val result = assertIs<StarPredictionBatchResult.Ready>(projectStars(listOf(star), context, geometry))
        val projection = result.projections.single()

        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, projection.classification)
        val displayPoint = assertNotNull(projection.displayPoint)
        assertEquals(bufferWidthPx / 2.0, displayPoint.x, eps)
        assertEquals(bufferHeightPx / 2.0, displayPoint.y, eps)
    }

    @Test
    fun `the calibrated resolution carries CAMERA_CHARACTERISTICS source, AnalysisBuffer reference, and a non-null quality`() {
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, calibratedIntrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx), calibratedIntrinsics.reference)
        assertNotNull(calibratedIntrinsics.quality)
    }

    // --- CAM-2c fix §7: at least four off-axis rays land at the exact expected buffer position ---

    @Test
    fun `four distinct off-axis rays all land at the exact tangent-formula-predicted buffer position`() {
        val pinhole = pinholeFor(calibratedIntrinsics)
        // East (positive azimuth offset) and west (negative) by two different amounts each - four
        // genuinely distinct off-axis rays, all checked against the exact closed-form pinhole formula
        // (fx*tan(angle)), not merely "roughly bigger" bounds.
        listOf(-10.0, -5.0, 5.0, 10.0).forEach { azimuthOffsetDeg ->
            val ray = rayAt(azimuthOffsetDeg)
            val point = pinhole.project(ray.normalizedX, ray.normalizedY)
            val expectedOffsetPx = pinhole.focalLengthXPx * tan(Math.toRadians(azimuthOffsetDeg))
            assertEquals(bufferWidthPx / 2.0 + expectedOffsetPx, point.x, eps, "azimuthOffsetDeg=$azimuthOffsetDeg")
            assertEquals(bufferHeightPx / 2.0, point.y, eps, "azimuthOffsetDeg=$azimuthOffsetDeg (no vertical offset expected)")
        }
    }

    // --- CAM-2c fix §7: rotationDegrees 0/90/180/270 all preserve the same physical scene mapping ---

    @Test
    fun `rotationDegrees 0, 90, 180, and 270 all project the forward-axis star to the viewport centre`() {
        val (star, context) = forwardAxisStar()
        // The camera's own analyzed buffer is never swapped for rotationDegrees (CameraFrameMetadata's
        // own contract), but the viewport (physical screen) genuinely does swap between landscape and
        // portrait - this is exactly the real CameraPreview.kt binding shape.
        listOf(0, 90, 180, 270).forEach { rotationDegrees ->
            val (viewportWidthPx, viewportHeightPx) =
                if (rotationDegrees == 90 || rotationDegrees == 270) bufferHeightPx to bufferWidthPx else bufferWidthPx to bufferHeightPx
            val geometry =
                buildTestGeometry(
                    bufferWidthPx = bufferWidthPx,
                    bufferHeightPx = bufferHeightPx,
                    rotationDegrees = rotationDegrees,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                    intrinsicsResolution = CameraIntrinsicsResolution.Resolved(calibratedIntrinsics),
                )

            val result = assertIs<StarPredictionBatchResult.Ready>(projectStars(listOf(star), context, geometry))
            val projection = result.projections.single()

            assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, projection.classification, "rotationDegrees=$rotationDegrees")
            val displayPoint = assertNotNull(projection.displayPoint, "rotationDegrees=$rotationDegrees")
            assertEquals(viewportWidthPx / 2.0, displayPoint.x, eps, "rotationDegrees=$rotationDegrees")
            assertEquals(viewportHeightPx / 2.0, displayPoint.y, eps, "rotationDegrees=$rotationDegrees")
        }
    }
}
