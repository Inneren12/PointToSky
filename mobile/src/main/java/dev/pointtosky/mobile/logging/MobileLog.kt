package dev.pointtosky.mobile.logging

import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugInfo
import dev.pointtosky.core.logging.LogBus

/**
 * Centralised helpers for structured mobile logging events.
 */
object MobileLog {
    fun realStarVisibilityDebug(info: RealStarVisibilityDebugInfo) {
        LogBus.event(
            name = "real_star_visibility_debug",
            payload =
                mapOf(
                    "catalogCount" to info.catalogCount,
                    "catalogMagLimit" to info.catalogMagLimit,
                    "limitingMagnitude" to info.limitingMagnitude,
                    "visibleCount" to info.visibleCount,
                ),
        )
    }

    fun realStarVisibilityDebugFailed(message: String) {
        LogBus.event(
            name = "real_star_visibility_debug_failed",
            payload = mapOf("error" to message),
        )
    }

    fun cardOpen(
        source: String,
        id: String?,
        type: String?,
    ) {
        LogBus.event(
            name = "card_open",
            payload =
                mapOf(
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

    /** CAM-1c: one event per successful `Preview` + `ImageAnalysis` bind. */
    fun cameraAnalysisBound() {
        LogBus.event("camera_analysis_bound")
    }

    /**
     * CAM-1c: one event per successful Preview-*only* bind, reached after the combined `Preview` +
     * `ImageAnalysis` bind was rejected. Distinct from [cameraAnalysisBound] so the two are never
     * conflated: this event means the AR preview is visible but no frame metadata is being
     * produced.
     */
    fun cameraPreviewBoundWithoutAnalysis() {
        LogBus.event("camera_preview_bound_without_analysis")
    }

    /** CAM-1c: bind failure, [reasonCategory] is a short non-device-specific category, never a raw message. */
    fun cameraAnalysisBindFailed(reasonCategory: String) {
        LogBus.event(
            name = "camera_analysis_bind_failed",
            payload = mapOf("reason" to reasonCategory),
        )
    }

    /** CAM-1c: throttled per-session summary — never called once per frame. */
    fun cameraFrameMetadata(
        widthPx: Int,
        heightPx: Int,
        rotationDegrees: Int,
        frameCount: Long,
    ) {
        LogBus.event(
            name = "camera_frame_metadata",
            payload =
                mapOf(
                    "widthPx" to widthPx,
                    "heightPx" to heightPx,
                    "rotationDegrees" to rotationDegrees,
                    "frameCount" to frameCount,
                ),
        )
    }

    /** CAM-1c: analyzer failure, [reasonCategory] is the thrown exception's simple class name only. */
    fun cameraFrameAnalysisFailed(reasonCategory: String) {
        LogBus.event(
            name = "camera_frame_analysis_failed",
            payload = mapOf("reason" to reasonCategory),
        )
    }

    /** CAM-1d: one event when a timestamp-pairing session starts observing camera frames. */
    fun timestampSyncSessionStarted() {
        LogBus.event("timestamp_sync_session_started")
    }

    /** CAM-1d: one event for the first successful camera/rotation pairing in a session. */
    fun timestampSyncFirstPair(deltaMillis: Long) {
        LogBus.event(
            name = "timestamp_sync_first_pair",
            payload = mapOf("deltaMillis" to deltaMillis),
        )
    }

    /** CAM-1d: throttled per-session pairing summary — never called once per frame. */
    fun timestampSyncSummary(
        deltaMillis: Long?,
        pairedCount: Long,
        rejectedCount: Long,
        compatibility: String,
    ) {
        LogBus.event(
            name = "timestamp_sync_summary",
            payload =
                mapOf(
                    "deltaMillis" to deltaMillis,
                    "pairedCount" to pairedCount,
                    "rejectedCount" to rejectedCount,
                    "compatibility" to compatibility,
                ).filterValuesNotNull(),
        )
    }

    /** CAM-1d: logged once when the session's compatibility status transitions to MISMATCH_SUSPECTED. */
    fun timestampSyncClockMismatchSuspected(deltaMillis: Long) {
        LogBus.event(
            name = "timestamp_sync_clock_mismatch_suspected",
            payload = mapOf("deltaMillis" to deltaMillis),
        )
    }

    /** CAM-1d: logged once per session on the first frame with no rotation samples available to pair against. */
    fun timestampSyncUnavailableNoSamples() {
        LogBus.event("timestamp_sync_unavailable_no_samples")
    }

    /** CAM-1f: one event when a camera-session geometry provider starts observing updates. */
    fun cameraGeometrySessionStarted() {
        LogBus.event("camera_geometry_session_started")
    }

    /** CAM-1f: one event for the first Ready geometry bundle published in a session. */
    fun cameraGeometryFirstReady(quality: String) {
        LogBus.event(
            name = "camera_geometry_first_ready",
            payload = mapOf("quality" to quality),
        )
    }

    /** CAM-1f: logged whenever the published geometry status actually changes — never once per frame. */
    fun cameraGeometryStatusChanged(status: String) {
        LogBus.event(
            name = "camera_geometry_status_changed",
            payload = mapOf("status" to status),
        )
    }

    /** CAM-1f: logged once, the first time a Ready bundle uses legacy fallback intrinsics. */
    fun cameraGeometryFallbackIntrinsicsInUse() {
        LogBus.event("camera_geometry_fallback_intrinsics_in_use")
    }

    /** CAM-1f: throttled per-session geometry summary — never called once per frame. */
    fun cameraGeometrySummary(
        status: String,
        quality: String,
        bufferWidthPx: Int,
        bufferHeightPx: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        rotationDegrees: Int,
        pairDeltaMillis: Long,
        intrinsicsSource: String,
    ) {
        LogBus.event(
            name = "camera_geometry_summary",
            payload =
                mapOf(
                    "status" to status,
                    "quality" to quality,
                    "bufferWidthPx" to bufferWidthPx,
                    "bufferHeightPx" to bufferHeightPx,
                    "viewportWidthPx" to viewportWidthPx,
                    "viewportHeightPx" to viewportHeightPx,
                    "rotationDegrees" to rotationDegrees,
                    "pairDeltaMillis" to pairDeltaMillis,
                    "intrinsicsSource" to intrinsicsSource,
                ),
        )
    }

    /** CAM-1f: one event when a camera-session geometry provider is disposed. */
    fun cameraGeometryDisposed() {
        LogBus.event("camera_geometry_disposed")
    }

    fun searchQuery(
        query: String,
        results: Int,
    ) {
        LogBus.event(
            name = "search_query",
            payload =
                mapOf(
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

    fun setTargetAck(
        ok: Boolean,
        durationMs: Long,
    ) {
        LogBus.event(
            name = "set_target_ack",
            payload =
                mapOf(
                    "ok" to ok,
                    "dtMs" to durationMs,
                ),
        )
    }

    fun bridgeSend(
        path: String,
        cid: String?,
        nodeId: String?,
        attempt: Int,
        payloadBytes: Int,
    ) {
        LogBus.event(
            name = "bridge_send",
            payload =
                mapOf(
                    "path" to path,
                    "cid" to cid,
                    "node" to nodeId,
                    "attempt" to attempt,
                    "bytes" to payloadBytes,
                ).filterValuesNotNull(),
        )
    }

    fun bridgeRetry(
        path: String,
        cid: String?,
        nodeId: String?,
        attempt: Int,
        payloadBytes: Int,
    ) {
        LogBus.event(
            name = "bridge_retry",
            payload =
                mapOf(
                    "path" to path,
                    "cid" to cid,
                    "node" to nodeId,
                    "attempt" to attempt,
                    "bytes" to payloadBytes,
                ).filterValuesNotNull(),
        )
    }

    fun bridgeRecv(
        path: String,
        cid: String?,
        nodeId: String?,
    ) {
        LogBus.event(
            name = "bridge_recv",
            payload =
                mapOf(
                    "path" to path,
                    "cid" to cid,
                    "node" to nodeId,
                ).filterValuesNotNull(),
        )
    }

    fun bridgeError(
        path: String,
        cid: String?,
        nodeId: String?,
        error: String?,
    ) {
        LogBus.event(
            name = "bridge_error",
            payload =
                mapOf(
                    "path" to path,
                    "cid" to cid,
                    "node" to nodeId,
                    "err" to error,
                ).filterValuesNotNull(),
        )
    }

    private fun Map<String, Any?>.filterValuesNotNull(): Map<String, Any?> = filterValues { value -> value != null }
}
