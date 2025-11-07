package dev.pointtosky.wear.tile.tonight

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.catalog.binary.BinaryStarCatalog
import dev.pointtosky.core.catalog.io.AssetProvider
import kotlinx.coroutines.flow.firstOrNull
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.location.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.*
import kotlinx.coroutines.flow.firstOrNull
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import java.time.ZoneOffset
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Реализация S7.C:
 *  - офлайн‑подбор 2–3 целей на ближайшую ночь;
 *  - планеты (Moon/Jupiter/Saturn) + яркие звёзды (mag ≤ 2.5);
 *  - кэш: in-memory + DataStore (ключ = UTC‑дата ночи + lat/lon, округлённые до 0.25°).
 */
class RealTonightProvider(
    private val context: Context,
    private val timeSource: TimeSource,
    private val zoneRepo: ZoneRepo,
    private val ephemeris: EphemerisComputer = SimpleEphemerisComputer(),
    private val starCatalog: StarCatalog? = null, // если null — загрузим BinaryStarCatalog из assets
    private val getLastKnownLocation: suspend () -> GeoPoint? = { null },
) : TonightProvider {

    private val cache by lazy { TonightCacheStore(context) }

    override suspend fun getModel(now: Instant): TonightTileModel = withContext(Dispatchers.Default) {
        val zone = zoneRepo.current()
        // Сначала пробуем получить локацию — она нужна для реального гражданского сумрака
        val gp = getLastKnownLocation.invoke()
        // Умный подбор окна ночи: civil twilight (-6°), при недоступности — фолбэк 18:00–06:00
        val (start, end, nightKeyDateUtc) = nightWindow(now, zone, gp)

        // Ключ кэша: дата старта ночи (UTC) + lat/lon округлённые до 0.25°
        val key = buildCacheKey(nightKeyDateUtc, gp)
        // 1) in-memory
        TonightMemCache.getIfValid(key)?.let { return@withContext it.model }
        // 2) persistent
        cache.get(key)?.let { cached ->
            // Вернём, если не протухло
            if (cached.expiresAt > System.currentTimeMillis()) {
                TonightMemCache.put(key, cached)
                return@withContext cached.model
            }
        }

        // Вычисление
        val model = pickTargets(now, start, end, gp, zone)

        // TTL: до конца ночи + 5 минут
        val ttlMs = max(System.currentTimeMillis() + 5 * 60_000L, end.toEpochMilli())
        val entry = CacheEntry(model = model, expiresAt = ttlMs)
        TonightMemCache.put(key, entry)
        cache.put(key, entry)
        model
    }

    // --- Подбор целей ---

    private suspend fun pickTargets(
        now: Instant,
        start: Instant,
        end: Instant,
        gp: GeoPoint?,
        zone: ZoneId,
    ): TonightTileModel {
        // Если локации нет — быстрый фолбэк (Moon + Vega)
        if (gp == null) {
            val moon = TonightTarget(
                id = "MOON",
                title = "Moon",
                subtitle = null,
                icon = TonightIcon.MOON,
            )
            val vega = TonightTarget(
                id = "VEGA",
                title = "Vega",
                subtitle = null,
                icon = TonightIcon.STAR,
            )
            return TonightTileModel(updatedAt = now, items = listOf(moon, vega))
        }

        // Источники
        val catalog = starCatalog ?: loadStarCatalog()

        // Планеты
        val planets = listOf(Body.MOON, Body.JUPITER, Body.SATURN)
            .mapNotNull { body -> evaluatePlanet(body, gp, zone, start, end, now) }

        // Звёзды: маг ≤ 2.5
        val brightStars: List<Star> = catalog.nearby(Equatorial(0.0, 0.0), 180.0, 2.5)
        val stars = brightStars
            .mapNotNull { star ->
                evaluateStar(
                    star.raDeg.toDouble(),
                    star.decDeg.toDouble(),
                    displayName(star),
                    gp, zone, start, end, now
                )
            }

        val all = (planets + stars)
            .sortedWith(
                compareByDescending<Candidate> { it.kind.priority }
                    .thenByDescending { it.score }
            )
        val top = all.take(3)
        val targets = top.map { cand ->
            val subtitle = cand.window?.let { (ws, we) -> hhmm(ws, zone) + "–" + hhmm(we, zone) }
                ?: cand.altNowDeg?.let { "alt ${it.roundToInt()}°" }
            TonightTarget(
                id = cand.id,
                title = cand.title,
                subtitle = subtitle,
                icon = cand.icon,
                azDeg = cand.azNowDeg,
                altDeg = cand.altNowDeg,
                windowStart = cand.window?.first,
                windowEnd = cand.window?.second,
            )
        }
        val limited = when {
            targets.size >= 2 -> targets.take(3)
            else -> targets + listOf(
                TonightTarget(id = "VEGA", title = "Vega", subtitle = null, icon = TonightIcon.STAR)
            )
        }
        return TonightTileModel(updatedAt = now, items = limited)
    }

    /**
     * Ближайший момент, когда тайл стоит обновить:
     *  - если мы до конца вечернего сумерка → на его конец,
     *  - если в ночном окне → на начало утреннего сумерка,
     *  - иначе → на следующий вечерний сумерки.
     *  Резерв: ближайший «верх часа» — выбираем минимальный из двух.
     */
    suspend fun nextRefreshAt(now: Instant): Instant? = withContext(Dispatchers.Default) {
        val zone = zoneRepo.current()
        val gp = getLastKnownLocation.invoke()
        val w = nightWindow(now, zone, gp)
        val base = when {
            now.isBefore(w.start) -> w.start
            now.isBefore(w.end) -> w.end
            else -> {
                val wNext = nightWindow(now.plus(Duration.ofHours(24)), zone, gp)
                wNext.start
            }
        }
        val hour = nextTopOfHour(now, zone)
        return@withContext minOf(base, hour)
    }

    private fun nextTopOfHour(now: Instant, zone: ZoneId): Instant {
        val z = now.atZone(zone).plusHours(1)
            .withMinute(0).withSecond(0).withNano(0)
        return z.toInstant()
    }

    private fun displayName(star: Star): String {
        return star.name ?: star.bayer ?: star.flamsteed ?: "Star"
    }

    private fun evaluatePlanet(
        body: Body,
        gp: GeoPoint,
        zone: ZoneId,
        start: Instant,
        end: Instant,
        now: Instant,
    ): Candidate? {
        val name = when (body) {
            Body.MOON -> "Moon"
            Body.JUPITER -> "Jupiter"
            Body.SATURN -> "Saturn"
            else -> return null
        }
        val icon = when (body) {
            Body.MOON -> TonightIcon.MOON
            Body.JUPITER -> TonightIcon.JUPITER
            Body.SATURN -> TonightIcon.SATURN
            else -> TonightIcon.STAR
        }
        // Сэмплирование по окну ночи
        val stepMin = 5L
        val samples = sampleWindow(start, end, stepMin) { t ->
            val eq = ephemeris.compute(body, t).eq
            val h = AstroMath.raDecToAltAz(eq, t, gp.latDeg, gp.lonDeg, zone)
            h.altDeg
        }
        val vis = summarizeVisibility(samples, stepMin)
        // Фильтры S7.C
        if (vis.altPeakDeg < 15.0 || vis.visibleMinutes < 30) return null
        val altNow = ephemeris.compute(body, now).eq.let { eq ->
            AstroMath.raDecToAltAz(eq, now, gp.latDeg, gp.lonDeg, zone)
        }
        val score = 0.6 * vis.altPeakDeg + 0.3 * (vis.visibleMinutes / 60.0) - 0.1 * vis.airmassAtPeak
        return Candidate(
            id = body.name,
            title = name,
            icon = icon,
            kind = Kind.PLANET,
            score = score,
            window = vis.longestWindow,
            altNowDeg = altNow.altDeg,
            azNowDeg = altNow.azDeg,
        )
    }

    private fun evaluateStar(
        raDeg: Double,
        decDeg: Double,
        title: String,
        gp: GeoPoint,
        zone: ZoneId,
        start: Instant,
        end: Instant,
        now: Instant,
    ): Candidate? {
        val eq = Equatorial(raDeg, decDeg)
        val stepMin = 10L
        val samples = sampleWindow(start, end, stepMin) { t ->
            AstroMath.raDecToAltAz(eq, t, gp.latDeg, gp.lonDeg, zone).altDeg
        }
        val vis = summarizeVisibility(samples, stepMin)
        // Фильтры звёзд
        if (vis.altPeakDeg < 25.0 || vis.visibleMinutes < 30) return null
        val altNow = AstroMath.raDecToAltAz(eq, now, gp.latDeg, gp.lonDeg, zone)
        val score = 0.6 * vis.altPeakDeg + 0.3 * (vis.visibleMinutes / 60.0) - 0.1 * vis.airmassAtPeak
        return Candidate(
            id = "STAR:$title",
            title = title,
            icon = TonightIcon.STAR,
            kind = Kind.STAR,
            score = score,
            window = vis.longestWindow,
            altNowDeg = altNow.altDeg,
            azNowDeg = altNow.azDeg,
        )
    }

    // --- Вспомогательные: выборка, видимость, окно ночи, форматирование ---

    private fun sampleWindow(
        start: Instant,
        end: Instant,
        stepMinutes: Long,
        f: (Instant) -> Double
    ): List<Pair<Instant, Double>> {
        val out = ArrayList<Pair<Instant, Double>>()
        var t = start
        val step = Duration.ofMinutes(stepMinutes)
        while (!t.isAfter(end)) {
            out += t to f(t)
            t = t.plus(step)
        }
        return out
    }

    private data class Visibility(
        val altPeakDeg: Double,
        val visibleMinutes: Int,
        val airmassAtPeak: Double,
        val longestWindow: Pair<Instant, Instant>?,
    )

    private fun summarizeVisibility(samples: List<Pair<Instant, Double>>, stepMinutes: Long): Visibility {
        var altPeak = -90.0
        var totalVisible = 0
        var longest: Pair<Instant, Instant>? = null
        var runStart: Instant? = null
        var lastT: Instant? = null
        samples.forEach { (t, alt) ->
            if (alt > altPeak) altPeak = alt
            if (alt > 0.0) {
                totalVisible += stepMinutes.toInt()
                if (runStart == null) runStart = t
                lastT = t
            } else {
                if (runStart != null && lastT != null) {
                    val candidate = runStart!! to lastT!!
                    if (longest == null || Duration.between(longest!!.first, longest!!.second) < Duration.between(candidate.first, candidate.second)) {
                        longest = candidate
                    }
                }
                runStart = null
                lastT = null
            }
        }
        if (runStart != null && lastT != null) {
            val candidate = runStart!! to lastT!!
            if (longest == null || Duration.between(longest!!.first, longest!!.second) < Duration.between(candidate.first, candidate.second)) {
                longest = candidate
            }
        }
        val airmass = AstroMath.airmassFromAltDeg(altPeak)
        return Visibility(
            altPeakDeg = altPeak,
            visibleMinutes = totalVisible,
            airmassAtPeak = airmass,
            longestWindow = longest,
        )
    }

    private data class NightWindow(val start: Instant, val end: Instant, val nightUtcDate: LocalDate)

    /**
     * Возвращает окно "ночи":
     *  • [конец вечернего гражданского сумерка; начало утреннего гражданского сумерка] при наличии локации;
     *  • иначе фолбэк [18:00; 06:00] локального времени.
     */
    private fun nightWindow(now: Instant, zone: ZoneId, gp: GeoPoint?): NightWindow {
        if (gp != null) {
            civilNightWindow(now, zone, gp)?.let { return it }
        }
        // Фолбэк: 18:00–06:00 (как раньше)
        val nowLocal = now.atZone(zone)
        val nightDate = if (nowLocal.toLocalTime() < LocalTime.of(6, 0))
            nowLocal.toLocalDate().minusDays(1) else nowLocal.toLocalDate()
        val start = ZonedDateTime.of(nightDate, LocalTime.of(18, 0), zone).toInstant()
        val end = ZonedDateTime.of(nightDate.plusDays(1), LocalTime.of(6, 0), zone).toInstant()
        val nightUtcDate = start.atZone(ZoneOffset.UTC).toLocalDate()
        return NightWindow(start, end, nightUtcDate)
    }

    /**
     * Реальное ночное окно по гражданскому сумраку: Солнце на высоте -6°.
     * Ищем в диапазоне [сегодня 12:00 → завтра 12:00]:
     *  • DESC crossing (сверху вниз) после полудня — конец вечернего гражданского сумерка,
     *  • ASC crossing (снизу вверх) до полудня — начало утреннего гражданского сумерка.
     */
    private fun civilNightWindow(now: Instant, zone: ZoneId, gp: GeoPoint): NightWindow? {
        val CIVIL = -6.0
        val todayLocal = now.atZone(zone).toLocalDate().let { d ->
            if (now.atZone(zone).toLocalTime() < LocalTime.of(6, 0)) d.minusDays(1) else d
        }
        val scanStart = ZonedDateTime.of(todayLocal, LocalTime.NOON, zone).toInstant()
        val scanEnd = ZonedDateTime.of(todayLocal.plusDays(1), LocalTime.NOON, zone).toInstant()
        val step = Duration.ofMinutes(3) // достаточно грубо; дальше уточняем бинарным поиском

        var tPrev = scanStart
        var altPrev = sunAlt(tPrev, gp, zone)
        var evening: Instant? = null
        var morning: Instant? = null

        var t = tPrev.plus(step)
        while (!t.isAfter(scanEnd)) {
            val alt = sunAlt(t, gp, zone)
            val prevAbove = altPrev >= CIVIL
            val currAbove = alt >= CIVIL
            if (prevAbove && !currAbove) {
                // Пересечение вниз (вечер) — интересует после 12:00
                val cross = refineCross(tPrev, t, CIVIL, gp, zone)
                val lt = cross.atZone(zone).toLocalTime()
                if (lt.isAfter(LocalTime.NOON)) {
                    evening = cross
                }
            } else if (!prevAbove && currAbove) {
                // Пересечение вверх (утро) — интересует до 12:00
                val cross = refineCross(tPrev, t, CIVIL, gp, zone)
                val lt = cross.atZone(zone).toLocalTime()
                if (!lt.isAfter(LocalTime.NOON) && morning == null) {
                    morning = cross
                }
            }
            tPrev = t
            altPrev = alt
            t = t.plus(step)
        }
        if (evening != null && morning != null && evening!!.isBefore(morning)) {
            val nightKeyUtc = evening!!.atZone(ZoneOffset.UTC).toLocalDate()
            return NightWindow(evening!!, morning!!, nightKeyUtc)
        }
        return null // не нашли валидное окно — пусть сработает фолбэк
    }

    private fun sunAlt(t: Instant, gp: GeoPoint, zone: ZoneId): Double {
        val eq = ephemeris.compute(Body.SUN, t).eq
        return AstroMath.raDecToAltAz(eq, t, gp.latDeg, gp.lonDeg, zone).altDeg
    }

    /** Уточняем момент пересечения высоты Солнца с порогом [levelDeg] бинарным поиском. */
    private fun refineCross(a0: Instant, b0: Instant, levelDeg: Double, gp: GeoPoint, zone: ZoneId): Instant {
        var a = a0
        var b = b0
        repeat(14) { // ~ до нескольких секунд точности
            val mid = a.plus(Duration.between(a, b).dividedBy(2))
            val altA = sunAlt(a, gp, zone) >= levelDeg
            val altM = sunAlt(mid, gp, zone) >= levelDeg
            if (altA != altM) b = mid else a = mid
        }
        return a.plus(Duration.between(a, b).dividedBy(2))
    }

    private fun hhmm(instant: Instant, zone: ZoneId): String {
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        return instant.atZone(zone).toLocalTime().format(fmt)
    }

    private fun buildCacheKey(nightUtcDate: LocalDate, gp: GeoPoint?): String {
        if (gp == null) return "N:$nightUtcDate@LOC:NONE"
        val latQ = round(gp.latDeg * 4.0) / 4.0
        val lonQ = round(gp.lonDeg * 4.0) / 4.0
        return "N:$nightUtcDate@LAT:${"%.2f"}.format(latQ)}@LON:${"%.2f"}.format(lonQ)}"
    }
    private fun loadStarCatalog(): StarCatalog {
        val assetProvider = object : AssetProvider {
            override fun open(path: String): InputStream = context.assets.open(path)
            override fun exists(path: String): Boolean =
                try {
                    context.assets.open(path).use { /* just probe */ }
                    true
                } catch (_: Exception) {
                    false
                }
        }
        // Фолбэк — FakeStarCatalog (внутри BinaryStarCatalog.load установлен).
        return BinaryStarCatalog.load(assetProvider)
    }
}

// --- Кандидаты и приоритеты ---
private enum class Kind(val priority: Int) { PLANET(2), STAR(1) }

private data class Candidate(
    val id: String,
    val title: String,
    val icon: TonightIcon,
    val kind: Kind,
    val score: Double,
    val window: Pair<Instant, Instant>?,
    val altNowDeg: Double?,
    val azNowDeg: Double?,
)

// --- Кэш ---
private data class CacheEntry(val model: TonightTileModel, val expiresAt: Long)

private object TonightMemCache {
    private val map = ConcurrentHashMap<String, CacheEntry>()
    fun getIfValid(key: String): CacheEntry? = map[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }
    fun put(key: String, entry: CacheEntry) { map[key] = entry }
}

// DataStore Preferences (строковая сериализация; без внешних JSON)
private val Context.tonightCacheDS: DataStore<Preferences> by preferencesDataStore(name = "tonight_cache_v1")

private class TonightCacheStore(
    private val context: Context
) {
    private val store: DataStore<Preferences>
        get() = context.tonightCacheDS
    suspend fun get(key: String): CacheEntry? = withContext(Dispatchers.IO) {
        val prefs = store.data.firstOrNull() ?: return@withContext null
        val payload = prefs[stringPreferencesKey("payload_$key")] ?: return@withContext null
        val expires = prefs[longPreferencesKey("expires_$key")] ?: return@withContext null
        val model = decodeModel(payload) ?: return@withContext null
        CacheEntry(model, expires)
    }

    suspend fun put(key: String, entry: CacheEntry) = withContext(Dispatchers.IO) {
        val encoded = encodeModel(entry.model)
        store.edit { p ->
            p[stringPreferencesKey("payload_$key")] = encoded
            p[longPreferencesKey("expires_$key")] = entry.expiresAt
        }
    }
    private fun encodeModel(model: TonightTileModel): String {
        val b = StringBuilder()
        b.append(model.updatedAt.epochSecond).append('|')
        b.append(model.items.size).append('|')
        model.items.forEach { t ->
            b.append(b64(t.id)).append(';')
            b.append(b64(t.title)).append(';')
            b.append(b64(t.subtitle ?: "")).append(';')
            b.append(t.icon.name).append(';')
            b.append(t.azDeg ?: Double.NaN).append(';')
            b.append(t.altDeg ?: Double.NaN).append(';')
            b.append(t.windowStart?.epochSecond ?: -1).append(';')
            b.append(t.windowEnd?.epochSecond ?: -1).append('|')
        }
        return b.toString()
    }

    private fun decodeModel(s: String): TonightTileModel? {
        val parts = s.split('|')
        if (parts.size < 2) return null
        val updatedAt = Instant.ofEpochSecond(parts[0].toLongOrNull() ?: return null)
        val count = parts[1].toIntOrNull() ?: return null
        val items = ArrayList<TonightTarget>(count)
        var idx = 2
        repeat(count) {
            val p = parts.getOrNull(idx++) ?: return null
            val fields = p.split(';')
            if (fields.size < 8) return null
            val id = b64d(fields[0])
            val title = b64d(fields[1])
            val subtitleRaw = b64d(fields[2])
            val subtitle = subtitleRaw.ifBlank { null }
            val icon = runCatching { TonightIcon.valueOf(fields[3]) }.getOrDefault(TonightIcon.STAR)
            val az = fields[4].toDoubleOrNull()?.takeUnless { it.isNaN() }
            val alt = fields[5].toDoubleOrNull()?.takeUnless { it.isNaN() }
            val ws = fields[6].toLongOrNull()?.takeIf { it >= 0 }?.let { Instant.ofEpochSecond(it) }
            val we = fields[7].toLongOrNull()?.takeIf { it >= 0 }?.let { Instant.ofEpochSecond(it) }
            items += TonightTarget(id, title, subtitle, icon, az, alt, ws, we)
        }
        return TonightTileModel(updatedAt, items)
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun b64d(s: String): String =
        String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)
}
