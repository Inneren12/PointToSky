package dev.pointtosky.mobile.card

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.datalayer.EquatorialDto
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class CardObjectPayload(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val constellation: String? = null,
    @SerialName("mag")
    private val magnitude: Double? = null,
    @SerialName("m")
    private val magnitudeAlt: Double? = null,
    val body: String? = null,
    val eq: EquatorialDto? = null,
    val horizontal: CardHorizontalPayload? = null,
    val bestWindow: CardBestWindowPayload? = null,
) {
    val magnitudeValue: Double? get() = magnitude ?: magnitudeAlt
}

@Serializable
internal data class CardHorizontalPayload(
    val azDeg: Double? = null,
    val altDeg: Double? = null,
)

@Serializable
internal data class CardBestWindowPayload(
    @SerialName("startEpochMs")
    val startEpochMs: Long? = null,
    @SerialName("endEpochMs")
    val endEpochMs: Long? = null,
)

internal fun CardObjectPayload.toEntry(fallbackId: String?): CardRepository.Entry {
    val normalizedId = (id ?: fallbackId)?.takeIf { it.isNotBlank() }
        ?: return CardRepository.Entry.Invalid("missing_id")
    val typeEnum = CardObjectType.fromRaw(type)
        ?: return CardRepository.Entry.Invalid("unknown_type")
    val equatorial = eq?.let { Equatorial(it.raDeg, it.decDeg) }
    val horizontal = horizontal?.let { payload ->
        val az = payload.azDeg
        val alt = payload.altDeg
        if (az != null && alt != null) Horizontal(az, alt) else null
    }
    val window = bestWindow?.let { payload ->
        val start = payload.startEpochMs?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        val end = payload.endEpochMs?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
        if (start != null || end != null) CardBestWindow(start, end) else null
    }
    val model = CardObjectModel(
        id = normalizedId,
        type = typeEnum,
        name = name,
        body = body,
        constellation = constellation,
        magnitude = magnitudeValue,
        equatorial = equatorial,
        horizontal = horizontal,
        bestWindow = window,
    )
    return CardRepository.Entry.Ready(model)
}

internal enum class CardObjectType {
    STAR,
    PLANET,
    MOON,
    CONST,
    ;

    companion object {
        fun fromRaw(raw: String?): CardObjectType? {
            if (raw.isNullOrBlank()) return null
            return values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}
