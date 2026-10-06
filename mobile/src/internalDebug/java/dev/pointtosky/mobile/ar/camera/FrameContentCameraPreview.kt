package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ResolutionInfo
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
import java.util.concurrent.RejectedExecutionException
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
 * One analyzed frame of this experiment, handed to the PTS-03 [SkyExposureJoin] on the analysis thread:
 * the frame metadata plus the detection run on that same `ImageProxy`'s luma plane.
 */
internal data class FrameContentAnalyzedFrame(
    val metadata: CameraFrameMetadata,
    val detection: FrameContentDetectionResult,
) : SkyJoinFrame {
    override val sensorTimestampNanos: Long get() = metadata.timestampNanos
}

/** What a successful bind reports once: the bound camera and CameraX's post-bind stream configuration. */
internal data class FrameContentBindInfo(
    val cameraInfo: CameraInfo,
    /** The bound camera's own Camera2 ID (`Camera2CameraInfo.getCameraId()`); the logical ID for an unpinned bind. */
    val boundCameraId: String?,
    val streamConfiguration: Pts03StreamConfiguration,
)

/**
 * PTS-03: the handle the screen uses to finalize the live bind's join before writing the authoritative
 * export. The bind installs [finalizer] while it is live and clears it on dispose; [requestFinalize]
 * returns `false` when no live bind is attached (nothing to finalize).
 */
internal class FrameContentJoinControl {
    @Volatile
    internal var finalizer: (() -> Boolean)? = null

    fun requestFinalize(): Boolean = finalizer?.invoke() ?: false
}

/**
 * The PTS-03 join depth for this experiment. The frame side holds only metadata and a detection result (no
 * pixels), so it can be deeper than SKY-1's luma-holding default without a memory cost; the depth absorbs
 * `KEEP_ONLY_LATEST` dropping frames whose `CaptureResult`s still arrive.
 */
internal const val FRAME_CONTENT_JOIN_CAPACITY: Int = 16

/**
 * Binds CameraX `Preview` + `ImageAnalysis` for [cameraSelector] (an explicit physical-camera selector
 * — see [explicitPhysicalCameraSelector] — or, for the PTS-03 A1 session, the plain logical back camera),
 * reading the analysis `ImageAnalysis.Analyzer`'s luma plane directly (task §1).
 *
 * ## PTS-03: every delivered frame carries its own `CaptureResult`
 * A `Camera2Interop` session capture callback on the same session builds one [SkyCaptureResultSnapshot] per
 * result and posts it to the analysis executor, where an [SkyExposureJoin] pairs it with the analyzed frame
 * whose `imageInfo.timestamp` is **exactly** its `SENSOR_TIMESTAMP` — the SKY-1 mechanism, reused. [onFrame]
 * is therefore called only for joined frames; everything released unjoined is counted in the
 * [SkyJoinStatistics] passed to [onJoinStatistics] after every offer, so a low match rate is visible
 * rather than silent.
 *
 * [requestedDistortionMode] other than [Pts03DistortionModeRequest.DEVICE_DEFAULT] is set as
 * `DISTORTION_CORRECTION_MODE` on both use cases' capture-request options (the same value, so the merged
 * repeating request is unambiguous); the effective mode is read back per frame, never assumed.
 *
 * [pinZoom] pins the zoom ratio to [EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO] as the explicit-physical
 * experiments always have; the A1 logical session passes `false` so the bind matches production, which
 * never sets zoom.
 */
@OptIn(ExperimentalCamera2Interop::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
internal fun FrameContentCameraPreview(
    modifier: Modifier = Modifier,
    cameraSelector: CameraSelector,
    analysisResolutionOverride: AnalysisResolutionRequest?,
    targetSpec: FrameContentTargetSpec,
    detectionTolerances: FrameContentDetectionTolerances,
    requestedDistortionMode: Pts03DistortionModeRequest = Pts03DistortionModeRequest.DEVICE_DEFAULT,
    pinZoom: Boolean = true,
    onCameraInfo: (CameraInfo) -> Unit = {},
    onBindInfo: (FrameContentBindInfo) -> Unit = {},
    onExplicitBindFailure: (String) -> Unit = {},
    onFrame: (CameraFrameMetadata, FrameContentDetectionResult, SkyCaptureResultSnapshot) -> Unit = { _, _, _ -> },
    onJoinStatistics: (SkyJoinStatistics) -> Unit = {},
    joinControl: FrameContentJoinControl? = null,
    onJoinFinalized: (SkyJoinStatistics) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }

    val currentOnCameraInfo = rememberStableCallback(onCameraInfo)
    val currentOnBindInfo = rememberStableCallback(onBindInfo)
    val currentOnExplicitBindFailure = rememberStableCallback(onExplicitBindFailure)
    val currentOnFrame = rememberStableCallback<SkyJoinedFrame<FrameContentAnalyzedFrame>> { joined ->
        onFrame(joined.frame.metadata, joined.frame.detection, joined.captureResult)
    }
    val currentOnJoinStatistics = rememberStableCallback(onJoinStatistics)
    val currentOnJoinFinalized = rememberStableCallback(onJoinFinalized)

    DisposableEffect(Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        val session = CameraSessionLifecycle()
        val analysisExecutor = Executors.newSingleThreadExecutor()
        // Touched only on analysisExecutor (offers) and once more in onDispose after the executor is shut
        // down — the same single-thread discipline SkySessionCameraPreview documents.
        val join = SkyExposureJoin<FrameContentAnalyzedFrame>(capacity = FRAME_CONTENT_JOIN_CAPACITY)

        // Statistics are reported after every frame offer and after any match, which keeps them current
        // without a UI update for every unmatched CaptureResult of a frame KEEP_ONLY_LATEST dropped.
        fun deliver(
            result: SkyJoinResult<FrameContentAnalyzedFrame>,
            fromFrameOffer: Boolean,
        ) {
            result.matched?.let { currentOnFrame(it) }
            if (fromFrameOffer || result.matched != null) currentOnJoinStatistics(join.statistics)
        }

        // Finalize runs on the analysis executor, so it is serialized with every offer: after it, the join
        // accepts nothing, its pending entries are in the pending-at-stop counts, and the statistics handed
        // to onJoinFinalized are final. Repeated requests return the same statistics.
        joinControl?.finalizer = {
            try {
                analysisExecutor.execute { currentOnJoinFinalized(join.finalizeJoin()) }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        val captureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val snapshot = skyCaptureResultSnapshotOf(result)
                    try {
                        analysisExecutor.execute { deliver(join.offerExposure(snapshot), fromFrameOffer = false) }
                    } catch (_: RejectedExecutionException) {
                        MobileLog.cameraFrameAnalysisFailed("frame_content_capture_result_after_unbind")
                    }
                }
            }

        scope.launch {
            val cameraProvider = context.getFrameContentCameraProvider()
            if (session.isDisposed) return@launch

            val previewBuilder = androidx.camera.core.Preview.Builder()
            Camera2Interop.Extender(previewBuilder).applyPts03DistortionMode(requestedDistortionMode)
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisBuilder =
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
            Camera2Interop.Extender(analysisBuilder).apply {
                setSessionCaptureCallback(captureCallback)
                applyPts03DistortionMode(requestedDistortionMode)
            }
            val imageAnalysis =
                analysisBuilder
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
                                deliver(join.offerFrame(FrameContentAnalyzedFrame(frameMetadata, detection)), fromFrameOffer = true)
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
                    if (pinZoom) {
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
                    }
                    if (session.isDisposed) return@launch
                    currentOnBindInfo(
                        FrameContentBindInfo(
                            cameraInfo = camera.cameraInfo,
                            boundCameraId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull(),
                            streamConfiguration =
                                Pts03StreamConfiguration(
                                    analysisResolutionInfo = imageAnalysis.resolutionInfo?.toPts03(),
                                    previewResolutionInfo = preview.resolutionInfo?.toPts03(),
                                ),
                        ),
                    )
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
            joinControl?.finalizer = null
            session.markDisposed()
            job.cancel()
            session.cleanupAndShutdown { analysisExecutor.shutdownNow() }
            // Best effort only: the session may already be gone. The authoritative final statistics come
            // from an explicit finalize (FrameContentJoinControl) before the export is written.
            join.drain()
            currentOnJoinStatistics(join.statistics)
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}

/**
 * Sets `DISTORTION_CORRECTION_MODE` on the request options when a mode other than the device default was
 * asked for. The key exists only on API 28+; below that nothing is set and the effective mode is reported
 * per frame as `API_UNSUPPORTED`.
 */
@OptIn(ExperimentalCamera2Interop::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun <T> Camera2Interop.Extender<T>.applyPts03DistortionMode(request: Pts03DistortionModeRequest) {
    val mode = request.camera2Value ?: return
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, mode)
    }
}

private fun ResolutionInfo.toPts03(): Pts03ResolutionInfo =
    Pts03ResolutionInfo(
        widthPx = resolution.width,
        heightPx = resolution.height,
        cropRect = cropRect.toPts03IntRect(),
        rotationDegrees = rotationDegrees,
    )
