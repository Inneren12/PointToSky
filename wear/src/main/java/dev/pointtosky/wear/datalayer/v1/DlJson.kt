package dev.pointtosky.wear.datalayer.v1

import org.json.JSONObject
import java.nio.charset.StandardCharsets

// --- JSON helpers: избегаем optString(..., null), которое требует non-null String в Java API ---
private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

private fun JSONObject.optBooleanOrNull(name: String): Boolean? = if (has(name) && !isNull(name)) getBoolean(name) else null

internal object DlJson {

    fun parseCid(data: ByteArray): String? = runCatching {
        JSONObject(String(data, StandardCharsets.UTF_8)).optStringOrNull("cid")
    }.getOrNull()
    fun buildAck(refCid: String, ok: Boolean = true, err: String? = null): ByteArray {
        val o = JSONObject()
        o.put("v", DlPaths.V)
        o.put("refCid", refCid)
        o.put("ok", ok)
        if (!err.isNullOrBlank()) o.put("err", err)
        return o.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun parseAck(data: ByteArray): Pair<String?, Boolean?> {
        val o = runCatching { JSONObject(String(data, StandardCharsets.UTF_8)) }.getOrNull() ?: return null to null
        return o.optStringOrNull("refCid") to o.optBooleanOrNull("ok")
    }
}
