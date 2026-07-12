package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [resolveCameraIntrinsics] and [selectFocalLengthMm] (CAM-1b). Uses a fake
 * [CameraCharacteristicsSource] so resolution logic is exercised without a real camera, CameraX
 * binding, or Robolectric — matching the isolation the Camera2 adapter
 * ([Camera2CharacteristicsSource]) exists to provide.
 */
class CameraIntrinsicsResolverTest {
    private val eps = 1e-6

    private fun sourceOf(
        focalLengthsMm: FloatArray? = floatArrayOf(4.25f),
        sensorWidthMm: Float? = 5.76f,
        sensorHeightMm: Float? = 4.29f,
    ) = CameraCharacteristicsSource {
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = focalLengthsMm,
            sensorPhysicalWidthMm = sensorWidthMm,
            sensorPhysicalHeightMm = sensorHeightMm,
        )
    }

    @Test
    fun `valid focal length and sensor size resolve real calibrated intrinsics`() {
        val result = resolveCameraIntrinsics(sourceOf(), imageWidthPx = null, imageHeightPx = null)

        assertNull(result.fallbackReason)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, result.intrinsics.source)
        // Compare against the float->double widening of the original Float inputs, not the Double
        // decimal literal: 5.76f/4.29f are not exactly representable in float32, so widening them
        // to Double does not yield the same bits as parsing "5.76"/"4.29" as a Double literal.
        assertEquals(4.25f.toDouble(), result.intrinsics.focalLengthMm)
        assertEquals(5.76f.toDouble(), result.intrinsics.sensorWidthMm)
        assertEquals(4.29f.toDouble(), result.intrinsics.sensorHeightMm)

        val expectedHorizontal = Math.toDegrees(2.0 * kotlin.math.atan(5.76f.toDouble() / (2.0 * 4.25f.toDouble())))
        val expectedVertical = Math.toDegrees(2.0 * kotlin.math.atan(4.29f.toDouble() / (2.0 * 4.25f.toDouble())))
        assertEquals(expectedHorizontal, result.intrinsics.horizontalFovDeg, eps)
        assertEquals(expectedVertical, result.intrinsics.verticalFovDeg, eps)
    }

    @Test
    fun `missing focal length array falls back to legacy intrinsics`() {
        val result = resolveCameraIntrinsics(sourceOf(focalLengthsMm = null), imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.NO_VALID_FOCAL_LENGTH)
    }

    @Test
    fun `empty focal length array falls back to legacy intrinsics`() {
        val result =
            resolveCameraIntrinsics(sourceOf(focalLengthsMm = floatArrayOf()), imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.NO_VALID_FOCAL_LENGTH)
    }

    @Test
    fun `focal length array with only invalid values falls back to legacy intrinsics`() {
        val invalid = floatArrayOf(0f, -3f, Float.NaN, Float.POSITIVE_INFINITY)
        val result = resolveCameraIntrinsics(sourceOf(focalLengthsMm = invalid), imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.NO_VALID_FOCAL_LENGTH)
    }

    @Test
    fun `missing sensor width falls back to legacy intrinsics`() {
        val result = resolveCameraIntrinsics(sourceOf(sensorWidthMm = null), imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.MISSING_OR_INVALID_SENSOR_SIZE)
    }

    @Test
    fun `missing sensor height falls back to legacy intrinsics`() {
        val result = resolveCameraIntrinsics(sourceOf(sensorHeightMm = null), imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.MISSING_OR_INVALID_SENSOR_SIZE)
    }

    @Test
    fun `invalid sensor dimensions fall back to legacy intrinsics`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            val widthResult = resolveCameraIntrinsics(sourceOf(sensorWidthMm = bad), imageWidthPx = null, imageHeightPx = null)
            assertFallback(widthResult, CameraIntrinsicsFallbackReason.MISSING_OR_INVALID_SENSOR_SIZE, "width=$bad")

            val heightResult =
                resolveCameraIntrinsics(sourceOf(sensorHeightMm = bad), imageWidthPx = null, imageHeightPx = null)
            assertFallback(heightResult, CameraIntrinsicsFallbackReason.MISSING_OR_INVALID_SENSOR_SIZE, "height=$bad")
        }
    }

    @Test
    fun `exception while reading metadata falls back to legacy intrinsics`() {
        val throwingSource = CameraCharacteristicsSource { throw IllegalStateException("camera service unavailable") }
        val result = resolveCameraIntrinsics(throwingSource, imageWidthPx = null, imageHeightPx = null)
        assertFallback(result, CameraIntrinsicsFallbackReason.CHARACTERISTICS_UNAVAILABLE)
    }

    @Test
    fun `focal length selection is deterministic regardless of array order`() {
        val ascending = sourceOf(focalLengthsMm = floatArrayOf(2.0f, 4.25f, 6.0f))
        val descending = sourceOf(focalLengthsMm = floatArrayOf(6.0f, 4.25f, 2.0f))
        val shuffled = sourceOf(focalLengthsMm = floatArrayOf(4.25f, 6.0f, 2.0f))

        val results = listOf(ascending, descending, shuffled).map { resolveCameraIntrinsics(it, null, null) }
        results.forEach { result ->
            assertNull(result.fallbackReason)
            assertEquals(2.0, result.intrinsics.focalLengthMm)
        }
    }

    @Test
    fun `focal length selection ignores invalid candidates mixed with valid ones`() {
        val mixed = floatArrayOf(Float.NaN, -5f, 0f, 3.5f, 8f)
        assertEquals(3.5, selectFocalLengthMm(mixed))
    }

    @Test
    fun `selectFocalLengthMm returns null for null, empty, or all-invalid candidates`() {
        assertNull(selectFocalLengthMm(null))
        assertNull(selectFocalLengthMm(floatArrayOf()))
        assertNull(selectFocalLengthMm(floatArrayOf(0f, -1f, Float.NaN)))
    }

    @Test
    fun `all fallback paths report the explicit legacy source and a non-null reason`() {
        val fallbacks =
            listOf(
                resolveCameraIntrinsics(sourceOf(focalLengthsMm = null), null, null),
                resolveCameraIntrinsics(sourceOf(sensorWidthMm = null), null, null),
                resolveCameraIntrinsics(CameraCharacteristicsSource { error("boom") }, null, null),
            )
        fallbacks.forEach { result ->
            assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, result.intrinsics.source)
            assertEquals(56.0, result.intrinsics.verticalFovDeg, eps)
            assertNotNull(result.fallbackReason)
            assertNull(result.intrinsics.focalLengthMm)
            assertNull(result.intrinsics.sensorWidthMm)
            assertNull(result.intrinsics.sensorHeightMm)
        }
    }

    @Test
    fun `successful resolution reports a null fallback reason`() {
        val result = resolveCameraIntrinsics(sourceOf(), imageWidthPx = null, imageHeightPx = null)
        assertNull(result.fallbackReason)
    }

    private fun assertFallback(result: CameraIntrinsicsResolution, expectedReason: String, context: String = "") {
        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, result.intrinsics.source, context)
        assertEquals(expectedReason, result.fallbackReason, context)
        assertEquals(56.0, result.intrinsics.verticalFovDeg, eps, context)
        assertTrue(result.intrinsics.horizontalFovDeg > 0.0 && result.intrinsics.horizontalFovDeg < 180.0, context)
    }
}
