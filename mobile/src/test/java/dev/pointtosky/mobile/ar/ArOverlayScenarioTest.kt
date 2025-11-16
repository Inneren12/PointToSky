package dev.pointtosky.mobile.ar

import androidx.compose.ui.unit.IntSize
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.pointtosky.core.location.model.GeoPoint


class ArOverlayScenarioTest {
    @Test
    fun `reticle orientation projects stars and selects nearest target`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val location = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        // lstAt теперь возвращает Sidereal → берём угол в градусах
        val sidereal = lstAt(instant, location.lonDeg)
        val lstDeg = sidereal.lstDeg

        val reticleHorizontal = Horizontal(azDeg = 120.0, altDeg = 45.0)
        val reticleEquatorial = altAzToRaDec(reticleHorizontal, lstDeg, location.latDeg)

        val target = ArViewModel.ArStar(
            id = 1,
            label = "Target",
            equatorial = reticleEquatorial,
            magnitude = 1.0,
            )
        val farTarget = ArViewModel.ArStar(
            id = 2,
            label = "Far",
            equatorial = Equatorial(
                raDeg = reticleEquatorial.raDeg + 1.0,
                decDeg = reticleEquatorial.decDeg,
                ),
            magnitude = 1.0,
            )

        val state =
            ArUiState.Ready(
                instant = instant,
                location = location,
                locationResolved = true,
                lstDeg = lstDeg,
                stars = listOf(target, farTarget),
                )

        // Направление "вперёд" в мировых координатах (как в реальном коде)
        val forwardWorld = horizontalToVector(reticleHorizontal)
        // Собираем матрицу, согласованную с forwardWorld (как если бы её дал SensorManager)
        val rotationMatrix = makeRotationMatrixFromForward(forwardWorld)

        val frame = RotationFrame(
            rotationMatrix = rotationMatrix,
            forwardWorld = forwardWorld,
            timestampNanos = 0L,
            )

        val overlay = calculateOverlay(state, frame, IntSize(1080, 1080))

        val result = assertNotNull(overlay)
        // forwardWorld хранится как FloatArray и дальше гоняется через тригонометрию,
        // поэтому точность лучше ≈1e-5 градуса физически не достижима.
        assertEquals(reticleHorizontal.azDeg, result.reticleHorizontal.azDeg, 1e-5)
        assertEquals(reticleHorizontal.altDeg, result.reticleHorizontal.altDeg, 1e-5)
        assertEquals(reticleEquatorial.raDeg, result.reticleEquatorial.raDeg, 1e-5)
        assertEquals(reticleEquatorial.decDeg, result.reticleEquatorial.decDeg, 1e-5)

        val nearest = assertNotNull(result.nearestLabel)
        assertEquals("Target", nearest.title)
        assertTrue(abs(nearest.separationDeg) < 1e-3)
        assertTrue(nearest.position.x in 0f..1080f)
        assertTrue(nearest.position.y in 0f..1080f)
        assertEquals(2, result.labels.size)
        assertEquals("Far", result.labels[1].title)
        assertTrue(nearest.separationDeg < 0.01)
    }

    /**
     * Строим rotationMatrix, согласованный с forwardWorld.
     * Это имитирует поведение SensorManager: ось Z устройства направлена "из экрана",
     * а вперёд = -Z в координатах устройства.
     */
    private fun makeRotationMatrixFromForward(forward: FloatArray): FloatArray {
        var fx = forward[0]
        var fy = forward[1]
        var fz = forward[2]
        val fLen = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
        if (fLen == 0f) {
            // Фоллбэк: смотрим вдоль -Z
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
                )
        }
        fx /= fLen; fy /= fLen; fz /= fLen

        // Мировой "вверх"
        var ux = 0f
        var uy = 0f
        var uz = 1f
        val dotFU = fx * ux + fy * uy + fz * uz
        if (kotlin.math.abs(dotFU) > 0.99f) {
            ux = 0f; uy = 1f; uz = 0f
        }

        // right = up × forward
        var rx = uy * fz - uz * fy
        var ry = uz * fx - ux * fz
        var rz = ux * fy - uy * fx
        val rLen = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rLen; ry /= rLen; rz /= rLen

        // новый up = forward × right
        val ux2 = fy * rz - fz * ry
        val uy2 = fz * rx - fx * rz
        val uz2 = fx * ry - fy * rx

        // Матрица R (device -> world), столбцы — оси устройства в мировых координатах
        // Z-ось устройства направлена "из экрана", а "вперёд" = -Z, поэтому:
        return floatArrayOf(
            rx, ux2, -fx,
            ry, uy2, -fy,
            rz, uz2, -fz,
        )
    }

    private fun identityMatrix(): FloatArray =
        floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
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
}
