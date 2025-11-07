package dev.pointtosky.wear.tile.tonight

import java.time.Instant

enum class TonightIcon { SUN, MOON, JUPITER, SATURN, STAR, CONST }

data class TonightTarget(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: TonightIcon,
    val azDeg: Double? = null,
    val altDeg: Double? = null,
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
)

data class TonightTileModel(
    val updatedAt: Instant,
    val items: List<TonightTarget>,
)

interface TonightProvider {
    suspend fun getModel(now: Instant): TonightTileModel
}
