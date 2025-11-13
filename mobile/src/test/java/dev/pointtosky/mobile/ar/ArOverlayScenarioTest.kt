package dev.pointtosky.mobile.ar

import androidx.compose.ui.unit.IntSize
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.GeoPoint
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArOverlayScenarioTest {
    @Test
    fun `reticle orientation projects stars and selects nearest target`() {
        val location = GeoPoint(latDeg = 53.5461, lonDeg = -113.4938)
        val instant = Instant.parse("2024-07-01T06:00:00Z")
        val lstDeg = lstAt(instant, location.lonDeg).lstDeg

        val reticleHorizontal = Horizontal(azDeg = 120.0, altDeg = 45.0)
        val reticleEquatorial = altAzToRaDec(reticleHorizontal, lstDeg = lstDeg, latDeg = location.latDeg)

        val target =
            ArViewModel.ArStar(
                id = 1,
                label = "Target",
                magnitude = 1.0,
                equatorial = reticleEquatorial,
            )
        val farTarget =
            ArViewModel.ArStar(
                id = 2,
                label = "Far",
                magnitude = 1.0,
                equatorial = Equatorial(
                    raDeg = (reticleEquatorial.raDeg + 20.0) % 360.0,
                    decDeg = reticleEquatorial.decDeg - 15.0,
                ),
            )

        val state =
            ArUiState.Ready(
                instant = instant,
                location = location,
                locationResolved = true,
                lstDeg = lstDeg,
                stars = listOf(target, farTarget),
            )

        val frame =
            RotationFrame(
                rotationMatrix = identityMatrix(),
                forwardWorld = horizontalToVector(reticleHorizontal),
                timestampNanos = 0L,
            )

        val overlay = calculateOverlay(state, frame, IntSize(1080, 1080))

        val result = assertNotNull(overlay)
        assertEquals(reticleHorizontal.azDeg, result.reticleHorizontal.azDeg, 1e-9)
        assertEquals(reticleHorizontal.altDeg, result.reticleHorizontal.altDeg, 1e-9)
        assertEquals(reticleEquatorial.raDeg, result.reticleEquatorial.raDeg, 1e-9)
        assertEquals(reticleEquatorial.decDeg, result.reticleEquatorial.decDeg, 1e-9)

        val nearest = assertNotNull(result.nearestLabel)
        assertEquals("Target", nearest.title)
        assertTrue(abs(nearest.separationDeg) < 1e-3)
        assertTrue(nearest.position.x in 0f..1080f)
        assertTrue(nearest.position.y in 0f..1080f)

        // Ensure that the farther star remains visible but is ranked behind the primary target.
        assertEquals(2, result.labels.size)
        assertEquals("Far", result.labels[1].title)
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
