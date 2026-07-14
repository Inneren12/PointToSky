package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceToOpticalCameraTransformTest {
    @Test
    fun `x is passed through unchanged`() {
        val optical = DeviceToOpticalCameraTransform.apply(DeviceVector(3.5, 0.0, -1.0))
        assertEquals(3.5, optical.x)
    }

    @Test
    fun `y is negated (device up becomes optical up, i,e, smaller image-down value)`() {
        val optical = DeviceToOpticalCameraTransform.apply(DeviceVector(0.0, 2.0, -1.0))
        assertEquals(-2.0, optical.y)
    }

    @Test
    fun `z is negated (device -Z, the camera forward axis, becomes optical +Z)`() {
        val optical = DeviceToOpticalCameraTransform.apply(DeviceVector(0.0, 0.0, -1.0))
        assertEquals(1.0, optical.z)
    }

    @Test
    fun `applying twice returns the original vector (its own inverse)`() {
        val original = DeviceVector(1.2, -3.4, 5.6)
        val once = DeviceToOpticalCameraTransform.apply(original)
        val twice = OpticalCameraVector(x = once.x, y = -once.y, z = -once.z) // manual re-application of the same sign pattern
        assertEquals(original.x, twice.x)
        assertEquals(original.y, twice.y)
        assertEquals(original.z, twice.z)
    }

    @Test
    fun `DeviceVector rejects non-finite components`() {
        assertFailsWith<IllegalArgumentException> { DeviceVector(Double.NaN, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { DeviceVector(0.0, Double.POSITIVE_INFINITY, 0.0) }
    }

    @Test
    fun `OpticalCameraVector rejects non-finite components`() {
        assertFailsWith<IllegalArgumentException> { OpticalCameraVector(Double.NaN, 0.0, 0.0) }
    }
}
