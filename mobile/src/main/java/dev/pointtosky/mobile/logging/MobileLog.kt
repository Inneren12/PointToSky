package dev.pointtosky.mobile.logging

import dev.pointtosky.core.logging.LogBus

/**
 * Centralised helpers for structured mobile logging events.
 */
object MobileLog {
    fun cardOpen(source: String, id: String?, type: String?) {
        LogBus.event(
            name = "card_open",
            payload = mapOf(
                "source" to source,
                "id" to id,
                "type" to type,
            ).filterValuesNotNull(),
        )
    }

    fun mapOpen() {
        LogBus.event("map_open")
    }

    fun arOpen() {
        LogBus.event("ar_open")
    }

    fun searchQuery(query: String, results: Int) {
        LogBus.event(
            name = "search_query",
            payload = mapOf(
                "q" to query,
                "results" to results,
            ),
        )
    }

    fun setTargetRequest(id: String) {
        LogBus.event(
            name = "set_target_request",
            payload = mapOf("id" to id),
        )
    }

    fun setTargetAck(ok: Boolean, durationMs: Long) {
        LogBus.event(
            name = "set_target_ack",
            payload = mapOf(
                "ok" to ok,
                "dtMs" to durationMs,
            ),
        )
    }

    fun bridgeSend(path: String, cid: String?, nodeId: String?, attempt: Int, payloadBytes: Int) {
        LogBus.event(
            name = "bridge_send",
            payload = mapOf(
                "path" to path,
                "cid" to cid,
                "node" to nodeId,
                "attempt" to attempt,
                "bytes" to payloadBytes,
            ).filterValuesNotNull(),
        )
    }

    fun bridgeRetry(path: String, cid: String?, nodeId: String?, attempt: Int, payloadBytes: Int) {
        LogBus.event(
            name = "bridge_retry",
            payload = mapOf(
                "path" to path,
                "cid" to cid,
                "node" to nodeId,
                "attempt" to attempt,
                "bytes" to payloadBytes,
            ).filterValuesNotNull(),
        )
    }

    fun bridgeRecv(path: String, cid: String?, nodeId: String?) {
        LogBus.event(
            name = "bridge_recv",
            payload = mapOf(
                "path" to path,
                "cid" to cid,
                "node" to nodeId,
            ).filterValuesNotNull(),
        )
    }

    fun bridgeError(path: String, cid: String?, nodeId: String?, error: String?) {
        LogBus.event(
            name = "bridge_error",
            payload = mapOf(
                "path" to path,
                "cid" to cid,
                "node" to nodeId,
                "err" to error,
            ).filterValuesNotNull(),
        )
    }

    private fun Map<String, Any?>.filterValuesNotNull(): Map<String, Any?> =
        filterValues { value -> value != null }
}
