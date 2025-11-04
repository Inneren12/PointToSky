package dev.pointtosky.core.astro.identify

import dev.pointtosky.core.astro.coord.Equatorial

class FakeSkyCatalog : SkyCatalog {
    private val objects = listOf(
        SkyObject("vega", "Vega", Equatorial(279.234, 38.783), mag = 0.03, type = Type.STAR),
        SkyObject("arcturus", "Arcturus", Equatorial(213.915, 19.182), mag = -0.05, type = Type.STAR),
        SkyObject("capella", "Capella", Equatorial(79.172, 45.997), mag = 0.08, type = Type.STAR),
        SkyObject("betelgeuse", "Betelgeuse", Equatorial(88.793, 7.407), mag = 0.42, type = Type.STAR),
        SkyObject("rigel", "Rigel", Equatorial(78.634, -8.205), mag = 0.18, type = Type.STAR),
        SkyObject("sirius", "Sirius", Equatorial(101.287, -16.716), mag = -1.46, type = Type.STAR),
        SkyObject("procyon", "Procyon", Equatorial(114.825, 5.225), mag = 0.40, type = Type.STAR),
        SkyObject("altair", "Altair", Equatorial(297.695, 8.868), mag = 0.77, type = Type.STAR),
        SkyObject("deneb", "Deneb", Equatorial(310.358, 45.280), mag = 1.25, type = Type.STAR),
        SkyObject("spica", "Spica", Equatorial(201.298, -11.161), mag = 0.98, type = Type.STAR),
        SkyObject("antares", "Antares", Equatorial(247.351, -26.432), mag = 1.06, type = Type.STAR),
        SkyObject("polaris", "Polaris", Equatorial(37.954, 89.264), mag = 1.97, type = Type.STAR),
    )

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<SkyObject> {
        return objects.filter { obj ->
            val separation = angularSeparationDeg(center, obj.eq)
            val passesRadius = separation <= radiusDeg
            val passesMagnitude = magLimit?.let { limit -> obj.mag?.let { it <= limit } ?: false } ?: true
            passesRadius && passesMagnitude
        }
    }
}

class FakeConstellations : ConstellationBoundaries {
    private data class Box(val raMin: Double, val raMax: Double, val decMin: Double, val decMax: Double, val code: String)

    private val boxes = listOf(
        Box(260.0, 300.0, 20.0, 60.0, "LYR"),
        Box(270.0, 320.0, 0.0, 50.0, "CYG"),
        Box(0.0, 60.0, -70.0, -30.0, "TUC"),
        Box(60.0, 120.0, -40.0, 10.0, "CMA"),
    )

    override fun findByEq(eq: Equatorial): String? {
        return boxes.firstOrNull { box ->
            eq.raDeg in box.raMin..box.raMax && eq.decDeg in box.decMin..box.decMax
        }?.code
    }
}
