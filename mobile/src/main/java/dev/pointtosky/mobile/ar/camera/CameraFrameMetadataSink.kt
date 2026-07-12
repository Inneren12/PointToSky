package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the latest [CameraFrameMetadata] for one bound camera session (CAM-1c). Deliberately
 * has no queueing semantics — implementations must retain at most the single latest frame, never
 * an `ImageProxy`, and callers must not accumulate frames across calls.
 */
interface CameraFrameMetadataSink {
    fun onFrame(metadata: CameraFrameMetadata)
}

/** Debug snapshot for a minimal readout (CAM-1c) — no pixels, only counters and the latest metadata. */
data class CameraFrameDebugState(
    val latest: CameraFrameMetadata?,
    val frameCount: Long,
    val failedFrameCount: Long,
)

/**
 * Thread-safe, latest-value-only [CameraFrameMetadataSink] (CAM-1c). Backed by a [StateFlow], which
 * already gives latest-value semantics and thread-safe publication with no unbounded queue — a new
 * [onFrame] call simply replaces the previous value, it never accumulates.
 *
 * Owned per bound camera session by the camera composable/controller, which must stop calling
 * [onFrame] (and drop its reference) on lifecycle disposal; this class itself holds no lifecycle
 * reference and does no cleanup of its own beyond going out of scope.
 */
class CameraFrameMetadataProvider : CameraFrameMetadataSink {
    private val _latest = MutableStateFlow<CameraFrameMetadata?>(null)
    val latest: StateFlow<CameraFrameMetadata?> = _latest.asStateFlow()

    private val frameCount = AtomicLong(0L)
    private val failedFrameCount = AtomicLong(0L)

    override fun onFrame(metadata: CameraFrameMetadata) {
        _latest.value = metadata
        val count = frameCount.incrementAndGet()
        if (count % METADATA_LOG_INTERVAL == 1L) {
            MobileLog.cameraFrameMetadata(
                widthPx = metadata.bufferWidthPx,
                heightPx = metadata.bufferHeightPx,
                rotationDegrees = metadata.rotationDegrees,
                frameCount = count,
            )
        }
    }

    /** Called by the analyzer when a frame could not be turned into [CameraFrameMetadata]. */
    fun recordFailedFrame() {
        failedFrameCount.incrementAndGet()
    }

    fun debugState(): CameraFrameDebugState =
        CameraFrameDebugState(
            latest = _latest.value,
            frameCount = frameCount.get(),
            failedFrameCount = failedFrameCount.get(),
        )

    private companion object {
        /** Throttle: log a metadata summary on the 1st frame and every 30th frame after. */
        const val METADATA_LOG_INTERVAL = 30L
    }
}
