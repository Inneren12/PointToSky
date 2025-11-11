package dev.pointtosky.wear.astro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.aim.AimError
import dev.pointtosky.core.astro.aim.AimTolerance
import dev.pointtosky.core.astro.aim.aimError
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyObjectOrConstellation
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationFrameDefaults
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

private const val ORIENTATION_SAMPLE_MS = 100L
private const val UI_IDLE_STOP_TIMEOUT_MS = 5_000L

class AstroDebugViewModel(
    orientationRepository: OrientationRepository,
    private val locationRepository: LocationRepository,
    private val ephemerisComputer: SimpleEphemerisComputer,
    private val identifySolver: IdentifySolver,
    private val constellations: ConstellationBoundaries,
    private val timeSource: SystemTimeSource = SystemTimeSource(periodMs = 200L),
) : ViewModel() {

    private val locationState = MutableStateFlow<LocationFix?>(null)
    private val targetState = MutableStateFlow(Body.SUN)

    @OptIn(FlowPreview::class)
    private val orientationState: StateFlow<OrientationFrame> = orientationRepository.frames
        .sample(ORIENTATION_SAMPLE_MS)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_IDLE_STOP_TIMEOUT_MS),
            OrientationFrameDefaults.EMPTY,
        )

    init {
        viewModelScope.launch {
            locationRepository.getLastKnown()?.let { fix ->
                locationState.value = fix
            }
        }
        viewModelScope.launch {
            locationRepository.fixes.collect { fix ->
                locationState.value = fix
            }
        }
    }

    val uiState: StateFlow<AstroDebugUiState> = combine(
        orientationState,
        locationState,
        timeSource.ticks,
        targetState,
    ) { frame, locationFix, instant, target ->
        computeState(frame, locationFix, instant, target)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = UI_IDLE_STOP_TIMEOUT_MS),
        AstroDebugUiState.Empty,
    )

    fun selectTarget(body: Body) {
        targetState.value = body
    }

    private fun computeState(
        frame: OrientationFrame,
        locationFix: LocationFix?,
        instant: Instant,
        target: Body
    ): AstroDebugUiState {
        val horizontal = frame.toHorizontal()
        val locationPoint = locationFix?.point
        if (locationPoint == null) {
            return AstroDebugUiState(
                lstDeg = null,
                lstHms = null,
                horizontal = horizontal,
                equatorial = null,
                bestMatch = null,
                target = target,
                aimError = null,
            )
        }

        val lst = lstAt(instant, locationPoint.lonDeg)
        val lstDeg = lst.lstDeg
        val lstHms = formatSiderealAsHms(lstDeg)
        val equatorial = altAzToRaDec(horizontal, lstDeg, locationPoint.latDeg)
        val bestMatch = computeBestMatch(equatorial)
        val targetEquatorial = ephemerisComputer.compute(target, instant).eq
        val targetHorizontal = raDecToAltAz(targetEquatorial, lstDeg, locationPoint.latDeg, applyRefraction = false)
        val aimError = aimError(horizontal, targetHorizontal, AimTolerance())

        return AstroDebugUiState(
            lstDeg = lstDeg,
            lstHms = lstHms,
            horizontal = horizontal,
            equatorial = equatorial,
            bestMatch = bestMatch,
            target = target,
            aimError = aimError,
        )
    }

    private fun OrientationFrame.toHorizontal(): Horizontal {
        val east = forward.getOrNull(0)?.toDouble() ?: 0.0
        val north = forward.getOrNull(1)?.toDouble() ?: 0.0
        val up = forward.getOrNull(2)?.toDouble() ?: 1.0
        val upClamped = up.coerceIn(-1.0, 1.0)
        val altitudeDeg = radToDeg(asin(upClamped))
        val azimuthRad = atan2(east, north)
        val azimuthDeg = wrapDeg0To360(radToDeg(azimuthRad))
        return Horizontal(azimuthDeg, altitudeDeg)
    }

    private fun computeBestMatch(equatorial: Equatorial): AstroBestMatch? {
        return when (val result = identifySolver.findBest(equatorial)) {
            is SkyObjectOrConstellation.Object -> {
                val obj = result.obj
                AstroBestMatch.Object(
                    label = obj.name ?: obj.id,
                    magnitude = obj.mag,
                    constellationCode = constellations.findByEq(obj.eq),
                )
            }
            is SkyObjectOrConstellation.Constellation -> {
                AstroBestMatch.Constellation(result.iauCode)
            }
        }
    }

    private fun formatSiderealAsHms(lstDeg: Double): String {
        val totalSeconds = ((lstDeg / 15.0) * 3600.0).roundToInt() % 86400
        val normalizedSeconds = if (totalSeconds < 0) totalSeconds + 86400 else totalSeconds
        val hours = normalizedSeconds / 3600
        val minutes = (normalizedSeconds % 3600) / 60
        val seconds = normalizedSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

data class AstroDebugUiState(
    val lstDeg: Double?,
    val lstHms: String?,
    val horizontal: Horizontal,
    val equatorial: Equatorial?,
    val bestMatch: AstroBestMatch?,
    val target: Body,
    val aimError: AimError?,
) {
    companion object {
        val Empty = AstroDebugUiState(
            lstDeg = null,
            lstHms = null,
            horizontal = Horizontal(0.0, 0.0),
            equatorial = null,
            bestMatch = null,
            target = Body.SUN,
            aimError = null,
        )
    }
}

sealed interface AstroBestMatch {
    data class Object(
        val label: String?,
        val magnitude: Double?,
        val constellationCode: String?,
    ) : AstroBestMatch

    data class Constellation(val code: String) : AstroBestMatch
}
