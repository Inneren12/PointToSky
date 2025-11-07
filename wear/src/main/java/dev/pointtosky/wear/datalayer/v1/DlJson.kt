package dev.pointtosky.wear.datalayer.v1

import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal object DlJson {
    fun envelope(cid: String, payloadUtf8: ByteArray?): ByteArray {
        val root = JSONObject()
        root.put("v", DlPaths.V)
        root.put("cid", cid)
        if (payloadUtf8 != null) {
            val s = String(payloadUtf8, StandardCharsets.UTF_8).trim()
            val payload = runCatching { JSONObject(s) }.getOrNull()
            if (payload != null) root.put("payload", payload) else root.put("payloadRaw", s)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

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
