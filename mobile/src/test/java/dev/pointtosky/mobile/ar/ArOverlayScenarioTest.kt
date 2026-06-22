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
import dev.pointtosky.core.astro.catalog.StarFlags
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
                showStarLabels = true,
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

    @Test
    fun `AUX_ONLY skeleton node does not appear in rendered overlay labels`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val location = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        val lstDeg = lstAt(instant, location.lonDeg).lstDeg
        val reticleHorizontal = Horizontal(azDeg = 120.0, altDeg = 45.0)
        val reticleEquatorial = altAzToRaDec(reticleHorizontal, lstDeg, location.latDeg)
        val constellationId = ConstellationId(0)

        val realStar = StarRecord(
            id = StarId(1),
            rightAscensionDeg = reticleEquatorial.raDeg.toFloat(),
            declinationDeg = reticleEquatorial.decDeg.toFloat(),
            magnitude = 1.0f,
            constellationId = constellationId,
            flags = 0,
            name = "RealStar",
        )
        // AUX_ONLY skeleton node at the exact same position — must not appear in labels.
        val auxNode = StarRecord(
            id = StarId(2),
            rightAscensionDeg = reticleEquatorial.raDeg.toFloat(),
            declinationDeg = reticleEquatorial.decDeg.toFloat(),
            magnitude = 0.0f,
            constellationId = constellationId,
            flags = StarFlags.LINE_NODE or StarFlags.AUX_ONLY,
            name = "SkelNode",
        )

        val catalog = object : AstroCatalog {
            private val stars = listOf(realStar, auxNode)
            override fun getConstellationMeta(id: ConstellationId) =
                ConstellationMeta(id, abbreviation = "TST", name = "Test")
            override fun allStars() = stars
            override fun starById(raw: Int) = stars.find { it.id.raw == raw }
            override fun starsByConstellation(id: ConstellationId) =
                if (id == constellationId) stars else emptyList()
            override fun asterismsByConstellation(id: ConstellationId) = emptyList<Asterism>()
            override fun artOverlaysByConstellation(id: ConstellationId) = emptyList<ArtOverlay>()
        }

        val catalogState = AstroCatalogState(
            catalog = catalog,
            starsById = mapOf(realStar.id.raw to realStar, auxNode.id.raw to auxNode),
            constellationByAbbr = mapOf("TST" to constellationId),
            skeletonLines = emptyList(),
        )
        val state = ArUiState.Ready(
            instant = instant,
            location = location,
            locationResolved = true,
            lstDeg = lstDeg,
            stars = emptyList(),
            catalog = catalogState,
            showConstellations = false,
            showAsterisms = false,
            asterismUiState = AsterismUiState(isEnabled = false, highlighted = null, available = emptyList()),
            magLimit = 6.0,
            showStarLabels = true,
        )

        val forwardWorld = horizontalToVector(reticleHorizontal)
        val frame = RotationFrame(
            rotationMatrix = makeRotationMatrixFromForward(forwardWorld),
            forwardWorld = forwardWorld,
            timestampNanos = 0L,
        )

        val overlay = assertNotNull(calculateOverlay(
            state = state,
            frame = frame,
            viewport = IntSize(1080, 1080),
            resolveConstellation = { constellationId },
        ))

        assertTrue(overlay.labels.none { it.title == "SkelNode" },
            "AUX_ONLY skeleton node must not appear in overlay labels")
        assertEquals(1, overlay.labels.size,
            "Only the real bulk star should be labelled")
        assertEquals("RealStar", overlay.labels.first().title)
    }

    @Test
    fun `declination correction shifts reticle from magnetic to true azimuth`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val location = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        val lstDeg = lstAt(instant, location.lonDeg).lstDeg

        val D = 13.0
        val trueAz = 120.0
        val magneticAz = trueAz - D  // 107.0
        val altDeg = 45.0

        // Frame built in the magnetic sensor frame: device is physically aimed at TRUE azimuth 120°,
        // which is magnetic azimuth 107° (since magnetic north is D° east of true north).
        val magneticForward = horizontalToVector(Horizontal(azDeg = magneticAz, altDeg = altDeg))
        val frame = RotationFrame(
            rotationMatrix = makeRotationMatrixFromForward(magneticForward),
            forwardWorld = magneticForward,
            timestampNanos = 0L,
        )

        // Star placed at TRUE azimuth 120°.
        val starEquatorial = altAzToRaDec(Horizontal(azDeg = trueAz, altDeg = altDeg), lstDeg, location.latDeg)
        val constellationId = ConstellationId(0)
        val star = StarRecord(
            id = StarId(10),
            rightAscensionDeg = starEquatorial.raDeg.toFloat(),
            declinationDeg = starEquatorial.decDeg.toFloat(),
            magnitude = 1.0f,
            constellationId = constellationId,
            flags = 0,
            name = "TrueNorthStar",
        )
        val catalog = object : AstroCatalog {
            private val stars = listOf(star)
            override fun getConstellationMeta(id: ConstellationId) =
                ConstellationMeta(id, abbreviation = "TRU", name = "True")
            override fun allStars() = stars
            override fun starById(raw: Int) = stars.find { it.id.raw == raw }
            override fun starsByConstellation(id: ConstellationId) =
                if (id == constellationId) stars else emptyList()
            override fun asterismsByConstellation(id: ConstellationId) = emptyList<Asterism>()
            override fun artOverlaysByConstellation(id: ConstellationId) = emptyList<ArtOverlay>()
        }
        val catalogState = AstroCatalogState(
            catalog = catalog,
            starsById = mapOf(star.id.raw to star),
            constellationByAbbr = mapOf("TRU" to constellationId),
            skeletonLines = emptyList(),
        )
        val state = ArUiState.Ready(
            instant = instant,
            location = location,
            locationResolved = true,
            lstDeg = lstDeg,
            stars = emptyList(),
            catalog = catalogState,
            showConstellations = false,
            showAsterisms = false,
            asterismUiState = AsterismUiState(isEnabled = false, highlighted = null, available = emptyList()),
            magLimit = 6.0,
            showStarLabels = true,
        )
        val viewport = IntSize(1080, 1080)

        // With D=13: corrected frame's true forward = magneticAz + D = trueAz; reticle matches star.
        val correctedOverlay = assertNotNull(calculateOverlay(state, frame, viewport, { constellationId }, declinationDeg = D))
        assertEquals(trueAz, correctedOverlay.reticleHorizontal.azDeg, 1e-4)
        assertEquals(altDeg, correctedOverlay.reticleHorizontal.altDeg, 1e-4)
        val correctedNearest = assertNotNull(correctedOverlay.nearestLabel)
        assertTrue(correctedNearest.separationDeg < 0.01)

        // With D=0: no correction; reticle stays at magnetic az 107° — star at true az 120° is ~D away.
        val uncorrectedOverlay = assertNotNull(calculateOverlay(state, frame, viewport, { constellationId }, declinationDeg = 0.0))
        assertEquals(magneticAz, uncorrectedOverlay.reticleHorizontal.azDeg, 1e-4)
        val uncorrectedNearest = assertNotNull(uncorrectedOverlay.nearestLabel)
        assertTrue(uncorrectedNearest.separationDeg > D * 0.5)
    }

    @Test
    fun `correctedForTrueNorth rotates magnetic frame vector to true azimuth`() {
        // A vector pointing north (magnetic az=0) when rotated by D should yield true az=D.
        // vectorToHorizontal of the rotated forwardWorld should give az=D.
        val D = 13.0
        val northForward = horizontalToVector(Horizontal(azDeg = 0.0, altDeg = 0.0))
        val frame = RotationFrame(
            rotationMatrix = makeRotationMatrixFromForward(northForward),
            forwardWorld = northForward,
            timestampNanos = 0L,
        )
        val corrected = frame.correctedForTrueNorth(D)
        // After +D rotation: magnetic az=0 → true az=D
        val trueHorizontal = vectorToHorizontal(corrected.forwardWorld)
        assertEquals(D, trueHorizontal.azDeg, 1e-4)
        assertEquals(0.0, trueHorizontal.altDeg, 1e-4)
    }

    private fun vectorToHorizontal(vector: FloatArray): dev.pointtosky.core.astro.coord.Horizontal {
        val z = vector[2].toDouble().coerceIn(-1.0, 1.0)
        val altDeg = Math.toDegrees(kotlin.math.asin(z))
        var azDeg = Math.toDegrees(kotlin.math.atan2(vector[0].toDouble(), vector[1].toDouble()))
        if (azDeg < 0) azDeg += 360.0
        return dev.pointtosky.core.astro.coord.Horizontal(azDeg = azDeg, altDeg = altDeg)
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
