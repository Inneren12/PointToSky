package dev.pointtosky.core.astro.projection.camera

import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val EPS = 1e-6

/**
 * Pure JVM tests for [SensorToBufferTransform], [ActiveArraySensorCropRegion], and
 * [mapActiveArrayIntrinsicsToAnalysisBuffer] (CAM-2c §4/§5/§8).
 */
class AnalysisBufferIntrinsicsMappingTest {
    // --- SensorToBufferTransform ---

    @Test
    fun `SensorToBufferTransform rejects non-finite or non-positive scale`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                SensorToBufferTransform(scaleX = bad, scaleY = 1.0, translateXPx = 0.0, translateYPx = 0.0)
            }
            assertFailsWith<IllegalArgumentException> {
                SensorToBufferTransform(scaleX = 1.0, scaleY = bad, translateXPx = 0.0, translateYPx = 0.0)
            }
        }
    }

    @Test
    fun `SensorToBufferTransform rejects non-finite translate`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                SensorToBufferTransform(scaleX = 1.0, scaleY = 1.0, translateXPx = bad, translateYPx = 0.0)
            }
        }
    }

    @Test
    fun `toActiveArraySensorCropRegion inverts a centered-crop-then-scale transform`() {
        // Active array region (1008,756)-(3024,2268) - a centered 2016x1512 crop of a 4032x3024
        // active array - mapped onto a 1008x756 buffer (scale 0.5).
        val transform = SensorToBufferTransform(scaleX = 0.5, scaleY = 0.5, translateXPx = -504.0, translateYPx = -378.0)

        val region = transform.toActiveArraySensorCropRegion(bufferWidthPx = 1008, bufferHeightPx = 756)

        assertEquals(1008.0, region.leftPx, EPS)
        assertEquals(756.0, region.topPx, EPS)
        assertEquals(3024.0, region.rightPx, EPS)
        assertEquals(2268.0, region.bottomPx, EPS)
    }

    @Test
    fun `toActiveArraySensorCropRegion rejects non-positive buffer dimensions`() {
        val transform = SensorToBufferTransform(scaleX = 1.0, scaleY = 1.0, translateXPx = 0.0, translateYPx = 0.0)
        assertFailsWith<IllegalArgumentException> { transform.toActiveArraySensorCropRegion(0, 100) }
        assertFailsWith<IllegalArgumentException> { transform.toActiveArraySensorCropRegion(100, -1) }
    }

    // --- ActiveArraySensorCropRegion ---

    @Test
    fun `ActiveArraySensorCropRegion rejects a degenerate or unordered region`() {
        assertFailsWith<IllegalArgumentException> { ActiveArraySensorCropRegion(100.0, 0.0, 100.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { ActiveArraySensorCropRegion(0.0, 100.0, 100.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { ActiveArraySensorCropRegion(100.0, 0.0, 0.0, 100.0) }
    }

    @Test
    fun `ActiveArraySensorCropRegion computes width and height`() {
        val region = ActiveArraySensorCropRegion(100.0, 200.0, 500.0, 800.0)
        assertEquals(400.0, region.widthPx)
        assertEquals(600.0, region.heightPx)
    }

    // --- mapActiveArrayIntrinsicsToAnalysisBuffer ---

    @Test
    fun `no crop, same buffer size is the identity mapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 960.0, cyPx = 540.0, widthPx = 1920, heightPx = 1080)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 1920.0, 1080.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 1920, bufferHeightPx = 1080)

        assertEquals(1000.0, result.fxPx, EPS)
        assertEquals(1000.0, result.fyPx, EPS)
        assertEquals(960.0, result.cxPx, EPS)
        assertEquals(540.0, result.cyPx, EPS)
    }

    @Test
    fun `centered crop keeps the principal point centered when buffer size matches the crop`() {
        val active = ActiveArrayIntrinsics(fxPx = 2000.0, fyPx = 2000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        val crop = ActiveArraySensorCropRegion(1008.0, 756.0, 3024.0, 2268.0) // centered 2016x1512 region

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 2016, bufferHeightPx = 1512)

        assertEquals(2000.0, result.fxPx, EPS) // sx == 1, no scaling
        assertEquals(2000.0, result.fyPx, EPS)
        assertEquals(1008.0, result.cxPx, EPS) // exactly bufferWidth/2 - still centered
        assertEquals(756.0, result.cyPx, EPS) // exactly bufferHeight/2
    }

    @Test
    fun `asymmetric crop translates the principal point off-center`() {
        val active = ActiveArrayIntrinsics(fxPx = 2000.0, fyPx = 2000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        // Same 2016x1512 size as the centered-crop case above, but shifted toward the top-left.
        val crop = ActiveArraySensorCropRegion(500.0, 300.0, 2516.0, 1812.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 2016, bufferHeightPx = 1512)

        assertEquals(1516.0, result.cxPx, EPS) // 2016 - 500, sx == 1
        assertEquals(1212.0, result.cyPx, EPS) // 1512 - 300, sy == 1
        // Off-center by exactly the crop's offset from centered - not bufferWidth/2 (1008).
        assertEquals(508.0, result.cxPx - 1008.0, EPS)
    }

    @Test
    fun `non-uniform scale applies distinct sx and sy to fx, fy, cx, cy`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 1000.0, 1000.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 500, bufferHeightPx = 250)

        assertEquals(500.0, result.fxPx, EPS) // sx = 0.5
        assertEquals(250.0, result.fyPx, EPS) // sy = 0.25
        assertEquals(250.0, result.cxPx, EPS)
        assertEquals(125.0, result.cyPx, EPS)
    }

    @Test
    fun `uniform focal scaling alone (no crop offset) scales fx and fy by the same factor`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 1000.0, 1000.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 250, bufferHeightPx = 250)

        assertEquals(250.0, result.fxPx, EPS)
        assertEquals(250.0, result.fyPx, EPS)
        assertEquals(125.0, result.cxPx, EPS)
        assertEquals(125.0, result.cyPx, EPS)
    }

    @Test
    fun `portrait active array and portrait buffer dimensions are handled without swapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 1512.0, cyPx = 2016.0, widthPx = 3024, heightPx = 4032)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 3024.0, 4032.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 756, bufferHeightPx = 1008)

        assertEquals(250.0, result.fxPx, EPS) // sx = sy = 0.25
        assertEquals(250.0, result.fyPx, EPS)
        assertEquals(378.0, result.cxPx, EPS)
        assertEquals(504.0, result.cyPx, EPS)
        assertEquals(756, result.widthPx)
        assertEquals(1008, result.heightPx)
    }

    @Test
    fun `landscape active array and landscape buffer dimensions are handled without swapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 4032.0, 3024.0)

        val result = mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 1008, bufferHeightPx = 756)

        assertEquals(1008, result.widthPx)
        assertEquals(756, result.heightPx)
        assertEquals(504.0, result.cxPx, EPS)
        assertEquals(378.0, result.cyPx, EPS)
    }

    @Test
    fun `invalid crop - region extending past the active array - is rejected`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val cropPastRight = ActiveArraySensorCropRegion(0.0, 0.0, 1500.0, 1000.0)
        val cropPastBottom = ActiveArraySensorCropRegion(0.0, 0.0, 1000.0, 1500.0)
        val cropNegativeLeft = ActiveArraySensorCropRegion(-100.0, 0.0, 900.0, 1000.0)

        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, cropPastRight, bufferWidthPx = 500, bufferHeightPx = 500)
        }
        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, cropPastBottom, bufferWidthPx = 500, bufferHeightPx = 500)
        }
        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, cropNegativeLeft, bufferWidthPx = 500, bufferHeightPx = 500)
        }
    }

    @Test
    fun `invalid crop - zero or negative buffer dimensions - is rejected`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 1000.0, 1000.0)

        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 0, bufferHeightPx = 500)
        }
        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 500, bufferHeightPx = -1)
        }
    }

    @Test
    fun `source geometry (active intrinsics and crop region) remains unchanged after mapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val crop = ActiveArraySensorCropRegion(0.0, 0.0, 1000.0, 1000.0)
        val activeBefore = active.copy()
        val cropBefore = crop.copy()

        mapActiveArrayIntrinsicsToAnalysisBuffer(active, crop, bufferWidthPx = 500, bufferHeightPx = 500)

        assertEquals(activeBefore, active)
        assertEquals(cropBefore, crop)
    }

    // --- toCameraIntrinsics ---

    @Test
    fun `toCameraIntrinsics publishes CAMERA_CHARACTERISTICS source and AnalysisBuffer reference`() {
        val values = AnalysisBufferIntrinsicsValues(fxPx = 500.0, fyPx = 500.0, cxPx = 320.0, cyPx = 240.0, widthPx = 640, heightPx = 480)

        val intrinsics =
            values.toCameraIntrinsics(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                quality = CameraIntrinsicsQuality.CALIBRATED,
            )

        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, intrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(640, 480), intrinsics.reference)
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, intrinsics.quality)
        assertEquals(320.0, intrinsics.principalPointXPx)
        assertEquals(240.0, intrinsics.principalPointYPx)
        assertEquals(4.25, intrinsics.focalLengthMm)
    }

    @Test
    fun `toCameraIntrinsics FOV round-trips back to the exact original fx and fy`() {
        val values = AnalysisBufferIntrinsicsValues(fxPx = 533.333, fyPx = 711.111, cxPx = 320.0, cyPx = 240.0, widthPx = 640, heightPx = 480)

        val intrinsics =
            values.toCameraIntrinsics(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            )

        val recoveredFx = values.widthPx / (2.0 * tan(Math.toRadians(intrinsics.horizontalFovDeg) / 2.0))
        val recoveredFy = values.heightPx / (2.0 * tan(Math.toRadians(intrinsics.verticalFovDeg) / 2.0))
        assertEquals(values.fxPx, recoveredFx, EPS)
        assertEquals(values.fyPx, recoveredFy, EPS)
    }
}
