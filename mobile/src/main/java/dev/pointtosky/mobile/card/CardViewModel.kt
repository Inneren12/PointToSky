package dev.pointtosky.mobile.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import dev.pointtosky.core.astro.visibility.limitingMagnitudeAt
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.datalayer.AimTargetOption
import dev.pointtosky.mobile.location.DeviceLocationRepository
import dev.pointtosky.mobile.visibility.BortleSource
import dev.pointtosky.mobile.visibility.LightPollutionProvider
import dev.pointtosky.mobile.visibility.VisibilitySettings
import dev.pointtosky.mobile.visibility.resolveEffectiveBortle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CardViewModel(
    private val cardId: String,
    private val repository: CardRepository,
    private val locationPrefs: LocationPrefs,
    private val deviceLocationFlow: StateFlow<GeoPoint?>,
    private val clock: () -> Instant = { Instant.now() },
    private val ephemerisComputer: EphemerisComputer = SimpleEphemerisComputer(),
    private val lightPollutionGrid: StateFlow<LightPollutionGrid?> = LightPollutionProvider.grid(),
) : ViewModel() {
    val state: StateFlow<CardUiState> =
        combine(
            repository.observe(cardId),
            deviceLocationFlow,
            locationPrefs.manualPointFlow,
            VisibilitySettings.enabled,
            VisibilitySettings.bortle,
            VisibilitySettings.bortleSource,
            lightPollutionGrid,
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val entry = values[0] as CardRepository.Entry?
            val devicePoint = values[1] as GeoPoint?
            val manualPoint = values[2] as GeoPoint?
            val enabled = values[3] as Boolean
            val manualBortle = values[4] as Bortle
            val bortleSource = values[5] as BortleSource
            val grid = values[6] as LightPollutionGrid?
            buildState(entry, manualPoint ?: devicePoint, enabled, manualBortle, bortleSource, grid)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CardUiState.Loading,
        )

    private fun buildState(
        entry: CardRepository.Entry?,
        resolvedPoint: GeoPoint?,
        enabled: Boolean,
        manualBortle: Bortle,
        bortleSource: BortleSource,
        grid: LightPollutionGrid?,
    ): CardUiState {
        val readyEntry =
            (entry as? CardRepository.Entry.Ready)?.model
                ?: return CardUiState.Error(CardErrorReason.NOT_ENOUGH_DATA)
        val equatorial = readyEntry.equatorial
        val instant = clock()
        val computedHorizontal: Horizontal? =
            if (equatorial != null && resolvedPoint != null) {
                computeHorizontal(equatorial, resolvedPoint, instant)
            } else {
                null
            }
        val horizontal = computedHorizontal ?: readyEntry.horizontal
        val (limitingMag, belowLimit) = if (resolvedPoint != null) {
            val eff = resolveEffectiveBortle(
                source = bortleSource,
                manual = manualBortle,
                locationResolved = true,
                latDeg = resolvedPoint.latDeg,
                lonDeg = resolvedPoint.lonDeg,
                grid = grid,
            )
            val lm = limitingMagnitudeAt(
                ephemerisComputer,
                instant,
                resolvedPoint.latDeg,
                resolvedPoint.lonDeg,
                eff.effective.darkNelm,
            )
            val below = enabled && readyEntry.magnitude != null && readyEntry.magnitude > lm
            lm to below
        } else {
            null to false
        }
        val title = titleFor(readyEntry)
        val shareText =
            buildShareText(
                title = title,
                type = readyEntry.type,
                magnitude = readyEntry.magnitude,
                equatorial = equatorial,
                horizontal = horizontal,
                bestWindow = readyEntry.bestWindow,
            )
        val targetOption = buildTargetOption(readyEntry, title, equatorial)
        return CardUiState.Ready(
            id = readyEntry.id,
            title = title,
            type = readyEntry.type,
            magnitude = readyEntry.magnitude,
            constellation = readyEntry.constellation,
            body = readyEntry.body,
            equatorial = equatorial,
            horizontal = horizontal,
            bestWindow = readyEntry.bestWindow,
            targetOption = targetOption,
            shareText = shareText,
            limitingMag = limitingMag,
            belowVisibilityLimit = belowLimit,
        )
    }

    private fun computeHorizontal(
        eq: Equatorial,
        point: GeoPoint,
        instant: Instant,
    ): Horizontal? =
        runCatching {
            val lst = lstAt(instant, point.lonDeg).lstDeg
            raDecToAltAz(eq, lst, point.latDeg, applyRefraction = false)
        }.getOrNull()

    private fun titleFor(model: CardObjectModel): String {
        val fallback = model.id
        return when (model.type) {
            CardObjectType.STAR -> model.name ?: fallback
            CardObjectType.PLANET, CardObjectType.MOON -> model.body ?: model.name ?: fallback
            CardObjectType.CONST -> model.name ?: model.constellation ?: fallback
        }
    }

    private fun buildTargetOption(
        model: CardObjectModel,
        label: String,
        equatorial: Equatorial?,
    ): AimTargetOption? =
        when (model.type) {
            CardObjectType.STAR ->
                equatorial?.let { eq ->
                    AimTargetOption(
                        id = model.id,
                        label = label,
                        buildMessage = { cid ->
                            AimSetTargetMessage(
                                cid = cid,
                                kind = AimTargetKind.EQUATORIAL,
                                payload =
                                    JsonCodec.encodeToElement(
                                        AimTargetEquatorialPayload(
                                            raDeg = eq.raDeg,
                                            decDeg = eq.decDeg,
                                        ),
                                    ),
                            )
                        },
                    )
                }
            CardObjectType.PLANET, CardObjectType.MOON ->
                model.body?.let { body ->
                    AimTargetOption(
                        id = model.id,
                        label = label,
                        buildMessage = { cid ->
                            AimSetTargetMessage(
                                cid = cid,
                                kind = AimTargetKind.BODY,
                                payload =
                                    JsonCodec.encodeToElement(
                                        AimTargetBodyPayload(body = body),
                                    ),
                            )
                        },
                    )
                }
            CardObjectType.CONST -> null
        }

    private fun buildShareText(
        title: String,
        type: CardObjectType,
        magnitude: Double?,
        equatorial: Equatorial?,
        horizontal: Horizontal?,
        bestWindow: CardBestWindow?,
    ): String {
        val locale = Locale.getDefault()
        val builder = StringBuilder()
        builder.append(title)
        builder.append(" (")
        builder.append(type.name)
        builder.append(')')
        if (magnitude != null) {
            builder.append("\n")
            builder.append("m = ")
            builder.append(String.format(locale, "%.1f", magnitude))
        }
        if (equatorial != null) {
            builder.append("\n")
            builder.append("RA = ")
            builder.append(String.format(locale, "%.1f°", equatorial.raDeg))
            builder.append(", Dec = ")
            builder.append(String.format(locale, "%.1f°", equatorial.decDeg))
        }
        if (horizontal != null) {
            builder.append("\n")
            builder.append("Alt = ")
            builder.append(String.format(locale, "%.1f°", horizontal.altDeg))
            builder.append(", Az = ")
            builder.append(String.format(locale, "%.1f°", horizontal.azDeg))
        }
        val windowText = bestWindow?.let { formatWindow(it) }
        if (!windowText.isNullOrBlank()) {
            builder.append("\n")
            builder.append(windowText)
        }
        return builder.toString()
    }

    private fun formatWindow(window: CardBestWindow): String? {
        val start = window.start
        val end = window.end
        if (start == null && end == null) return null
        val formatter =
            DateTimeFormatter
                .ofPattern("dd MMM HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
        val startText = start?.let { formatter.format(it) }
        val endText = end?.let { formatter.format(it) }
        return when {
            startText != null && endText != null -> "$startText – $endText"
            startText != null -> startText
            endText != null -> endText
            else -> null
        }
    }
}

class CardViewModelFactory(
    private val cardId: String,
    private val repository: CardRepository,
    private val locationPrefs: LocationPrefs,
    private val deviceLocationRepository: DeviceLocationRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CardViewModel::class.java)) {
            return CardViewModel(
                cardId = cardId,
                repository = repository,
                locationPrefs = locationPrefs,
                deviceLocationFlow = deviceLocationRepository.deviceLocationFlow,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}

sealed interface CardUiState {
    object Loading : CardUiState

    data class Error(
        val reason: CardErrorReason,
    ) : CardUiState

    data class Ready(
        val id: String,
        val title: String,
        val type: CardObjectType,
        val magnitude: Double?,
        val constellation: String?,
        val body: String?,
        val equatorial: Equatorial?,
        val horizontal: Horizontal?,
        val bestWindow: CardBestWindow?,
        val targetOption: AimTargetOption?,
        val shareText: String,
        val limitingMag: Double? = null,
        val belowVisibilityLimit: Boolean = false,
    ) : CardUiState
}

enum class CardErrorReason {
    NOT_ENOUGH_DATA,
}
