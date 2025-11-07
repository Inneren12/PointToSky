package dev.pointtosky.mobile.datalayer.v1

import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal object DlJson {
    fun parseCid(data: ByteArray): String? =
        runCatching { JSONObject(String(data, StandardCharsets.UTF_8)).optString("cid", null) }.getOrNull()
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
        return o.optString("refCid", null) to (if (o.has("ok")) o.optBoolean("ok") else null)
    }
}
