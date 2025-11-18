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

class ProjectionOrientationTest {
    @Test
    fun `polyline shape is stable across display rotations`() {
        val horizontals =
            listOf(
                Horizontal(azDeg = 0.0, altDeg = 45.0),
                Horizontal(azDeg = 5.0, altDeg = 45.0),
                Horizontal(azDeg = 5.0, altDeg = 50.0),
            )

        val baseForward = horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 45.0))
        val baseRotation = makeRotationMatrixFromForward(baseForward)

        val portrait =
            projectHorizontalsToScreen(
                frame = makeFrame(baseRotation, Surface.ROTATION_0),
                viewport = IntSize(1080, 1920),
                horizontals = horizontals,
            )
        val landscape =
            projectHorizontalsToScreen(
                frame = makeFrame(baseRotation, Surface.ROTATION_90),
                viewport = IntSize(1920, 1080),
                horizontals = horizontals,
            )

        assertEquals(horizontals.size, portrait.size)
        assertEquals(horizontals.size, landscape.size)

        val portraitLengths = normalizeLengths(segmentLengths(portrait), IntSize(1080, 1920))
        val landscapeLengths = normalizeLengths(segmentLengths(landscape), IntSize(1920, 1080))

        portraitLengths.zip(landscapeLengths).forEach { (portraitLength, landscapeLength) ->
            assertTrue(abs(portraitLength - landscapeLength) < 1e-3)
        }
    }

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
        val remapped = remapForDisplay(rotation, displayRotation)
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
