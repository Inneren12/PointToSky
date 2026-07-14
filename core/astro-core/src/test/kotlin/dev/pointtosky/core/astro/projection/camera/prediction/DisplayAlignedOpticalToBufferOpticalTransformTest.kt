package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the literal `J(rotationDegrees)⁻¹` formulas documented on
 * [DisplayAlignedOpticalToBufferOpticalTransform] (Blocker 1 fix), independent of the rest of the
 * projection pipeline. A generic, non-axis-aligned `(dx, dy)` is used throughout so a transposition or
 * sign error cannot hide behind a coincidental symmetric input.
 */
class DisplayAlignedOpticalToBufferOpticalTransformTest {
    private val dx = 0.3
    private val dy = 0.7
    private val dz = 2.5
    private val displayOptical = OpticalCameraVector(x = dx, y = dy, z = dz)

    @Test
    fun `0 degrees is the identity`() {
        val result = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 0)
        assertEquals(dx, result.x)
        assertEquals(dy, result.y)
        assertEquals(dz, result.z)
    }

    @Test
    fun `90 degrees maps bufferX=dy, bufferY=-dx`() {
        val result = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 90)
        assertEquals(dy, result.x)
        assertEquals(-dx, result.y)
        assertEquals(dz, result.z)
    }

    @Test
    fun `180 degrees maps bufferX=-dx, bufferY=-dy`() {
        val result = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 180)
        assertEquals(-dx, result.x)
        assertEquals(-dy, result.y)
        assertEquals(dz, result.z)
    }

    @Test
    fun `270 degrees maps bufferX=-dy, bufferY=dx`() {
        val result = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 270)
        assertEquals(-dy, result.x)
        assertEquals(dx, result.y)
        assertEquals(dz, result.z)
    }

    @Test
    fun `z (depth) is never touched, for any rotation`() {
        for (rotationDegrees in listOf(0, 90, 180, 270)) {
            val result = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, rotationDegrees)
            assertEquals(dz, result.z, "z must be unchanged for rotationDegrees=$rotationDegrees")
        }
    }

    // --- Algebraic properties (verify the derivation, not just the literal table) -------------------

    @Test
    fun `composing 90 then 270 returns the original vector`() {
        val afterNinety = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 90)
        val roundTrip =
            DisplayAlignedOpticalToBufferOpticalTransform.apply(
                OpticalCameraVector(x = afterNinety.x, y = afterNinety.y, z = afterNinety.z),
                270,
            )
        assertEquals(dx, roundTrip.x, 1e-12)
        assertEquals(dy, roundTrip.y, 1e-12)
    }

    @Test
    fun `composing 270 then 90 returns the original vector`() {
        val afterTwoSeventy = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 270)
        val roundTrip =
            DisplayAlignedOpticalToBufferOpticalTransform.apply(
                OpticalCameraVector(x = afterTwoSeventy.x, y = afterTwoSeventy.y, z = afterTwoSeventy.z),
                90,
            )
        assertEquals(dx, roundTrip.x, 1e-12)
        assertEquals(dy, roundTrip.y, 1e-12)
    }

    @Test
    fun `180 is its own inverse`() {
        val once = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 180)
        val twice =
            DisplayAlignedOpticalToBufferOpticalTransform.apply(
                OpticalCameraVector(x = once.x, y = once.y, z = once.z),
                180,
            )
        assertEquals(dx, twice.x, 1e-12)
        assertEquals(dy, twice.y, 1e-12)
    }

    @Test
    fun `0 is its own inverse`() {
        val once = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 0)
        val twice =
            DisplayAlignedOpticalToBufferOpticalTransform.apply(
                OpticalCameraVector(x = once.x, y = once.y, z = once.z),
                0,
            )
        assertEquals(dx, twice.x, 1e-12)
        assertEquals(dy, twice.y, 1e-12)
    }

    // --- Validation ------------------------------------------------------------------------------

    @Test
    fun `an invalid rotationDegrees is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 45)
        }
        assertFailsWith<IllegalArgumentException> {
            DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, -90)
        }
        assertFailsWith<IllegalArgumentException> {
            DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, 360)
        }
    }

    private fun assertEquals(
        expected: Double,
        actual: Double,
        tolerance: Double,
        message: String? = null,
    ) {
        kotlin.test.assertTrue(kotlin.math.abs(expected - actual) < tolerance, message ?: "expected $expected but was $actual")
    }
}
