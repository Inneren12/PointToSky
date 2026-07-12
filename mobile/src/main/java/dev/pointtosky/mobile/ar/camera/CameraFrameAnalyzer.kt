package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CancellationException

/**
 * CameraX [ImageAnalysis.Analyzer] that extracts frame metadata only (CAM-1c) — it never reads
 * `imageProxy.planes`, `imageProxy.image`, or any pixel row/stride.
 *
 * [analyze] is CameraX's entry point; the actual logic lives in [analyzeSource], which takes a
 * [CloseableFrameMetadataSource] so it is unit-testable with a plain fake, without a real
 * `ImageProxy`. Every source is closed exactly once, always in `finally`, whether extraction
 * succeeds, extraction throws (invalid metadata), or [metadataSink] itself throws. The frame is
 * never retained past this call.
 *
 * `CancellationException` is rethrown rather than swallowed, matching the CAM-1b convention in
 * [dev.pointtosky.mobile.ar.camera.resolveCameraIntrinsics] — extraction failures are handled
 * without crashing the camera pipeline, but cancellation must propagate.
 */
class CameraFrameAnalyzer(
    private val metadataSink: CameraFrameMetadataSink,
    private val onFrameFailure: () -> Unit = {},
) : ImageAnalysis.Analyzer {
    override fun analyze(imageProxy: ImageProxy) {
        analyzeSource(ImageProxyFrameMetadataSource(imageProxy))
    }

    internal fun analyzeSource(source: CloseableFrameMetadataSource) {
        try {
            metadataSink.onFrame(source.toCameraFrameMetadata())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MobileLog.cameraFrameAnalysisFailed(e.javaClass.simpleName)
            onFrameFailure()
        } finally {
            source.close()
        }
    }
}
