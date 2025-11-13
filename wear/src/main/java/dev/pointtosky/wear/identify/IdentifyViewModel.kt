package dev.pointtosky.wear.identify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyObjectOrConstellation
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationFrameDefaults
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val ORIENTATION_SAMPLE_MS = 200L
private const val UI_IDLE_STOP_TIMEOUT_MS = 5_000L

enum class IdentifyType { STAR, CONST, PLANET, MOON }

data class IdentifyUiState(
    val title: String,
    val type: IdentifyType,
    val magnitude: Double?,
    val separationDeg: Double?,
    val lowAccuracy: Boolean,
    // payload-friendly extras
    // for STAR
    val objectId: String? = null,
    // for STAR (static RA/Dec)
    val objectEq: Equatorial? = null,
    // for PLANET / MOON
    val body: Body? = null,
    // for CONST
    val constellationIau: String? = null,
) {
    companion object {
        val Empty =
            IdentifyUiState(
                title = "—",
                type = IdentifyType.CONST,
                magnitude = null,
                separationDeg = null,
                lowAccuracy = true,
                objectId = null,
                objectEq = null,
                body = null,
                constellationIau = null,
            )
    }
}

class IdentifyViewModel(
    orientationRepository: OrientationRepository,
    private val locationRepository: LocationRepository,
    private val identifySolver: IdentifySolver,
    private val constellations: ConstellationBoundaries,
    private val ephemeris: SimpleEphemerisComputer = SimpleEphemerisComputer(),
    @Suppress("unused")
    private val timeSource: dev.pointtosky.core.time.SystemTimeSource =
        dev.pointtosky.core.time
            .SystemTimeSource(periodMs = 200L),
    @Suppress("unused")
    private val settings: AimIdentifySettingsDataStore,
) : ViewModel() {
    private val locationState = MutableStateFlow<LocationFix?>(null)

    @OptIn(FlowPreview::class)
    private val orientationState: StateFlow<OrientationFrame> =
        orientationRepository.frames
            .sample(ORIENTATION_SAMPLE_MS)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_IDLE_STOP_TIMEOUT_MS),
                OrientationFrameDefaults.EMPTY,
            )

    init {
        viewModelScope.launch {
            locationRepository.getLastKnown()?.let { fix -> locationState.value = fix }
        }
        viewModelScope.launch {
            locationRepository.fixes.collect { fix -> locationState.value = fix }
        }
    }

    val uiState: StateFlow<IdentifyUiState> =
        combine(
            orientationState,
            locationState,
            timeSource.ticks,
            settings.identifyMagLimitFlow,
            settings.identifyRadiusDegFlow,
        ) { frame, locationFix, instant, magLimit, radiusDeg ->
            computeState(frame, locationFix, instant, magLimit, radiusDeg)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_IDLE_STOP_TIMEOUT_MS),
            IdentifyUiState.Empty,
        )

    // Лог результатов распознавания с антиспамом (distinct по сигнатуре)
    init {
        uiState
            .map { it.toLogPayloadOrNull() }
            .distinctUntilChanged()
            .onEach { payload ->
                if (payload != null) {
                    LogBus.d(
                        tag = "Identify",
                        msg = "identify_result",
                        payload = payload,
                    )
                }
            }.launchIn(viewModelScope)
    }

    private fun computeState(
        frame: OrientationFrame,
        locationFix: LocationFix?,
        instant: Instant,
        magLimit: Double,
        radiusDeg: Double,
    ): IdentifyUiState {
        val lowAccuracy =
            frame.accuracy == OrientationAccuracy.UNRELIABLE ||
                frame.accuracy == OrientationAccuracy.LOW
        val locationPoint = locationFix?.point ?: return missingLocationState()

        val horizontal = frame.toHorizontal()
        val lstDeg = lstAt(instant, locationPoint.lonDeg).lstDeg
        val equatorial = altAzToRaDec(horizontal, lstDeg, locationPoint.latDeg)

        val result = identifySolver.findBest(equatorial)
        val star = starCandidateFromResult(result, equatorial, magLimit, radiusDeg)
        val body = nearestBody(equatorial, instant)

        return when (pickWinner(star, body)) {
            Winner.STAR -> star?.let { stateForStar(it, lowAccuracy) } ?: stateForConst(equatorial, lowAccuracy)
            Winner.BODY -> body?.let { stateForBody(it, lowAccuracy) } ?: stateForConst(equatorial, lowAccuracy)
            Winner.CONST -> stateForConst(equatorial, lowAccuracy)
        }
    }

    // --- decomposition helpers (уменьшают сложность computeState) ---
    private data class StarCandidate(
        val label: String,
        val id: String,
        val eq: Equatorial,
        val mag: Double,
        val sep: Double,
    )

    private data class BodyCandidate(
        val body: Body,
        val eq: Equatorial,
        val sep: Double,
    )

    private enum class Winner { STAR, BODY, CONST }

    private fun starCandidateFromResult(
        result: SkyObjectOrConstellation,
        eq: Equatorial,
        magLimit: Double,
        radiusDeg: Double,
    ): StarCandidate? =
        when (result) {
            is SkyObjectOrConstellation.Object -> {
                val obj = result.obj
                val mag = obj.mag ?: return null
                val sep = separationDeg(eq, obj.eq)
                if (mag <= magLimit && sep <= radiusDeg) {
                    StarCandidate(label = obj.name ?: obj.id, id = obj.id, eq = obj.eq, mag = mag, sep = sep)
                } else {
                    null
                }
            }
            is SkyObjectOrConstellation.Constellation -> null
        }

    private fun nearestBody(
        eq: Equatorial,
        instant: Instant,
    ): BodyCandidate? =
        listOf(Body.MOON, Body.JUPITER, Body.SATURN)
            .minByOrNull { body ->
                val bEq = ephemeris.compute(body, instant).eq
                separationDeg(eq, bEq)
            }?.let { body ->
                val bEq = ephemeris.compute(body, instant).eq
                BodyCandidate(body = body, eq = bEq, sep = separationDeg(eq, bEq))
            }

    private fun pickWinner(
        star: StarCandidate?,
        body: BodyCandidate?,
    ): Winner =
        when {
            star != null && body != null -> if (body.sep < star.sep) Winner.BODY else Winner.STAR
            star != null -> Winner.STAR
            body != null -> Winner.BODY
            else -> Winner.CONST
        }

    private fun stateForStar(
        s: StarCandidate,
        lowAccuracy: Boolean,
    ) = IdentifyUiState(
        title = s.label,
        type = IdentifyType.STAR,
        magnitude = s.mag,
        separationDeg = s.sep,
        lowAccuracy = lowAccuracy,
        objectId = s.id,
        objectEq = s.eq,
        body = null,
        constellationIau = null,
    )

    private fun stateForBody(
        b: BodyCandidate,
        lowAccuracy: Boolean,
    ): IdentifyUiState {
        val t = if (b.body == Body.MOON) IdentifyType.MOON else IdentifyType.PLANET
        return IdentifyUiState(
            title = b.body.name,
            type = t,
            magnitude = null,
            separationDeg = b.sep,
            lowAccuracy = lowAccuracy,
            objectId = null,
            objectEq = null,
            body = b.body,
            constellationIau = null,
        )
    }

    private fun stateForConst(
        eq: Equatorial,
        lowAccuracy: Boolean,
    ): IdentifyUiState {
        val iau = constellations.findByEq(eq)
        return IdentifyUiState(
            title = iau ?: "—",
            type = IdentifyType.CONST,
            magnitude = null,
            separationDeg = null,
            lowAccuracy = lowAccuracy,
            objectId = null,
            objectEq = null,
            body = null,
            constellationIau = iau,
        )
    }

    private fun missingLocationState() =
        IdentifyUiState(
            title = "—",
            type = IdentifyType.CONST,
            magnitude = null,
            separationDeg = null,
            lowAccuracy = true,
            objectId = null,
            objectEq = null,
            body = null,
            constellationIau = null,
        )

    // --- helpers for logging ---
    private fun IdentifyUiState.toLogPayloadOrNull(): Map<String, Any?>? {
        val typeStr =
            when (type) {
                IdentifyType.STAR -> "STAR"
                IdentifyType.PLANET -> "PLANET"
                IdentifyType.MOON -> "MOON"
                IdentifyType.CONST -> "CONST"
            }
        val idOrName: String? =
            when (type) {
                IdentifyType.STAR -> objectId // для звезды ждём ID
                IdentifyType.PLANET -> body?.name // планеты/луна — имя тела
                IdentifyType.MOON -> body?.name
                IdentifyType.CONST ->
                    constellationIau
                        ?: title.takeUnless { it.isBlank() || it == "—" } // пропустить заглушку
            }
        if (idOrName == null) return null
        val payload =
            mutableMapOf<String, Any?>(
                "id" to idOrName,
                "type" to typeStr,
                "name" to title,
                "sepDeg" to separationDeg,
            )
        if (magnitude != null) payload["mag"] = magnitude
        return payload
    }
}

// --- helpers ---

private fun OrientationFrame.toHorizontal(): Horizontal {
    // Такой же расчёт, как в AstroDebugViewModel: east/north/up → az/alt
    val east = forward.getOrNull(0)?.toDouble() ?: 0.0
    val north = forward.getOrNull(1)?.toDouble() ?: 0.0
    val up = forward.getOrNull(2)?.toDouble() ?: 1.0
    val upClamped = up.coerceIn(-1.0, 1.0)
    val altitudeDeg = radToDeg(asin(upClamped))
    val azimuthRad = atan2(east, north)
    val azimuthDeg = wrapDeg0To360(radToDeg(azimuthRad))
    return Horizontal(azimuthDeg, altitudeDeg)
}

private fun separationDeg(
    a: Equatorial,
    b: Equatorial,
): Double {
    // Большая окружность по сферической косинусной теореме
    val ra1 = degToRad(a.raDeg)
    val ra2 = degToRad(b.raDeg)
    val dRa = ra1 - ra2
    val d1 = degToRad(a.decDeg)
    val d2 = degToRad(b.decDeg)
    val cosSep = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
    val clamped = cosSep.coerceIn(-1.0, 1.0)
    return radToDeg(kotlin.math.acos(clamped))
}

@Suppress("unused")
private fun separationDeg(
    h1: Horizontal,
    h2: Horizontal,
): Double {
    // Альтернативный расчёт через горизонтальные координаты (не используется сейчас)
    val az1 = degToRad(h1.azDeg)
    val alt1 = degToRad(h1.altDeg)
    val az2 = degToRad(h2.azDeg)
    val alt2 = degToRad(h2.altDeg)
    val cosSep = sin(alt1) * sin(alt2) + cos(alt1) * cos(alt2) * cos(az1 - az2)
    val clamped = cosSep.coerceIn(-1.0, 1.0)
    return radToDeg(kotlin.math.acos(clamped))
}
