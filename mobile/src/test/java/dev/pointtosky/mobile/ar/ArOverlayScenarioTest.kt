package dev.pointtosky.mobile.ar

import androidx.compose.ui.unit.IntSize
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.catalog.Asterism
import dev.pointtosky.core.astro.catalog.ArtOverlay
import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.ConstellationMeta
import dev.pointtosky.core.astro.catalog.StarId
import dev.pointtosky.core.astro.catalog.StarRecord
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

        val constellationId = ConstellationId(0)
        val target =
            StarRecord(
                id = StarId(1),
                rightAscensionDeg = reticleEquatorial.raDeg.toFloat(),
                declinationDeg = reticleEquatorial.decDeg.toFloat(),
                magnitude = 1.0f,
                constellationId = constellationId,
                flags = 0,
                name = "Target",
            )
        val farTarget =
            StarRecord(
                id = StarId(2),
                rightAscensionDeg = (reticleEquatorial.raDeg + 1.0).toFloat(),
                declinationDeg = reticleEquatorial.decDeg.toFloat(),
                magnitude = 1.0f,
                constellationId = constellationId,
                flags = 0,
                name = "Far",
            )

        val catalog =
            object : AstroCatalog {
                private val stars = listOf(target, farTarget)

                override fun getConstellationMeta(id: ConstellationId): ConstellationMeta =
                    ConstellationMeta(id, abbreviation = "TST", name = "Test")

                override fun allStars(): List<StarRecord> = stars

                override fun starById(raw: Int): StarRecord? = stars.find { it.id.raw == raw }

                override fun starsByConstellation(id: ConstellationId): List<StarRecord> =
                    if (id == constellationId) stars else emptyList()

                override fun asterismsByConstellation(id: ConstellationId) = emptyList<Asterism>()

                override fun artOverlaysByConstellation(id: ConstellationId) = emptyList<ArtOverlay>()
            }

        val catalogState =
            AstroCatalogState(
                catalog = catalog,
                starsById = mapOf(target.id.raw to target, farTarget.id.raw to farTarget),
                constellationByAbbr = mapOf("TST" to constellationId),
                skeletonLines = emptyList(),
            )

        val state =
            ArUiState.Ready(
                instant = instant,
                location = location,
                locationResolved = true,
                lstDeg = lstDeg,
                stars = emptyList(),
                catalog = catalogState,
                showConstellations = false,
                showAsterisms = false,
                asterismUiState =
                    AsterismUiState(
                        isEnabled = false,
                        highlighted = null,
                        available = emptyList(),
                    ),
                magLimit = 6.0,
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

        val overlay =
            calculateOverlay(
                state = state,
                frame = frame,
                viewport = IntSize(1080, 1080),
                resolveConstellation = { constellationId },
            )

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
        assertTrue(result.constellationSegments.isEmpty())
        assertTrue(result.asterismSegments.isEmpty())
        assertTrue(result.artOverlays.isEmpty())
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
