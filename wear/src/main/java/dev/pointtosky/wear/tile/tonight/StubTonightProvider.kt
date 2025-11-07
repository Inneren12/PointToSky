package dev.pointtosky.wear.tile.tonight

import android.content.Context
import java.time.Instant

/**
 * Временный фейковый провайдер для тайла (S7.A).
 */
class StubTonightProvider(
    private val context: Context,
) : TonightProvider {
    override suspend fun getModel(now: Instant): TonightTileModel {
        // 2–3 фиктивные цели
        val items = listOf(
            TonightTarget(
                id = "MOON",
                title = "Moon",
                subtitle = "High near 23:10",
                icon = TonightIcon.MOON,
                azDeg = 130.0, altDeg = 45.0,
                windowStart = now, windowEnd = now.plusSeconds(3600),
            ),
            TonightTarget(
                id = "JUPITER",
                title = "Jupiter",
                subtitle = "Best around 01:30",
                icon = TonightIcon.JUPITER,
                azDeg = 180.0, altDeg = 35.0,
                windowStart = now.plusSeconds(1800), windowEnd = now.plusSeconds(5400),
            ),
            TonightTarget(
                id = "VEGA",
                title = "Vega",
                subtitle = "Lyra (m=0.0)",
                icon = TonightIcon.STAR,
                azDeg = 300.0, altDeg = 20.0,
            ),
        )
        return TonightTileModel(updatedAt = now, items = items)
    }
}
