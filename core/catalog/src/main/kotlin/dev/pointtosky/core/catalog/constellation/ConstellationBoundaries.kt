package dev.pointtosky.core.catalog.constellation

/**
 * Backward‑compatibility alias: старое имя теперь указывает на новый интерфейс
 * из core.astro.identify. В рантайме больше не будет двух разных типов.
 */
@Deprecated(
    message = "Use dev.pointtosky.core.astro.identify.ConstellationBoundaries directly",
    replaceWith = ReplaceWith("dev.pointtosky.core.astro.identify.ConstellationBoundaries"),
)
typealias ConstellationBoundaries =
    dev.pointtosky.core.astro.identify.ConstellationBoundaries
