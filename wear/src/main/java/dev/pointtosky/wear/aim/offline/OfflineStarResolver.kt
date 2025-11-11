package dev.pointtosky.wear.aim.offline

import android.content.Context
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.logging.LogBus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Оффлайн‑резолвер: читает assets бинарник каталога звёзд и строит индекс id→(RA,Dec).
 * Ожидаем файл в одном из путей: "catalog/stars_V1.bin", "stars_V1.bin", "catalog stars_V1.bin".
 * Формат №1: [count:Int][ (id:Int, ra:Double, dec:Double) * count ] в Little‑Endian.
 * Формат №2: [count:Int][ (id:Int, ra:Float,  dec:Float ) * count ] в Little‑Endian.
 * Fallback: блочное сканирование по 20‑байтным записям (Int + Double + Double).
 * Пишем мини‑метрику LogBus.event("offline_star_index", { ok,size,count,path }).
 */
fun offlineStarResolver(context: Context): (Int) -> Equatorial? {
    val app = context.applicationContext
    val assets = app.assets
    val candidatePaths = listOf(
        "catalog/stars_V1.bin",
        "stars_V1.bin",
        "catalog stars_V1.bin",
    )

    var chosenPath: String? = null
    var bytes: ByteArray? = null
    for (p in candidatePaths) {
        val ok = runCatching {
            assets.open(p).use { stream -> bytes = stream.readBytes() }
            true
        }.getOrDefault(false)
        if (ok) {
            chosenPath = p
            break
        }
    }

    val index: Map<Int, Equatorial> = try {
        val data = bytes ?: ByteArray(0)
        if (data.isEmpty()) emptyMap() else parseStarIndex(data)
    } catch (_: Throwable) {
        emptyMap()
    }

    // мини‑метрика — видим подхватился ли индекс и какой объём
    val meta = buildMap<String, Any?> {
        put("ok", index.isNotEmpty())
        put("size", bytes?.size ?: 0)
        put("count", index.size)
        put("path", chosenPath.orEmpty())
    }
    LogBus.event("offline_star_index", meta)

    return { id -> index[id] }
}

private fun parseStarIndex(data: ByteArray): Map<Int, Equatorial> {
    if (data.size < 4) return emptyMap()
    parseCounted(data, doubles = true)?.let { return it }
    parseCounted(data, doubles = false)?.let { return it }
    return parseFixedStride(data)
}

private fun parseCounted(data: ByteArray, doubles: Boolean): Map<Int, Equatorial>? {
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val count = bb.int
    val recSize = if (doubles) 4 + 8 + 8 else 4 + 4 + 4
    if (data.size < 4 + count * recSize) return null
    val map = HashMap<Int, Equatorial>(count.coerceAtLeast(16))
    repeat(count) {
        if (bb.remaining() < recSize) return@repeat
        val id = bb.int
        val ra = if (doubles) bb.double else bb.float.toDouble()
        val dec = if (doubles) bb.double else bb.float.toDouble()
        map[id] = Equatorial(raDeg = ra, decDeg = dec)
    }
    return map
}

private fun parseFixedStride(data: ByteArray): Map<Int, Equatorial> {
    // Требуем размер кратный 20 байтам (Int + Double + Double)
    if (data.size % 20 != 0) return emptyMap()
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val map = HashMap<Int, Equatorial>()
    while (bb.remaining() >= 20) {
        val id = bb.int
        val ra = bb.double
        val dec = bb.double
        map[id] = Equatorial(raDeg = ra, decDeg = dec)
    }
    return map
}
