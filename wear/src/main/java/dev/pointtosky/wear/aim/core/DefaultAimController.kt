package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
     * Fallback‑резолвер звезды по id (офлайн), если в цели нет eq.
     * По умолчанию ничего не резолвит.
     */
    private val resolveStar: (Int) -> Equatorial? = { null },
    /**
     * Преобразование экваториальных координат в горизонтальные.
     * Ожидает lstDeg (0..360) и широту места.
     */
    private val raDecToAltAz: (Equatorial, Double, Double) -> Horizontal,
    /** Оффлайн‑резолвер звезды по id (если eq не передали). */
    private val starResolver: ((Int) -> Equatorial?)? = null,
) : AimController {
    private val _state = MutableStateFlow(
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
    private var target: AimTarget = AimTarget.EquatorialTarget(POLARIS_EQ)
    private var tol: AimTolerance = AimTolerance()
    private var holdMs: Long = DEFAULT_HOLD_TO_LOCK_MS
    private var lastFix: LocationFix? = null

    // удержание для LOCK
    private var inTolSinceMs: Long? = null
    private var lastTickMs: Long = 0

    // для определения переходов фаз
    private var lastPhase: AimPhase = AimPhase.SEARCHING

    // окно для оценки confidence
    private data class AzSample(val ts: Long, val azDeg: Double)
    private val azWindow: ArrayDeque<AzSample> = ArrayDeque(CONFIDENCE_WINDOW_CAPACITY)

    override fun setTarget(target: AimTarget) {
        this.target = target
        // сбрасываем удержание
        inTolSinceMs = null
        // aim_target_changed {target}
        when (target) {
            is AimTarget.StarTarget -> {
                val payload = buildMap<String, Any?> {
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
        // aim_start
        LogBus.d(tag = "Aim", msg = "aim_start", payload = emptyMap())

        scope = CoroutineScope(Dispatchers.Default + Job()).also { sc ->
            // поток локации (редко)
            sc.launch {
                lastFix = location.getLastKnown()
                // допускаем, что где-то ещё у тебя есть полноценная подписка на Fixes,
                // здесь нам достаточно lastKnown, чтобы ядро работало.
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
        inTolSinceMs = null
        azWindow.clear()
    }

    private fun tick(frame: OrientationFrame) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastTickMs < MIN_UPDATE_INTERVAL_MS) return
        lastTickMs = nowMs

        val current = toHorizontal(frame)
        addAzSample(nowMs, current.azDeg)

        val fix = lastFix
        val instant = time.now()
        val targetHor = computeTargetHorizontal(instant, fix)

        if (targetHor != null) {
            val dAz = normalizeAzimuthDelta(targetHor.azDeg - current.azDeg)
            val dAlt = targetHor.altDeg - current.altDeg

            val inTolerance = (abs(dAz) <= tol.azDeg) && (abs(dAlt) <= tol.altDeg)
            val newPhase = computePhase(nowMs, inTolerance)
            val conf = computeConfidence()

            // лог переходов фаз
            if (newPhase != lastPhase) {
                when (newPhase) {
                    AimPhase.IN_TOLERANCE -> {
                        LogBus.d(
                            tag = "Aim",
                            msg = "aim_enter_tolerance",
                            payload = mapOf("dAz" to dAz, "dAlt" to dAlt),
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
                }
            }
            // фиксируем новую фазу, чтобы не спамить событиями на каждом тике
            lastPhase = newPhase

            _state.value = AimState(
                current = current,
                target = targetHor,
                dAzDeg = dAz,
                dAltDeg = dAlt,
                phase = newPhase,
                confidence = conf,
            )
        } else {
            // Цель пока не посчитана — публикуем только текущий луч.
            _state.value = _state.value.copy(
                current = current,
                phase = AimPhase.SEARCHING,
                confidence = computeConfidence(),
            )
        }
    }

    // --- core helpers kept in class (логическая часть) ---
    private fun toHorizontal(frame: OrientationFrame): Horizontal =
        Horizontal(
            azDeg = wrapDeg0To360(frame.azimuthDeg.toDouble()),
            altDeg = clamp(frame.pitchDeg.toDouble(), -90.0, 90.0),
        )

    private fun computeTargetHorizontal(now: Instant, fix: LocationFix?): Horizontal? {
        val lat = fix?.point?.latDeg ?: 0.0
        val lon = fix?.point?.lonDeg ?: 0.0
        val lst = lstDeg(now, lon)
        val eqOrNull: Equatorial? = when (val t = target) {
            is AimTarget.EquatorialTarget -> t.eq
            is AimTarget.BodyTarget -> ephem.compute(t.body, now).eq
            is AimTarget.StarTarget -> t.eq ?: starResolver?.invoke(t.starId) ?: resolveStar(t.starId)
        }
        return eqOrNull?.let { raDecToAltAz(it, lst, lat) }
    }

    private fun computePhase(nowMs: Long, inTolerance: Boolean): AimPhase {
        if (!inTolerance) {
            inTolSinceMs = null
            return AimPhase.SEARCHING
        }
        val start = inTolSinceMs ?: run {
            inTolSinceMs = nowMs
            return AimPhase.IN_TOLERANCE
        }
        val dt = nowMs - start
        return if (dt >= holdMs) AimPhase.LOCKED else AimPhase.IN_TOLERANCE
    }

    private fun addAzSample(tsMs: Long, azDeg: Double) {
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

        // Polaris ~ J2000 (приближенно)
        private val POLARIS_EQ = Equatorial(raDeg = 37.95456067, decDeg = 89.26410897)
    }
}

// ---- Angle/Time helpers (file‑private) ----
private fun clamp(v: Double, mn: Double, mx: Double) = max(mn, min(mx, v))

private fun wrapDeg0To360(d: Double): Double {
    var x = d % 360.0
    if (x < 0) x += 360.0
    return x
}

private fun normalizeAzimuthDelta(delta: Double): Double {
    var d = delta
    while (d > 180.0) d -= 360.0
    while (d <= -180.0) d += 360.0
    return d
}

private fun julianDay(instant: Instant): Double =
    // JD from Unix time: JD = (ms/86400000) + 2440587.5
    instant.toEpochMilli() / 86_400_000.0 + 2440587.5

private fun gmstDeg(jd: Double): Double {
    val t = (jd - 2451545.0) / 36525.0
    val theta = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - (t * t * t) / 38710000.0
    return wrapDeg0To360(theta)
}

private fun lstDeg(instant: Instant, lonDeg: Double): Double =
    wrapDeg0To360(gmstDeg(julianDay(instant)) + lonDeg)
