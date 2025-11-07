package dev.pointtosky.wear.tile.tonight

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Временный фейковый провайдер для тайла (S7.A).
 */
class StubTonightProvider(
    private val context: Context,
) : TonightProvider {
    override suspend fun getModel(now: Instant): TonightTileModel {
        // Формат HH:mm по локали устройства
        val locale = Locale.getDefault()
        val zone = ZoneId.systemDefault()
        val hhmm = DateTimeFormatter.ofPattern("HH:mm", locale)
        fun range(s: Instant, e: Instant): String {
            val a = s.atZone(zone).format(hhmm)
            val b = e.atZone(zone).format(hhmm)
            return "$a–$b"
        }

        // 2–3 фиктивные цели
        val items = listOf(
            TonightTarget(
                id = "MOON",
                title = "Moon",
                subtitle = range(now, now.plusSeconds(3600)),
                icon = TonightIcon.MOON,
                azDeg = 130.0, altDeg = 45.0,
                windowStart = now, windowEnd = now.plusSeconds(3600),
            ),
            TonightTarget(
                id = "JUPITER",
                title = "Jupiter",
                subtitle = range(now.plusSeconds(1800), now.plusSeconds(5400)),
                icon = TonightIcon.JUPITER,
                azDeg = 180.0, altDeg = 35.0,
                windowStart = now.plusSeconds(1800), windowEnd = now.plusSeconds(5400),
            ),
            TonightTarget(
                id = "VEGA",
                title = "Vega",
                subtitle = "alt 20°",
                icon = TonightIcon.STAR,
                azDeg = 300.0, altDeg = 20.0,
            ),
        )
        return TonightTileModel(updatedAt = now, items = items)
    }
}
