package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import dev.pointtosky.mobile.ar.rememberStableCallback
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * SKY-1 sky-capture camera bind (`internalDebug`-only).
 *
 * ## Why a third bind composable
 * [dev.pointtosky.mobile.ar.CameraPreview]'s contract is explicit that its analyzer "extracts frame
 * metadata only... never reads pixel planes", and weakening that is not an option — production code
 * relies on it. [FrameContentCameraPreview] does read the plane, but it belongs to the CAM-2c
 * dot-grid track: it detects target corners per frame, and its analyzer signature is built around
 * that. The sky track needs the plane bytes themselves plus a `CaptureResult` exposure read, and must
 * not perturb the dot-grid experiment, so it binds its own session. That is the same reasoning
 * [FrameContentCameraPreview] itself documents for not reusing [dev.pointtosky.mobile.ar.CameraPreview].
 *
 * ## What one analyzed frame delivers
 * [onFrame] is called once per frame, on the analysis executor thread, with the frame's CAM-1c
 * metadata, the **packed** luma plane, and the exposure sample keyed to that exact frame's
 * `SENSOR_TIMESTAMP` (or `null` when no `CaptureResult` for it has arrived). The `ImageProxy` is
 * closed before [onFrame] returns to the caller's control — the buffer handed over is a copy owned by
 * the callback, never a view into a recycled camera buffer.
 */
@Composable
internal fun SkySessionCameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    analysisResolutionOverride: AnalysisResolutionRequest?,
    manualExposure: SkyManualExposureRequest?,
    onCameraInfo: (CameraInfo) -> Unit = {},
    onExplicitBindFailure: (String) -> Unit = {},
    onFrame: (SkyAnalyzedFrame) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }

    val currentOnCameraInfo = rememberStableCallback(onCameraInfo)
    val currentOnExplicitBindFailure = rememberStableCallback(onExplicitBindFailure)
    val currentOnFrame = rememberStableCallback(onFrame)

    // Re-bind whenever the requested exposure changes: a manual exposure is a capture-request option,
    // which CameraX only applies at bind time. Silently keeping the previous exposure while the UI
    // showed a new one would put a wrong requested value in front of the operator - the recorded
    // value would still be the truthful one read back from CaptureResult, but the session would be
    // shot at an exposure nobody chose.
    DisposableEffect(cameraSelector, analysisResolutionOverride, manualExposure) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        val session = CameraSessionLifecycle()
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val exposureStore = SkyExposureSampleStore()

        val captureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    exposureStore.record(result)
                }
            }

        scope.launch {
            val cameraProvider = context.getSkySessionCameraProvider()
            if (session.isDisposed) return@launch

            val preview =
                androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(
                        previewView.surfaceProvider,
                    )
                }

            val imageAnalysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .apply {
                        if (analysisResolutionOverride != null) {
                            setResolutionSelector(
                                ResolutionSelector
                                    .Builder()
                                    .setAspectRatioStrategy(aspectRatioStrategyFor(analysisResolutionOverride.family))
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(
                                                analysisResolutionOverride.widthPx,
                                                analysisResolutionOverride.heightPx,
                                            ),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    ).build(),
                            )
                        }
                    }.applySkyCaptureOptions(manualExposure, captureCallback)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                val metadata = ImageProxyFrameMetadataSource(imageProxy).toCameraFrameMetadata()
                                val luma = imageProxy.toLumaBufferOrNull()
                                if (luma != null) {
                                    currentOnFrame(
                                        SkyAnalyzedFrame(
                                            metadata = metadata,
                                            lumaData = luma.data,
                                            lumaWidthPx = luma.widthPx,
                                            lumaHeightPx = luma.heightPx,
                                            lumaRowStridePx = luma.rowStridePx,
                                            exposure = exposureStore.takeFor(metadata.timestampNanos),
                                        ),
                                    )
                                } else {
                                    MobileLog.cameraFrameAnalysisFailed("sky_luma_plane_unavailable")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                MobileLog.cameraFrameAnalysisFailed(e.javaClass.simpleName)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

            var boundCamera: Camera? = null
            val bindFailure: RuntimeException? =
                try {
                    boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    null
                } catch (e: IllegalStateException) {
                    e
                } catch (e: IllegalArgumentException) {
                    e
                }

            if (bindFailure == null) {
                val boundSessionIsActive =
                    session.confirmBound {
                        imageAnalysis.clearAnalyzer()
                        cameraProvider.unbind(preview, imageAnalysis)
                    }
                if (boundSessionIsActive) {
                    MobileLog.cameraAnalysisBound()
                    val camera = checkNotNull(boundCamera) { "boundCamera must be set once bind succeeded" }
                    if (session.isDisposed) return@launch
                    currentOnCameraInfo(camera.cameraInfo)
                }
                return@launch
            }

            imageAnalysis.clearAnalyzer()
            session.shutdownExecutorOnce { analysisExecutor.shutdownNow() }
            val reason =
                if (bindFailure is IllegalStateException) "explicit_selector_illegal_state" else "explicit_selector_illegal_argument"
            MobileLog.cameraAnalysisBindFailed(reason)
            currentOnExplicitBindFailure(reason)
        }

        onDispose {
            session.markDisposed()
            job.cancel()
            session.cleanupAndShutdown { analysisExecutor.shutdownNow() }
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}

/**
 * One analyzed sky frame, handed to the capture callback on the analysis thread.
 *
 * [lumaData] is the packed plane [toLumaBufferOrNull] produced — a fresh array per frame, already
 * detached from the `ImageProxy`, so the callback may write it to disk after the proxy is closed.
 * `LumaBuffer` itself is not exposed here: it belongs to the CAM-2c dot-grid detector's own file, and
 * the sky stream should not grow a dependency on that track's types beyond the one plane-reading
 * extension it deliberately reuses.
 */
internal data class SkyAnalyzedFrame(
    val metadata: CameraFrameMetadata,
    val lumaData: ByteArray,
    val lumaWidthPx: Int,
    val lumaHeightPx: Int,
    val lumaRowStridePx: Int,
    val exposure: SkyExposureSample?,
) {
    // A ByteArray field makes the generated equals/hashCode reference-based, which is both surprising
    // and useless here. Identity is the honest answer for a per-frame pixel buffer, so it is stated
    // explicitly rather than left to a data class's misleading default.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

private suspend fun Context.getSkySessionCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            { continuation.resume(providerFuture.get()) },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { providerFuture.cancel(true) }
    }
