package dev.pointtosky.mobile.ar.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import dev.pointtosky.mobile.ar.EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO
import dev.pointtosky.mobile.ar.rememberStableCallback
import dev.pointtosky.mobile.logging.MobileLog
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §1). A dedicated CameraX
 * bind composable, separate from [dev.pointtosky.mobile.ar.CameraPreview] on purpose:
 * [dev.pointtosky.mobile.ar.CameraPreview]'s own contract (see its `Preview`+`ImageAnalysis` binding
 * KDoc) is explicit that its `ImageAnalysis.Analyzer` "extracts frame metadata only... never reads
 * pixel planes" — this experiment's whole point is reading pixel data (the luma plane), so it cannot
 * reuse that composable without either weakening that guarantee (unacceptable — other production code
 * relies on it) or duplicating the bind. This file does the latter, deliberately narrower than
 * [dev.pointtosky.mobile.ar.CameraPreview]'s own bind (no Preview-only fallback: an explicit physical
 * camera bind either succeeds or this attempt reports failure, matching
 * [PhysicalCameraBindingExperiment]'s own philosophy for the same experiment family).
 */

/** Extracts a [LumaBuffer] from [ImageProxy.getPlanes]`[0]` (the Y/luma plane of a `YUV_420_888`
 * image), packing rows into a contiguous `rowStridePx == widthPx` array regardless of the plane's own
 * `pixelStride`/`rowStride`. Returns `null` when there is no plane to read (never fabricated). */
internal fun ImageProxy.toLumaBufferOrNull(): LumaBuffer? {
    val yPlane = planes.getOrNull(0) ?: return null
    val width = width
    val height = height
    if (width <= 0 || height <= 0) return null
    val rowStride = yPlane.rowStride
    val pixelStride = yPlane.pixelStride
    if (rowStride <= 0 || pixelStride <= 0) return null

    val source: ByteBuffer = yPlane.buffer.duplicate()
    val packed = ByteArray(width * height)

    return try {
        if (pixelStride == 1) {
            for (row in 0 until height) {
                source.position(row * rowStride)
                source.get(packed, row * width, width)
            }
        } else {
            val rowBuffer = ByteArray(rowStride)
            for (row in 0 until height) {
                source.position(row * rowStride)
                val available = (source.remaining()).coerceAtMost(rowStride)
                source.get(rowBuffer, 0, available)
                for (col in 0 until width) {
                    val srcIndex = col * pixelStride
                    packed[row * width + col] = if (srcIndex < available) rowBuffer[srcIndex] else 0
                }
            }
        }
        LumaBuffer(packed, width, height, width)
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

private suspend fun Context.getFrameContentCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            { continuation.resume(providerFuture.get()) },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { providerFuture.cancel(true) }
    }

/**
 * Binds CameraX `Preview` + `ImageAnalysis` for [cameraSelector] (an explicit physical-camera selector
 * — see [explicitPhysicalCameraSelector]), reading the analysis `ImageAnalysis.Analyzer`'s luma plane
 * directly (task §1). [onFrame] is called with each frame's metadata and this frame's
 * [FrameContentDetectionResult] together, exactly once per analyzed frame, on the analysis executor
 * thread — mirroring [dev.pointtosky.mobile.ar.camera.CameraFrameAnalyzer]'s own direct-call convention.
 * [onCameraInfo]/[onExplicitBindFailure] mirror [dev.pointtosky.mobile.ar.CameraPreview]'s own explicit
 * -selector semantics: no Preview-only fallback for a bind failure, since substituting an unrequested
 * camera would defeat this experiment's whole purpose.
 */
@Composable
internal fun FrameContentCameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    analysisResolutionOverride: AnalysisResolutionRequest?,
    targetSpec: FrameContentTargetSpec,
    detectionTolerances: FrameContentDetectionTolerances,
    onCameraInfo: (CameraInfo) -> Unit = {},
    onExplicitBindFailure: (String) -> Unit = {},
    onFrame: (CameraFrameMetadata, FrameContentDetectionResult) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }

    val currentOnCameraInfo = rememberStableCallback(onCameraInfo)
    val currentOnExplicitBindFailure = rememberStableCallback(onExplicitBindFailure)
    val currentOnFrame = rememberStableCallback<Pair<CameraFrameMetadata, FrameContentDetectionResult>> { (frame, detection) ->
        onFrame(frame, detection)
    }

    DisposableEffect(Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        val session = CameraSessionLifecycle()
        val analysisExecutor = Executors.newSingleThreadExecutor()

        scope.launch {
            val cameraProvider = context.getFrameContentCameraProvider()
            if (session.isDisposed) return@launch

            val preview =
                androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .apply {
                        if (analysisResolutionOverride != null) {
                            setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(aspectRatioStrategyFor(analysisResolutionOverride.family))
                                    .setResolutionStrategy(
                                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                                            android.util.Size(analysisResolutionOverride.widthPx, analysisResolutionOverride.heightPx),
                                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    )
                                    .build(),
                            )
                        }
                    }
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                val source = ImageProxyFrameMetadataSource(imageProxy)
                                val frameMetadata = source.toCameraFrameMetadata()
                                val luma = imageProxy.toLumaBufferOrNull()
                                val detection =
                                    if (luma != null) {
                                        detectFrameContentTargetCorners(luma, targetSpec, detectionTolerances)
                                    } else {
                                        FrameContentDetectionResult.InsufficientOrAmbiguousGrid("luma plane unavailable", 0)
                                    }
                                currentOnFrame(frameMetadata to detection)
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
                    val zoomResult =
                        camera.cameraControl
                            .setZoomRatio(EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO)
                            .let { future ->
                                suspendCancellableCoroutine { continuation ->
                                    future.addListener(
                                        { continuation.resume(runCatching { future.get() }) },
                                        ContextCompat.getMainExecutor(context),
                                    )
                                    continuation.invokeOnCancellation { future.cancel(true) }
                                }
                            }
                    if (zoomResult.isFailure) {
                        currentOnExplicitBindFailure("explicit_selector_zoom_failed")
                        return@launch
                    }
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
