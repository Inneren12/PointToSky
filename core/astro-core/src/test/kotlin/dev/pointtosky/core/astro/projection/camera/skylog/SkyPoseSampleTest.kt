package dev.pointtosky.core.astro.projection.camera.skylog

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [SkyPoseSample]: the rotation matrix is authoritative, the quaternion is derived
 * from it on every read, and the `TimedRotationSample` handed to the math is the same rotation.
 */
class SkyPoseSampleTest {
    private fun rotationAboutZ(angleRad: Double): List<Double> =
        listOf(
            cos(angleRad),
            -sin(angleRad),
            0.0,
            sin(angleRad),
            cos(angleRad),
            0.0,
            0.0,
            0.0,
            1.0,
        )

    @Test
    fun `the identity matrix derives the identity quaternion`() {
        val pose = SkySessionLogFixtures.pose(rotationMatrix = SkySessionLogFixtures.identityRotationMatrix)

        val quaternion = pose.quaternion

        assertEquals(0.0, quaternion.x, absoluteTolerance = 1e-12)
        assertEquals(0.0, quaternion.y, absoluteTolerance = 1e-12)
        assertEquals(0.0, quaternion.z, absoluteTolerance = 1e-12)
        assertEquals(1.0, quaternion.w, absoluteTolerance = 1e-12)
    }

    @Test
    fun `a 90 degree rotation about Z derives the expected quaternion`() {
        val pose = SkySessionLogFixtures.pose(rotationMatrix = rotationAboutZ(PI / 2.0))

        val quaternion = pose.quaternion

        assertEquals(0.0, quaternion.x, absoluteTolerance = 1e-12)
        assertEquals(0.0, quaternion.y, absoluteTolerance = 1e-12)
        assertEquals(sqrt(0.5), quaternion.z, absoluteTolerance = 1e-12)
        assertEquals(sqrt(0.5), quaternion.w, absoluteTolerance = 1e-12)
    }

    @Test
    fun `every derived quaternion is a unit quaternion with a non-negative scalar part`() {
        // Sweep past the trace <= 0 branches, where a naive derivation takes a square root of a
        // near-zero number and loses precision or sign.
        val angles = (0..36).map { it * 10.0 * PI / 180.0 }

        angles.forEach { angle ->
            val quaternion = SkySessionLogFixtures.pose(rotationMatrix = rotationAboutZ(angle)).quaternion
            val norm =
                sqrt(
                    quaternion.x * quaternion.x + quaternion.y * quaternion.y + quaternion.z * quaternion.z +
                        quaternion.w * quaternion.w,
                )
            assertEquals(1.0, norm, absoluteTolerance = 1e-9, message = "angle=$angle produced a non-unit quaternion")
            assertTrue(quaternion.w >= 0.0, "angle=$angle produced a negative scalar part")
        }
    }

    @Test
    fun `a 180 degree rotation resolves through the largest-divisor branch`() {
        val pose = SkySessionLogFixtures.pose(rotationMatrix = rotationAboutZ(PI))

        val quaternion = pose.quaternion

        assertEquals(1.0, quaternion.z, absoluteTolerance = 1e-9)
        assertEquals(0.0, quaternion.w, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the timed rotation sample carries the same rotation on the aligned clock`() {
        val pose = SkySessionLogFixtures.pose(rotationMatrix = rotationAboutZ(PI / 3.0))

        val sample = pose.toTimedRotationSample(alignedTimestampNanos = 12_345L)

        assertEquals(12_345L, sample.timestampNanos)
        assertEquals(9, sample.rotationMatrix.size)
        sample.rotationMatrix.forEachIndexed { index, value ->
            assertEquals(pose.rotationMatrix[index].toFloat(), value)
        }
    }

    @Test
    fun `a rotation matrix of the wrong size is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkyPoseSample(timestampNanos = 0L, rotationMatrix = listOf(1.0, 0.0, 0.0), frameToPoseDeltaNanos = 0L)
        }
    }

    @Test
    fun `a non-finite rotation matrix element is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkyPoseSample(
                timestampNanos = 0L,
                rotationMatrix = List(9) { if (it == 4) Double.NaN else 0.0 },
                frameToPoseDeltaNanos = 0L,
            )
        }
    }
}
