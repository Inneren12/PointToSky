package dev.pointtosky.core.astro.identify

import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdentifySolverTest {
    private val catalog = FakeSkyCatalog()
    private val constellations = FakeConstellations()
    private val solver = IdentifySolver(catalog, constellations)

    @Test
    fun `returns closest bright star`() {
        val center = Equatorial(279.3, 38.7)

        val result = solver.findBest(center)

        val objResult = assertIs<SkyObjectOrConstellation.Object>(result)
        assertEquals("vega", objResult.obj.id)
    }

    @Test
    fun `falls back to constellation when nothing nearby`() {
        val center = Equatorial(30.0, -50.0)

        val result = solver.findBest(center, searchRadiusDeg = 2.0, magLimit = 3.0)

        val constellationResult = assertIs<SkyObjectOrConstellation.Constellation>(result)
        assertEquals("TUC", constellationResult.iauCode)
    }
}
