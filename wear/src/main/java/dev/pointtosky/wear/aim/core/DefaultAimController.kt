package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0_360
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

class DefaultAimController(
    private val orientation: OrientationRepository,
    private val location: dev.pointtosky.core.location.api.LocationOrchestrator,
    private val time: TimeSource,
    private val ephem: dev.pointtosky.core.astro.ephem.EphemerisComputer,
    private val raDecToAltAz: (Equatorial, lstDeg: Double, latDeg: Double) -> Horizontal,
) : AimController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        AimState(
            current = Horizontal(0.0, 0.0),
            target = Horizontal(0.0, 0.0),
            dAzDeg = 0.0,
            dAltDeg = 0.0,
            phase = AimPhase.SEARCHING,
            confidence = 0f,
        ),
    )

    override val state: StateFlow<AimState> = _state

    @Volatile
    private var target: AimTarget? = null

    @Volatile
    private var tolerance: AimTolerance = AimTolerance()

    @Volatile
    private var holdToLockMs: Long = DEFAULT_HOLD_TO_LOCK_MS

    @Volatile
    private var lastLocation: GeoPoint? = null

    // AtomicBoolean сам обеспечивает корректную видимость/атомарность; @Volatile не требуется
    private val running = AtomicBoolean(false)

    private var orientationJob: Job? = null
    private var locationJob: Job? = null

    private var lastUpdateTimeMs: Long = Long.MIN_VALUE

    private var currentPhase: AimPhase = AimPhase.SEARCHING
    private var inToleranceSinceMs: Long = Long.MIN_VALUE

    private var lastTarget: Horizontal? = null

    private val azWindowTimesMs = LongArray(CONFIDENCE_WINDOW_CAPACITY)
    private val azWindowSin = DoubleArray(CONFIDENCE_WINDOW_CAPACITY)
    private val azWindowCos = DoubleArray(CONFIDENCE_WINDOW_CAPACITY)
    private var azWindowSize = 0
    private var azWindowStart = 0
    private var sumSin = 0.0
    private var sumCos = 0.0

    override fun setTarget(target: AimTarget) {
        this.target = target
    }

    override fun setTolerance(t: AimTolerance) {
        tolerance = t
    }

    override fun setHoldToLockMs(ms: Long) {
        require(ms >= 0) { "hold to lock must be non-negative" }
        holdToLockMs = ms
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        locationJob = scope.launch {
            val known = location.getLastKnown()
            if (known != null) {
                updateLocation(known)
            }
            location.fixes.collect { fix ->
                updateLocation(fix)
            }
        }

        orientationJob = scope.launch {
            orientation.frames.collect { frame ->
                handleFrame(frame)
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        // Захватываем ссылки на текущие задачи и сразу очищаем поля
        val oldOrientation = orientationJob
        val oldLocation = locationJob
        orientationJob = null
        locationJob = null

        // Отменяем старые задачи немедленно
        oldOrientation?.cancel()
        oldLocation?.cancel()

        // Дожидаемся завершения именно старых задач — без гонок с новым start()
        scope.launch {
            oldOrientation?.join()
            oldLocation?.join()
        }

        resetState()
    }

    private fun handleFrame(frame: OrientationFrame) {
        val timestampMs = frame.timestampNanos / 1_000_000L
        if (lastUpdateTimeMs != Long.MIN_VALUE) {
            val deltaMs = timestampMs - lastUpdateTimeMs
            if (deltaMs in 0 until MIN_UPDATE_INTERVAL_MS) {
                return
            }
        }
        lastUpdateTimeMs = timestampMs

        val currentHorizontal = frame.toHorizontal()
        addAzSample(currentHorizontal.azDeg, timestampMs)

        val locationPoint = lastLocation
        val targetHorizontal = computeTargetHorizontal(locationPoint)
        if (targetHorizontal != null) {
            lastTarget = targetHorizontal
        }

        val targetForState = targetHorizontal ?: lastTarget ?: currentHorizontal
        val diffAz = normalizeAzimuthDelta(targetForState.azDeg - currentHorizontal.azDeg)
        val diffAlt = targetForState.altDeg - currentHorizontal.altDeg

        val tol = tolerance
        val hasTarget = targetHorizontal != null
        val withinTolerance = hasTarget &&
            abs(diffAz) <= tol.azDeg &&
            abs(diffAlt) <= tol.altDeg

        val newPhase = computePhase(withinTolerance, timestampMs)
        val confidence = computeConfidence(frame.accuracy)

        _state.value = AimState(
            current = currentHorizontal,
            target = targetForState,
            dAzDeg = diffAz,
            dAltDeg = diffAlt,
            phase = newPhase,
            confidence = confidence,
        )
    }

    private fun computePhase(withinTolerance: Boolean, timestampMs: Long): AimPhase {
        if (!withinTolerance) {
            inToleranceSinceMs = Long.MIN_VALUE
            currentPhase = AimPhase.SEARCHING
            return currentPhase
        }

        if (inToleranceSinceMs == Long.MIN_VALUE) {
            inToleranceSinceMs = timestampMs
        }

        val elapsed = timestampMs - inToleranceSinceMs
        currentPhase = if (elapsed >= holdToLockMs) {
            AimPhase.LOCKED
        } else {
            AimPhase.IN_TOLERANCE
        }
        return currentPhase
    }

    private fun computeConfidence(accuracy: OrientationAccuracy): Float {
        val base = when (accuracy) {
            OrientationAccuracy.UNRELIABLE -> 0.1f
            OrientationAccuracy.LOW -> 0.4f
            OrientationAccuracy.MEDIUM -> 0.7f
            OrientationAccuracy.HIGH -> 1.0f
        }

        val stdDevDeg = currentAzimuthStdDevDeg()
        val stability = 1.0 - min(stdDevDeg / MAX_STD_DEV_DEG, 1.0)
        val confidence = base * stability.toFloat()
        return confidence.coerceIn(0f, 1f)
    }

    private fun currentAzimuthStdDevDeg(): Double {
        val count = azWindowSize
        if (count <= 1) {
            return 0.0
        }
        val meanSin = sumSin / count
        val meanCos = sumCos / count
        val r = sqrt(meanSin * meanSin + meanCos * meanCos)
        val safeR = r.coerceIn(MIN_RESULTANT_LENGTH, 1.0)
        val stdRad = sqrt(max(0.0, -2.0 * ln(safeR)))
        return radToDeg(stdRad)
    }

    private fun OrientationFrame.toHorizontal(): Horizontal {
        val forwardVec = forward
        val east = forwardVec.getOrNull(0)?.toDouble() ?: 0.0
        val north = forwardVec.getOrNull(1)?.toDouble() ?: 0.0
        val up = forwardVec.getOrNull(2)?.toDouble() ?: 1.0
        val upClamped = up.coerceIn(-1.0, 1.0)
        val altitudeDeg = radToDeg(asin(upClamped))
        val azimuthRad = atan2(east, north)
        val azimuthDeg = wrapDeg0_360(radToDeg(azimuthRad))
        return Horizontal(azDeg = azimuthDeg, altDeg = altitudeDeg)
    }

    private fun computeTargetHorizontal(locationPoint: GeoPoint?): Horizontal? {
        val currentTarget = target
        val loc = locationPoint ?: return null
        val instant: Instant = time.now()
        val lstDeg = lstAt(instant, loc.lonDeg).lstDeg
        val latDeg = loc.latDeg

        val eq = when (currentTarget) {
            is AimTarget.EquatorialTarget -> currentTarget.eq
            is AimTarget.BodyTarget -> ephem.compute(currentTarget.body, instant).eq
            null -> return null
        }

        return raDecToAltAz(eq, lstDeg, latDeg)
    }

    private fun addAzSample(azDeg: Double, timestampMs: Long) {
        pruneOldSamples(timestampMs)

        if (azWindowSize == CONFIDENCE_WINDOW_CAPACITY) {
            removeOldestSample()
        }

        val azRad = degToRad(wrapDeg0_360(azDeg))
        val sinVal = sin(azRad)
        val cosVal = cos(azRad)

        val insertIndex = (azWindowStart + azWindowSize) % CONFIDENCE_WINDOW_CAPACITY
        azWindowTimesMs[insertIndex] = timestampMs
        azWindowSin[insertIndex] = sinVal
        azWindowCos[insertIndex] = cosVal
        azWindowSize += 1
        sumSin += sinVal
        sumCos += cosVal
    }

    private fun pruneOldSamples(currentTimeMs: Long) {
        while (azWindowSize > 0) {
            val index = azWindowStart
            val age = currentTimeMs - azWindowTimesMs[index]
            if (age <= CONFIDENCE_WINDOW_MS) {
                break
            }
            removeOldestSample()
        }
    }

    private fun removeOldestSample() {
        if (azWindowSize == 0) {
            return
        }
        val index = azWindowStart
        sumSin -= azWindowSin[index]
        sumCos -= azWindowCos[index]
        azWindowStart = (azWindowStart + 1) % CONFIDENCE_WINDOW_CAPACITY
        azWindowSize -= 1
    }

    private fun normalizeAzimuthDelta(deltaDeg: Double): Double {
        var normalized = deltaDeg % 360.0
        if (normalized < -180.0) {
            normalized += 360.0
        } else if (normalized > 180.0) {
            normalized -= 360.0
        }
        return normalized
    }

    private fun updateLocation(fix: LocationFix) {
        lastLocation = GeoPoint(fix.point.latDeg, fix.point.lonDeg)
    }

    private fun resetState() {
        lastUpdateTimeMs = Long.MIN_VALUE
        currentPhase = AimPhase.SEARCHING
        inToleranceSinceMs = Long.MIN_VALUE
        azWindowSize = 0
        azWindowStart = 0
        sumSin = 0.0
        sumCos = 0.0
        val resetTarget = lastTarget ?: Horizontal(0.0, 0.0)
        _state.value = AimState(
            current = Horizontal(0.0, 0.0),
            target = resetTarget,
            dAzDeg = 0.0,
            dAltDeg = 0.0,
            phase = AimPhase.SEARCHING,
            confidence = 0f,
        )
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 66L
        private const val CONFIDENCE_WINDOW_MS = 1_500L
        private const val CONFIDENCE_WINDOW_CAPACITY = 32
        private const val MAX_STD_DEV_DEG = 10.0
        private const val MIN_RESULTANT_LENGTH = 1e-6
        private const val DEFAULT_HOLD_TO_LOCK_MS = 1_200L
    }
}
