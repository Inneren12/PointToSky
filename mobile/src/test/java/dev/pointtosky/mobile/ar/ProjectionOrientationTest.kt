package dev.pointtosky.mobile.ar

import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the display-rotation remap contract behind [remapForDisplay] via the
 * Android-independent [remapRotationMatrixForDisplay].
 *
 * SensorManager.remapCoordinateSystem is a stubbed no-op under `:mobile` JVM unit tests
 * (unitTests.isReturnDefaultValues = true), so calling it here would silently leave outR
 * zeroed — every point then reports as "behind the camera" and projection returns an empty
 * list (see docs/cam_0a_recon.md). [remapRotationMatrixForDisplay] reimplements the same
 * axis-permutation contract in plain arithmetic so these tests stay meaningful in a plain JVM.
 */
class ProjectionOrientationTest {
    private val allRotations =
        listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )

    // Physical screen dimensions swap between portrait (0/180) and landscape (90/270) rotations.
    private fun viewportFor(displayRotation: Int): IntSize =
        when (displayRotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> IntSize(1920, 1080)
            else -> IntSize(1080, 1920)
        }

    @Test
    fun `remapRotationMatrixForDisplay matches the expected axis permutation and stays a proper rotation`() {
        val identity =
            floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
        val expected =
            mapOf(
                Surface.ROTATION_0 to floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                // AXIS_Y, AXIS_MINUS_X
                Surface.ROTATION_90 to floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f),
                Surface.ROTATION_180 to floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f),
                // AXIS_MINUS_Y, AXIS_X
                Surface.ROTATION_270 to floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            )

        expected.forEach { (displayRotation, expectedMatrix) ->
            val actual = remapRotationMatrixForDisplay(identity, displayRotation)
            assertMatrixEquals(expectedMatrix, actual, "displayRotation=$displayRotation")
            // Starting from a proper (det=+1) right-handed identity, the remap must stay a proper
            // rotation — a determinant flip would mean the remap silently mirrors the axes instead
            // of rotating them.
            assertTrue(abs(determinant(actual) - 1f) < 1e-5f, "displayRotation=$displayRotation: not a proper rotation (mirrored)")
        }
    }

    @Test
    fun `remapRotationMatrixForDisplay mirrors the exact AXIS_ mapping remapForDisplay requests from SensorManager`() {
        // Regression for a prior sign-inversion bug in this helper: it must reproduce production's
        // remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR) for ROTATION_90 and
        // remapCoordinateSystem(inR, AXIS_MINUS_Y, AXIS_X, outR) for ROTATION_270 (see
        // remapForDisplay), not the inverse pairing a naive "new axis expressed in old axes"
        // reading would produce.
        val inR =
            floatArrayOf(
                1f, 2f, 3f,
                4f, 5f, 6f,
                7f, 8f, 9f,
            )

        val rotation90 = remapRotationMatrixForDisplay(inR, Surface.ROTATION_90)
        // AXIS_Y, AXIS_MINUS_X: old column 1 (negated) -> new column 0; old column 0 -> new column 1.
        assertMatrixEquals(floatArrayOf(-2f, 1f, 3f, -5f, 4f, 6f, -8f, 7f, 9f), rotation90, "ROTATION_90 vs AXIS_Y/AXIS_MINUS_X")

        val rotation270 = remapRotationMatrixForDisplay(inR, Surface.ROTATION_270)
        // AXIS_MINUS_Y, AXIS_X: old column 1 -> new column 0; old column 0 (negated) -> new column 1.
        assertMatrixEquals(floatArrayOf(2f, -1f, 3f, 5f, -4f, 6f, 8f, -7f, 9f), rotation270, "ROTATION_270 vs AXIS_MINUS_Y/AXIS_X")
    }

    @Test
    fun `remapRotationMatrixForDisplay never zeroes the rotation matrix`() {
        // Regression for the bug this test class exists to close: under the old
        // SensorManager-backed path, JVM unit tests silently got an all-zero outR back
        // (SensorManager.remapCoordinateSystem never writes outR when stubbed), so every
        // projected point was rejected and callers observed count 0.
        val forward = horizontalToVector(Horizontal(azDeg = 15.0, altDeg = 35.0))
        val rotation = makeRotationMatrixFromForward(forward)

        allRotations.forEach { displayRotation ->
            val remapped = remapRotationMatrixForDisplay(rotation, displayRotation)
            val normSquared = remapped.fold(0f) { acc, v -> acc + v * v }
            assertTrue(normSquared > 1e-6f, "displayRotation=$displayRotation produced a zeroed matrix")
        }
    }

    @Test
    fun `remapRotationMatrixForDisplay preserves orthonormality and handedness for a realistic orientation`() {
        val forward = horizontalToVector(Horizontal(azDeg = 200.0, altDeg = 10.0))
        val rotation = makeRotationMatrixFromForward(forward)

        // makeRotationMatrixFromForward's own X/Y basis choice is arbitrary (it doesn't claim to
        // match true compass right/up), so we don't assert an absolute handedness sign here.
        // What must hold is that the remap doesn't silently flip it relative to ROTATION_0 — that
        // would mean two display rotations disagree on which way is left/right for the same
        // physical orientation.
        var baselineHandedness: Float? = null
        allRotations.forEach { displayRotation ->
            val r = remapRotationMatrixForDisplay(rotation, displayRotation)
            val deviceX = floatArrayOf(r[0], r[3], r[6])
            val deviceY = floatArrayOf(r[1], r[4], r[7])
            val deviceZ = floatArrayOf(r[2], r[5], r[8])

            assertTrue(abs(dot(deviceX, deviceX) - 1f) < 1e-4f, "displayRotation=$displayRotation")
            assertTrue(abs(dot(deviceY, deviceY) - 1f) < 1e-4f, "displayRotation=$displayRotation")
            assertTrue(abs(dot(deviceZ, deviceZ) - 1f) < 1e-4f, "displayRotation=$displayRotation")
            assertTrue(abs(dot(deviceX, deviceY)) < 1e-4f, "displayRotation=$displayRotation: X/Y not orthogonal")

            val handedness = dot(cross(deviceX, deviceY), deviceZ)
            assertTrue(abs(abs(handedness) - 1f) < 1e-4f, "displayRotation=$displayRotation")
            val expected = baselineHandedness
            if (expected == null) {
                baselineHandedness = handedness
            } else {
                assertTrue(
                    abs(handedness - expected) < 1e-4f,
                    "displayRotation=$displayRotation: handedness flipped relative to ROTATION_0 (mirrored)",
                )
            }
        }
    }

    @Test
    fun `polyline shape is stable across display rotations sharing the same aspect ratio`() {
        // Portrait (0/180) and landscape (90/270) are NOT compared against each other: the
        // projection model uses a fixed vertical FOV with a horizontal FOV derived from the
        // viewport aspect ratio (see VERTICAL_FOV_DEG / projectionParams in ArScreen.kt), so
        // portrait and landscape genuinely render a different angular extent by existing,
        // unchanged design — that's out of scope for CAM-0c to alter. Rotations that share an
        // aspect ratio (0<->180, 90<->270) must still reproduce the same relative shape.
        val horizontals =
            listOf(
                Horizontal(azDeg = 0.0, altDeg = 45.0),
                Horizontal(azDeg = 5.0, altDeg = 45.0),
                Horizontal(azDeg = 5.0, altDeg = 50.0),
            )

        val baseForward = horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 45.0))
        val baseRotation = makeRotationMatrixFromForward(baseForward)

        val normalizedLengthsByRotation =
            allRotations.associateWith { rotation ->
                val viewport = viewportFor(rotation)
                val projected =
                    projectHorizontalsToScreen(
                        frame = makeFrame(baseRotation, rotation),
                        viewport = viewport,
                        horizontals = horizontals,
                    )
                assertEquals(horizontals.size, projected.size, "displayRotation=$rotation")
                normalizeLengths(segmentLengths(projected), viewport)
            }

        fun assertShapesMatch(a: Int, b: Int) {
            normalizedLengthsByRotation.getValue(a).zip(normalizedLengthsByRotation.getValue(b)).forEach { (x, y) ->
                assertTrue(abs(x - y) < 1e-3, "displayRotation $a vs $b")
            }
        }
        assertShapesMatch(Surface.ROTATION_0, Surface.ROTATION_180)
        assertShapesMatch(Surface.ROTATION_90, Surface.ROTATION_270)
    }

    @Test
    fun `known horizontal points remain projectable after remap for every rotation`() {
        val horizontals =
            listOf(
                Horizontal(azDeg = 0.0, altDeg = 55.0),
                Horizontal(azDeg = 10.0, altDeg = 40.0),
                Horizontal(azDeg = -10.0, altDeg = 40.0),
                Horizontal(azDeg = 0.0, altDeg = 25.0),
            )
        val forward = horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 40.0))
        val rotation = makeRotationMatrixFromForward(forward)

        allRotations.forEach { displayRotation ->
            val viewport = viewportFor(displayRotation)
            val projected =
                projectHorizontalsToScreen(
                    frame = makeFrame(rotation, displayRotation),
                    viewport = viewport,
                    horizontals = horizontals,
                )
            assertEquals(
                horizontals.size,
                projected.size,
                "expected all points on-screen for displayRotation=$displayRotation",
            )
            projected.forEach { point ->
                assertTrue(point.x in 0f..viewport.width.toFloat(), "displayRotation=$displayRotation")
                assertTrue(point.y in 0f..viewport.height.toFloat(), "displayRotation=$displayRotation")
            }
        }
    }

    @Test
    fun `point in front stays in front and point behind stays rejected for every rotation`() {
        val boresight = Horizontal(azDeg = 90.0, altDeg = 25.0)
        val behind = Horizontal(azDeg = 270.0, altDeg = -25.0)
        val forward = horizontalToVector(boresight)
        val rotation = makeRotationMatrixFromForward(forward)

        allRotations.forEach { displayRotation ->
            val viewport = viewportFor(displayRotation)
            val frame = makeFrame(rotation, displayRotation)

            val front = projectHorizontalsToScreen(frame, viewport, listOf(boresight))
            assertEquals(1, front.size, "displayRotation=$displayRotation: point in front must project")
            val center = front.single()
            assertTrue(
                abs(center.x - viewport.width / 2f) < viewport.width * 0.05f,
                "displayRotation=$displayRotation: boresight should land near screen center",
            )
            assertTrue(
                abs(center.y - viewport.height / 2f) < viewport.height * 0.05f,
                "displayRotation=$displayRotation: boresight should land near screen center",
            )

            val rejected = projectHorizontalsToScreen(frame, viewport, listOf(behind))
            assertTrue(rejected.isEmpty(), "displayRotation=$displayRotation: point behind camera must be rejected")
        }
    }

    private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray, message: String) {
        expected.indices.forEach { i ->
            assertTrue(abs(expected[i] - actual[i]) < 1e-6f, "$message: index $i expected ${expected[i]} but was ${actual[i]}")
        }
    }

    private fun determinant(m: FloatArray): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )

    private fun horizontalToVector(horizontal: Horizontal): FloatArray {
        val altRad = Math.toRadians(horizontal.altDeg)
        val azRad = Math.toRadians(horizontal.azDeg)
        val cosAlt = kotlin.math.cos(altRad)
        return floatArrayOf(
            (cosAlt * kotlin.math.sin(azRad)).toFloat(),
            (cosAlt * kotlin.math.cos(azRad)).toFloat(),
            kotlin.math.sin(altRad).toFloat(),
        )
    }

    private fun makeFrame(rotation: FloatArray, displayRotation: Int): RotationFrame {
        val remapped = remapRotationMatrixForDisplay(rotation, displayRotation)
        val forward =
            floatArrayOf(
                -remapped[2],
                -remapped[5],
                -remapped[8],
            )
        val forwardLen = sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2])
        val normalizedForward =
            if (forwardLen == 0f) floatArrayOf(0f, 0f, -1f)
            else floatArrayOf(forward[0] / forwardLen, forward[1] / forwardLen, forward[2] / forwardLen)
        return RotationFrame(remapped.copyOf(), normalizedForward, timestampNanos = 0L)
    }

    private fun makeRotationMatrixFromForward(forward: FloatArray): FloatArray {
        var fx = forward[0]
        var fy = forward[1]
        var fz = forward[2]
        val fLen = sqrt(fx * fx + fy * fy + fz * fz)
        if (fLen == 0f) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
        }
        fx /= fLen; fy /= fLen; fz /= fLen

        var ux = 0f
        var uy = 0f
        var uz = 1f
        val dotFU = fx * ux + fy * uy + fz * uz
        if (kotlin.math.abs(dotFU) > 0.99f) {
            ux = 0f; uy = 1f; uz = 0f
        }

        var rx = uy * fz - uz * fy
        var ry = uz * fx - ux * fz
        var rz = ux * fy - uy * fx
        val rLen = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rLen; ry /= rLen; rz /= rLen

        val ux2 = fy * rz - fz * ry
        val uy2 = fz * rx - fx * rz
        val uz2 = fx * ry - fy * rx

        return floatArrayOf(
            rx, ux2, -fx,
            ry, uy2, -fy,
            rz, uz2, -fz,
        )
    }

    private fun segmentLengths(points: List<Offset>): List<Double> =
        points.zipWithNext { start, end ->
            hypot(
                (end.x - start.x).toDouble(),
                (end.y - start.y).toDouble(),
            )
        }

    private fun normalizeLengths(lengths: List<Double>, viewport: IntSize): List<Double> {
        val scale = hypot(viewport.width.toDouble(), viewport.height.toDouble())
        return lengths.map { it / scale }
    }
}
