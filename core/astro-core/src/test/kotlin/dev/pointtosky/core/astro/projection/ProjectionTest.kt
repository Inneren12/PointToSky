package dev.pointtosky.core.astro.projection

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the extracted AR projection math (CAM-1a). These lock the formulas that
 * previously lived as private helpers in `mobile/.../ar/ArScreen.kt` so future CAM work can rely
 * on them without the Compose/Android UI layer.
 */
class ProjectionTest {
    private val eps = 1e-5f

    private fun assertVectorEquals(expected: FloatArray, actual: FloatArray, message: String = "") {
        assertEquals(expected.size, actual.size, message)
        expected.indices.forEach { i ->
            assertTrue(abs(expected[i] - actual[i]) < eps, "$message: index $i expected ${expected[i]} but was ${actual[i]}")
        }
    }

    @Test
    fun `horizontalToVector cardinal table`() {
        assertVectorEquals(floatArrayOf(0f, 1f, 0f), horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 0.0)), "North horizon")
        assertVectorEquals(floatArrayOf(1f, 0f, 0f), horizontalToVector(Horizontal(azDeg = 90.0, altDeg = 0.0)), "East horizon")
        assertVectorEquals(floatArrayOf(0f, -1f, 0f), horizontalToVector(Horizontal(azDeg = 180.0, altDeg = 0.0)), "South horizon")
        assertVectorEquals(floatArrayOf(-1f, 0f, 0f), horizontalToVector(Horizontal(azDeg = 270.0, altDeg = 0.0)), "West horizon")
        assertVectorEquals(floatArrayOf(0f, 0f, 1f), horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 90.0)), "Zenith")
    }

    @Test
    fun `vectorToHorizontal inverts horizontalToVector`() {
        val cases =
            listOf(
                Horizontal(azDeg = 0.0, altDeg = 0.0),
                Horizontal(azDeg = 45.0, altDeg = 10.0),
                Horizontal(azDeg = 123.0, altDeg = 35.0),
                Horizontal(azDeg = 200.0, altDeg = 60.0),
                Horizontal(azDeg = 315.0, altDeg = 5.0),
                Horizontal(azDeg = 90.0, altDeg = 80.0),
            )
        cases.forEach { h ->
            val roundTrip = vectorToHorizontal(horizontalToVector(h))
            assertTrue(abs(roundTrip.altDeg - h.altDeg) < 1e-3, "alt for $h -> $roundTrip")
            // Azimuth is ill-defined at the zenith; all test cases stay below alt 90.
            assertTrue(abs(roundTrip.azDeg - h.azDeg) < 1e-3, "az for $h -> $roundTrip")
        }
    }

    @Test
    fun `vectorToHorizontal normalizes azimuth into 0 to 360`() {
        // West points to atan2(-1, 0) = -90 before normalization; must come back as 270.
        val west = vectorToHorizontal(floatArrayOf(-1f, 0f, 0f))
        assertTrue(abs(west.azDeg - 270.0) < 1e-3, "west az=${west.azDeg}")
    }

    @Test
    fun `transpose identity is identity`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertVectorEquals(identity, transpose(identity), "identity transpose")
    }

    @Test
    fun `transpose swaps off-diagonal elements`() {
        val m = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val expected = floatArrayOf(1f, 4f, 7f, 2f, 5f, 8f, 3f, 6f, 9f)
        assertVectorEquals(expected, transpose(m), "transpose")
    }

    @Test
    fun `transpose of a 90 degree rotation is its inverse`() {
        // Rotation by +90 deg about z: maps x-axis -> y-axis.
        val rot = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val inv = transpose(rot)
        // rot * inv should be identity.
        val product = matMul(rot, inv)
        assertVectorEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), product, "rot * rot^T")
    }

    @Test
    fun `multiply by identity returns the vector`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val v = floatArrayOf(3f, -4f, 5f)
        assertVectorEquals(v, multiply(identity, v), "identity multiply")
    }

    @Test
    fun `multiply applies a 90 degree rotation consistently`() {
        // +90 deg about z maps (1,0,0) -> (0,1,0).
        val rot = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertVectorEquals(floatArrayOf(0f, 1f, 0f), multiply(rot, floatArrayOf(1f, 0f, 0f)), "rotate x-axis")
        // Its transpose (inverse world->device style usage) reverses it.
        assertVectorEquals(floatArrayOf(1f, 0f, 0f), multiply(transpose(rot), floatArrayOf(0f, 1f, 0f)), "inverse rotate")
    }

    @Test
    fun `optical axis projects to screen centre`() {
        val viewport = ViewportSize(1080, 1920)
        val params = projectionParams(viewport)
        // Straight ahead: z negative (in front), no lateral offset.
        val projected = projectDeviceVector(floatArrayOf(0f, 0f, -1f), params)
        assertNotNull(projected, "on-axis point must project")
        assertTrue(abs(projected.x - viewport.width / 2f) < 1e-3f, "centre x=${projected.x}")
        assertTrue(abs(projected.y - viewport.height / 2f) < 1e-3f, "centre y=${projected.y}")
        assertTrue(abs(projected.distance) < 1e-6, "centre distance=${projected.distance}")
    }

    @Test
    fun `behind camera is rejected at the z threshold`() {
        val params = projectionParams(ViewportSize(1080, 1920))
        // z well behind camera.
        assertNull(projectDeviceVector(floatArrayOf(0f, 0f, 1f), params), "positive z rejected")
        // Exactly at the threshold z = -0.01 is rejected (>= -0.01f).
        assertNull(projectDeviceVector(floatArrayOf(0f, 0f, -0.01f), params), "z == -0.01 rejected")
        // Just in front of the threshold projects.
        assertNotNull(projectDeviceVector(floatArrayOf(0f, 0f, -0.02f), params), "z < -0.01 accepted")
    }

    @Test
    fun `half FOV vectors land near the frame edge`() {
        val viewport = ViewportSize(1080, 1920)
        val params = projectionParams(viewport)

        // A device vector pointing at exactly half the vertical FOV: ndcY == 1 -> top edge.
        val halfVFov = floatArrayOf(0f, params.tanVFov.toFloat(), -1f)
        val vProjected = projectDeviceVector(halfVFov, params)
        assertNotNull(vProjected, "half vertical FOV must project")
        assertTrue(abs(vProjected.y - 0f) < 1e-2f, "half vFOV should reach top edge y=${vProjected.y}")

        // Half the horizontal FOV: ndcX == 1 -> right edge.
        val halfHFov = floatArrayOf(params.tanHFov.toFloat(), 0f, -1f)
        val hProjected = projectDeviceVector(halfHFov, params)
        assertNotNull(hProjected, "half horizontal FOV must project")
        assertTrue(abs(hProjected.x - viewport.width.toFloat()) < 1e-2f, "half hFOV should reach right edge x=${hProjected.x}")
    }

    @Test
    fun `positive device Y projects upward in NDC but to a lower screen Y`() {
        val viewport = ViewportSize(1080, 1920)
        val params = projectionParams(viewport)
        val up = projectDeviceVector(floatArrayOf(0f, 0.2f, -1f), params)
        assertNotNull(up, "upward vector must project")
        // Screen Y grows downward, so "up" must be above centre (smaller y).
        assertTrue(up.y < viewport.height / 2f, "positive device Y should map to smaller screen Y, got ${up.y}")
    }

    @Test
    fun `portrait and landscape derive different horizontal FOV`() {
        val portrait = projectionParams(ViewportSize(1080, 1920))
        val landscape = projectionParams(ViewportSize(1920, 1080))
        // Vertical FOV is fixed; horizontal is aspect-derived, so the two differ.
        assertTrue(abs(portrait.tanVFov - landscape.tanVFov) < 1e-9, "vFOV must be identical")
        assertTrue(portrait.tanHFov < landscape.tanHFov, "landscape must have a wider horizontal FOV")
        // Exact aspect relationship.
        assertTrue(abs(portrait.tanHFov - portrait.tanVFov * (1080.0 / 1920.0)) < 1e-9, "portrait hFOV = vFOV * aspect")
        assertTrue(abs(landscape.tanHFov - landscape.tanVFov * (1920.0 / 1080.0)) < 1e-9, "landscape hFOV = vFOV * aspect")
    }

    @Test
    fun `max screen distance rejects far off-axis points`() {
        val params = projectionParams(ViewportSize(1080, 1920))
        // Push far outside the frustum: large lateral component -> distance > MAX_SCREEN_DISTANCE.
        val faOff = projectDeviceVector(floatArrayOf(10f * params.tanHFov.toFloat(), 0f, -1f), params)
        assertNull(faOff, "point far off-axis must be rejected by MAX_SCREEN_DISTANCE")
    }

    @Test
    fun `vertical FOV constant is unchanged`() {
        assertEquals(56.0, VERTICAL_FOV_DEG, "VERTICAL_FOV_DEG must stay 56.0")
    }

    private fun matMul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += a[row * 3 + k] * b[k * 3 + col]
                }
                out[row * 3 + col] = sum
            }
        }
        return out
    }
}
