package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.aim.AimDelta
import dev.pointtosky.core.astro.aim.aimDelta
import dev.pointtosky.core.astro.aim.forwardVectorToHorizontal
import dev.pointtosky.core.astro.aim.toTrueNorth
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.coord.polarisJ2000
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.wear.sensors.orientation.MagneticDeclinationProvider
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.Math.toRadians
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Минимально жизнеспособная реализация Aim-ядра.
 * Без внешних зависимостей на UI/DI, только вычисления.
 */
class DefaultAimController(
    private val orientation: OrientationRepository,
    private val location: LocationOrchestrator,
    private val time: TimeSource,
    private val ephem: EphemerisComputer,
    /**
     * Преобразование экваториальных координат в горизонтальные.
     * Ожидает lstDeg (0..360) и широту места.
     */
    private val raDecToAltAz: (Equatorial, Double, Double) -> Horizontal,
    /** Оффлайн‑резолвер звезды по id (если eq не передали). */
    private val starResolver: ((Int) -> Equatorial?)? = null,
    /**
     * Источник магнитного склонения для перевода компасного азимута (магнитный север)
     * в истинный север. По умолчанию — нулевой (без коррекции), чтобы не тянуть Android
     * в JVM‑тесты.
     */
    private val declinationProvider: MagneticDeclinationProvider = MagneticDeclinationProvider.Zero,
    /** Диспетчер для вычислений/сбора потоков (инжектируется для тестов). */
    private val dispatcher: CoroutineDispatcher = AimDispatchers.computation,
) : AimController {
    private val _state =
        MutableStateFlow(
            AimState(
                current = Horizontal(azDeg = 0.0, altDeg = 0.0),
                target = Horizontal(azDeg = 0.0, altDeg = 0.0),
                dAzDeg = 0.0,
                dAltDeg = 0.0,
                phase = AimPhase.SEARCHING,
                confidence = 0f,
            ),
        )
    override val state: StateFlow<AimState> = _state

    private var scope: CoroutineScope? = null
    @Volatile private var target: AimTarget = AimTarget.EquatorialTarget(polarisJ2000)
    @Volatile private var tol: AimTolerance = AimTolerance()
    @Volatile private var holdMs: Long = DEFAULT_HOLD_TO_LOCK_MS
    @Volatile private var lastFix: LocationFix? = null

    // Phase-machine state shared between resetPhaseMachine() (UI thread: setTarget/start/stop) and
    // tick() (Default thread). The only cross-thread writes are the UI-thread resets, which are plain
    // writes of known values; the read-modify-write of these fields happens ONLY in tick() (a single
    // coroutine), so there is no concurrent RMW across threads. @Volatile therefore gives the needed
    // visibility (a UI reset is promptly seen by the next tick) without requiring full-machine atomicity.
    @Volatile private var inTolSinceMs: Long? = null
    @Volatile private var lastPhase: AimPhase = AimPhase.SEARCHING
    @Volatile private var outTolTicks: Int = 0

    private var lastTickMs: Long = 0

    // окно для оценки confidence
    private data class AzSample(
        val ts: Long,
        val azDeg: Double,
    )

    private val azWindow: ArrayDeque<AzSample> = ArrayDeque(CONFIDENCE_WINDOW_CAPACITY)

    override fun setTarget(target: AimTarget) {
        this.target = target
        resetPhaseMachine()
        // aim_target_changed {target}
        when (target) {
            is AimTarget.StarTarget -> {
                val payload =
                    buildMap<String, Any?> {
                        put("target", "STAR")
                        put("id", target.starId)
                        target.eq?.let {
                            put("raDeg", it.raDeg)
                            put("decDeg", it.decDeg)
                        }
                    }
                LogBus.d(tag = "Aim", msg = "aim_target_changed", payload = payload)
            }
            is AimTarget.BodyTarget -> {
                LogBus.d(tag = "Aim", msg = "aim_target_changed", payload = mapOf("target" to target.body.name))
            }
            is AimTarget.EquatorialTarget -> {
                LogBus.d(
                    tag = "Aim",
                    msg = "aim_target_changed",
                    payload = mapOf("target" to "EQUATORIAL", "raDeg" to target.eq.raDeg, "decDeg" to target.eq.decDeg),
                )
            }
        }
    }

    override fun setTolerance(t: AimTolerance) {
        this.tol = t
    }

    override fun setHoldToLockMs(ms: Long) {
        this.holdMs = max(0L, ms)
    }

    override fun start() {
        if (scope != null) return
        resetPhaseMachine()
        // Drop any fix carried over from a previous session BEFORE collectors launch, so a stale
        // value can't briefly guide/lock before the new getLastKnown() seed or a live fix arrives.
        lastFix = null
        // aim_start
        LogBus.d(tag = "Aim", msg = "aim_start", payload = emptyMap())

        scope =
            CoroutineScope(dispatcher + SupervisorJob()).also { sc ->
                // поток локации
                sc.launch {
                    // Seed once (hardened: getLastKnown() can throw SecurityException on a revoked permission).
                    lastFix = runCatching { location.getLastKnown() }.getOrNull()
                    // Live updates INCLUDING null on loss. currentFix pushes null when location becomes
                    // unavailable, which the non-null `fixes` flow could not signal.
                    // location.start() stays owned by MainActivity.
                    location.currentFix.collect { fix -> lastFix = fix }
                }
                // поток ориентации
                sc.launch {
                    // ktlint: require newline after '->'
                    orientation.frames.collectLatest { frame ->
                        tick(frame)
                    }
                }
            }
    }

    override fun stop() {
        // aim_stop
        LogBus.d(tag = "Aim", msg = "aim_stop", payload = emptyMap())

        scope?.cancel()
        scope = null
        resetPhaseMachine()
        lastFix = null
        azWindow.clear()
    }

    private fun tick(frame: OrientationFrame) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTickMs < MIN_UPDATE_INTERVAL_MS) return
        lastTickMs = nowMs

        val fix = lastFix
        val instant = time.now()
        val current = toHorizontal(frame, declinationFor(fix, instant))
        addAzSample(nowMs, current.azDeg)

        // GATE 1 — no real location fix: do not fabricate (0,0).
        if (fix == null) {
            enterNonGuidingPhase(AimPhase.NO_LOCATION, current)
            return
        }

        val targetHor = computeTargetHorizontal(instant, fix)
        if (targetHor == null) {
            // Target unresolvable (e.g. unknown star id) — existing behaviour: publish current ray only.
            inTolSinceMs = null
            outTolTicks = 0
            lastPhase = AimPhase.SEARCHING
            _state.value =
                _state.value.copy(
                    current = current,
                    phase = AimPhase.SEARCHING,
                    confidence = computeConfidence(),
                )
            return
        }

        // GATE 2 — target below the horizon: not observable. No lock, no haptics.
        if (targetHor.altDeg < HORIZON_THRESHOLD_DEG) {
            enterNonGuidingPhase(AimPhase.BELOW_HORIZON, current, targetHor, aimDelta(current, targetHor))
            return
        }

        // --- normal guidance path below, UNCHANGED ---
        val delta = aimDelta(current, targetHor)
        val crossAbs = abs(delta.crossTrackDeg)
        val alongAbs = abs(delta.alongTrackDeg)
        val newPhase = computePhase(nowMs, crossAbs, alongAbs)
        val conf = computeConfidence()

        // лог переходов фаз
        if (newPhase != lastPhase) {
            when (newPhase) {
                AimPhase.IN_TOLERANCE -> {
                    LogBus.d(
                        tag = "Aim",
                        msg = "aim_enter_tolerance",
                        payload = mapOf("dAz" to delta.crossTrackDeg, "dAlt" to delta.alongTrackDeg),
                    )
                }
                AimPhase.LOCKED -> {
                    LogBus.d(
                        tag = "Aim",
                        msg = "aim_lock",
                        payload = mapOf("holdMs" to holdMs),
                    )
                }
                AimPhase.SEARCHING -> {
                    LogBus.d(tag = "Aim", msg = "aim_lost", payload = emptyMap())
                }
                else -> {}
            }
        }
        // фиксируем новую фазу, чтобы не спамить событиями на каждом тике
        lastPhase = newPhase

        _state.value =
            AimState(
                current = current,
                target = targetHor,
                dAzDeg = delta.crossTrackDeg,
                dAltDeg = delta.alongTrackDeg,
                phase = newPhase,
                confidence = conf,
            )
    }

    /**
     * Publish a non-guiding phase (NO_LOCATION / BELOW_HORIZON) and reset lock progress so that
     * returning to the normal path re-acquires from scratch. No ENTER/LOCK haptics ever fire here
     * because AimScreen's haptic effect only reacts to IN_TOLERANCE / LOCKED / SEARCHING.
     */
    private fun enterNonGuidingPhase(
        phase: AimPhase,
        current: Horizontal,
        target: Horizontal? = null,
        delta: AimDelta? = null,
    ) {
        inTolSinceMs = null
        outTolTicks = 0
        if (phase != lastPhase) {
            LogBus.d(
                tag = "Aim",
                msg = if (phase == AimPhase.NO_LOCATION) "aim_no_location" else "aim_below_horizon",
                payload = emptyMap(),
            )
        }
        lastPhase = phase
        _state.value =
            _state.value.copy(
                current = current,
                target = target ?: _state.value.target,
                dAzDeg = delta?.crossTrackDeg ?: _state.value.dAzDeg,
                dAltDeg = delta?.alongTrackDeg ?: _state.value.dAltDeg,
                phase = phase,
                confidence = computeConfidence(),
            )
    }

    // --- core helpers kept in class (логическая часть) ---
    /**
     * Текущее направление руки в горизонтальных координатах.
     *
     * Берём «forward»‑вектор устройства в мировой ENU‑системе (а не сырые углы Эйлера): так
     * высота не зависит от крена. Азимут компас отдаёт относительно магнитного севера —
     * добавляем склонение [declinationDeg], чтобы получить истинный север, как у целей.
     */
    private fun toHorizontal(
        frame: OrientationFrame,
        declinationDeg: Double,
    ): Horizontal {
        // forward — инвариант: ровно 3 ENU‑компоненты (см. OrientationFrame.forward).
        val forward = frame.forward
        val magnetic =
            forwardVectorToHorizontal(
                east = forward[0].toDouble(),
                north = forward[1].toDouble(),
                up = forward[2].toDouble(),
            )
        return magnetic.toTrueNorth(declinationDeg)
    }

    private fun declinationFor(
        fix: LocationFix?,
        instant: Instant,
    ): Double {
        val point = fix?.point ?: return 0.0
        return declinationProvider
            .declinationDeg(
                latDeg = point.latDeg,
                lonDeg = point.lonDeg,
                altitudeM = fix.altitudeM ?: 0.0,
                epochMillis = instant.toEpochMilli(),
            ).toDouble()
    }

    private fun computeTargetHorizontal(
        now: Instant,
        fix: LocationFix?,
    ): Horizontal? {
        val lat = fix?.point?.latDeg ?: 0.0
        val lon = fix?.point?.lonDeg ?: 0.0
        val lst = lstAt(now, lon).lstDeg
        val eqOrNull: Equatorial? =
            when (val t = target) {
                is AimTarget.EquatorialTarget -> t.eq
                is AimTarget.BodyTarget -> ephem.compute(t.body, now).eq
                is AimTarget.StarTarget -> t.eq ?: starResolver?.invoke(t.starId)
            }
        return eqOrNull?.let { raDecToAltAz(it, lst, lat) }
    }

    private fun resetPhaseMachine() {
        inTolSinceMs = null
        outTolTicks = 0
        lastPhase = AimPhase.SEARCHING
        _state.value = _state.value.copy(phase = AimPhase.SEARCHING)
    }

    private fun computePhase(
        nowMs: Long,
        crossAbs: Double,
        alongAbs: Double,
    ): AimPhase {
        val enter = crossAbs <= tol.azDeg && alongAbs <= tol.altDeg
        val release = crossAbs <= tol.azDeg * RELEASE_FACTOR && alongAbs <= tol.altDeg * RELEASE_FACTOR
        return when (lastPhase) {
            AimPhase.SEARCHING, AimPhase.BELOW_HORIZON, AimPhase.NO_LOCATION ->
                if (enter) { inTolSinceMs = nowMs; outTolTicks = 0; AimPhase.IN_TOLERANCE }
                else AimPhase.SEARCHING

            AimPhase.IN_TOLERANCE -> when {
                enter -> {
                    // Only the enter box may advance the hold timer.
                    outTolTicks = 0
                    val start = inTolSinceMs ?: nowMs.also { inTolSinceMs = it }
                    if (nowMs - start >= holdMs) AimPhase.LOCKED else AimPhase.IN_TOLERANCE
                }
                release -> {
                    // Grace zone: preserve IN_TOLERANCE but reset the hold timer so grace time
                    // cannot contribute to the holdMs requirement.
                    inTolSinceMs = null
                    outTolTicks = 0
                    AimPhase.IN_TOLERANCE
                }
                else -> {
                    outTolTicks += 1
                    if (outTolTicks >= RELEASE_TICKS) {
                        inTolSinceMs = null; outTolTicks = 0; AimPhase.SEARCHING
                    } else {
                        AimPhase.IN_TOLERANCE // grace: survive a brief excursion beyond release box
                    }
                }
            }

            AimPhase.LOCKED ->
                if (release) {
                    // Enter zone is a subset of release zone — both keep the lock.
                    outTolTicks = 0
                    AimPhase.LOCKED
                } else {
                    outTolTicks += 1
                    if (outTolTicks >= RELEASE_TICKS) {
                        inTolSinceMs = null; outTolTicks = 0; AimPhase.SEARCHING
                    } else {
                        AimPhase.LOCKED // grace: survive a brief excursion beyond release box
                    }
                }
        }
    }

    private fun addAzSample(
        tsMs: Long,
        azDeg: Double,
    ) {
        pruneOldSamples(tsMs)
        if (azWindow.size >= CONFIDENCE_WINDOW_CAPACITY) azWindow.removeFirst()
        azWindow.addLast(AzSample(tsMs, azDeg))
    }

    private fun pruneOldSamples(nowMs: Long) {
        val threshold = nowMs - CONFIDENCE_WINDOW_MS
        while (azWindow.isNotEmpty() && azWindow.first().ts < threshold) azWindow.removeFirst()
    }

    /** Оценка уверенности (0..1) по круговой дисперсии азимута. */
    private fun computeConfidence(): Float {
        val n = azWindow.size
        if (n < 3) return 0.3f

        // круговая статистика
        var sumX = 0.0
        var sumY = 0.0
        for (s in azWindow) {
            val a = toRadians(s.azDeg)
            sumX += cos(a)
            sumY += sin(a)
        }
        val r = sqrt(sumX * sumX + sumY * sumY) / n
        // circular std (рад): sqrt(-2 ln R), защитимся от R≈0
        val rClamped = max(r, MIN_RESULTANT_LENGTH)
        val stdRad = sqrt(max(0.0, -2.0 * ln(rClamped)))
        val stdDeg = stdRad * 180.0 / Math.PI

        // Маппинг std→confidence: <=2° → ~1.0; >=MAX_STD_DEV_DEG → ~0.1
        val clamped = min(MAX_STD_DEV_DEG, stdDeg)
        val c = 1.0 - (clamped / MAX_STD_DEV_DEG)
        return c.toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val DEFAULT_HOLD_TO_LOCK_MS: Long = 1200L
        private const val MIN_UPDATE_INTERVAL_MS: Long = 66L // ~15 Гц
        private const val CONFIDENCE_WINDOW_MS: Long = 2000L
        private const val CONFIDENCE_WINDOW_CAPACITY: Int = 64
        private const val MAX_STD_DEV_DEG: Double = 12.0
        private const val MIN_RESULTANT_LENGTH: Double = 1e-3
        private const val RELEASE_FACTOR: Double = 1.8 // release box = 1.8× the enter tolerance
        private const val RELEASE_TICKS: Int = 3       // ~200 ms at 15 Hz before dropping the lock
        private const val HORIZON_THRESHOLD_DEG: Double = 0.0 // target.alt below this ⇒ BELOW_HORIZON
    }
}

// Локальный провайдер дефолтного диспетчера с подавлением линта.
private object AimDispatchers {
    @Suppress("InjectDispatcher")
    val computation: CoroutineDispatcher = Dispatchers.Default
}
