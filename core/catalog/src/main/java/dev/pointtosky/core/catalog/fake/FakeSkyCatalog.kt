package dev.pointtosky.core.catalog.fake

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.SkyCatalog
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.astro.identify.Type

object FakeSkyCatalog : SkyCatalog {
    val placeholder: SkyObject = SkyObject(
        id = "debug-star",
        name = "Debug Star",
        eq = Equatorial(0.0, 0.0),
        mag = 1.0,
        type = Type.STAR,
    )

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<SkyObject> {
        val withinRadius = radiusDeg >= 0
        val passesMagnitude = magLimit?.let { placeholder.mag?.let { mag -> mag <= it } ?: true } ?: true
        return if (withinRadius && passesMagnitude) listOf(placeholder) else emptyList()
    }
}

object FakeConstellationBoundaries : ConstellationBoundaries {
    override fun findByEq(eq: Equatorial): String? = null
}
