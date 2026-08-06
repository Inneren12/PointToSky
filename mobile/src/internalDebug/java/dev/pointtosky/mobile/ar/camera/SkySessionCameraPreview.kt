package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import dev.pointtosky.mobile.ar.rememberStableCallback
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
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
 * ## One epoch per bind
 * Every `DisposableEffect` pass mints a new, monotonically increasing epoch and passes it to every
 * callback. That is what lets [SkySessionCaptureSession] tell a frame from the live bind apart from
 * one still draining out of the previous bind's analysis queue — see [SkyCaptureGenerationTracker].
 * The counter is `remember`ed, so it survives recomposition and never restarts.
 *
 * ## Everything joins on the analysis thread
 * `CaptureResult` arrives on the camera callback thread and `ImageProxy` on the analysis executor.
 * Rather than lock the join, the capture callback **posts** its sample to that same single-threaded
 * analysis executor. Every offer, every match and every delivery therefore happens on one thread, in
 * arrival order, with no lock and no chance of two matches racing into the recorder.
 *
 * [onFrame] is called only for a frame whose exposure has been matched by exact `SENSOR_TIMESTAMP`.
 * [onJoinDrops] reports everything released without a pair, so a HUD can say how many frames were
 * lost and why instead of showing a silently lower recorded count.
 */
@Composable
internal fun SkySessionCameraPreview(
    modifier: Modifier = Modifier,
    configuration: SkyCaptureConfiguration,
    onBind: (epoch: Long, configuration: SkyCaptureConfiguration, cameraInfo: CameraInfo) -> Unit = { _, _, _ -> },
    onExplicitBindFailure: (String) -> Unit = {},
    onFrame: (epoch: Long, configuration: SkyCaptureConfiguration, joined: SkyJoinedFrame) -> Unit = { _, _, _ -> },
    onJoinDrops: (epoch: Long, drops: List<SkyJoinDrop>) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        }
    val bindEpochs = remember { AtomicLong(0L) }

    // rememberStableCallback takes a single-argument lambda, so each multi-argument callback is
    // wrapped in the payload it carries rather than given its own bespoke stabiliser.
    val currentOnBind =
        rememberStableCallback<SkyBindEvent> { event -> onBind(event.epoch, event.configuration, event.cameraInfo) }
    val currentOnExplicitBindFailure = rememberStableCallback(onExplicitBindFailure)
    val currentOnFrame =
        rememberStableCallback<SkyFrameEvent> { event -> onFrame(event.epoch, event.configuration, event.joined) }
    val currentOnJoinDrops = rememberStableCallback<SkyJoinDropEvent> { event -> onJoinDrops(event.epoch, event.drops) }

    // Keyed on the whole configuration: the physical camera, the analysis resolution and the manual
    // exposure are all bind-time decisions CameraX cannot change in place. Silently keeping the
    // previous bind while the UI showed a new setting would record a session at settings nobody chose.
    DisposableEffect(configuration) {
        val epoch = bindEpochs.incrementAndGet()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        val session = CameraSessionLifecycle()
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val join = SkyExposureJoin()

        fun deliver(result: SkyJoinResult) {
            result.matched?.let { currentOnFrame(SkyFrameEvent(epoch, configuration, it)) }
            if (result.dropped.isNotEmpty()) currentOnJoinDrops(SkyJoinDropEvent(epoch, result.dropped))
        }

        val captureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val sample = skyExposureSampleOf(result)
                    // Hop to the analysis thread rather than locking the join: see the class KDoc.
                    // A rejection means the executor is already shut down, i.e. this bind is gone.
                    try {
                        analysisExecutor.execute { deliver(join.offerExposure(sample)) }
                    } catch (_: RejectedExecutionException) {
                        MobileLog.cameraFrameAnalysisFailed("sky_exposure_after_unbind")
                    }
                }
            }

        scope.launch {
            val cameraProvider = context.getSkySessionCameraProvider()
            if (session.isDisposed) return@launch

            val preview =
                androidx.camera.core.Preview
                    .Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector
                            .Builder()
                            .setAspectRatioStrategy(aspectRatioStrategyFor(configuration.resolution.family))
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(
                                        configuration.resolution.widthPx,
                                        configuration.resolution.heightPx,
                                    ),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            ).build(),
                    ).applySkyCaptureOptions(configuration.exposure, captureCallback)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                val metadata = ImageProxyFrameMetadataSource(imageProxy).toCameraFrameMetadata()
                                val luma = imageProxy.toLumaBufferOrNull()
                                if (luma != null) {
                                    deliver(
                                        join.offerFrame(
                                            SkyAnalyzedFrame(
                                                metadata = metadata,
                                                lumaData = luma.data,
                                                lumaWidthPx = luma.widthPx,
                                                lumaHeightPx = luma.heightPx,
                                                lumaRowStridePx = luma.rowStridePx,
                                            ),
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
                    boundCamera =
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            explicitPhysicalCameraSelector(configuration.physicalCameraId),
                            preview,
                            imageAnalysis,
                        )
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
                    currentOnBind(SkyBindEvent(epoch, configuration, camera.cameraInfo))
                }
                return@launch
            }

            imageAnalysis.clearAnalyzer()
            session.shutdownExecutorOnce { analysisExecutor.shutdownNow() }
            val reason =
                if (bindFailure is IllegalStateException) {
                    "explicit_selector_illegal_state"
                } else {
                    "explicit_selector_illegal_argument"
                }
            MobileLog.cameraAnalysisBindFailed(reason)
            currentOnExplicitBindFailure(reason)
        }

        onDispose {
            session.markDisposed()
            job.cancel()
            session.cleanupAndShutdown { analysisExecutor.shutdownNow() }
            // Whatever never completed a pair is reported once, so the HUD's dropped count accounts for
            // the whole bind rather than quietly forgetting its tail.
            val pending = join.drain()
            if (pending.isNotEmpty()) currentOnJoinDrops(SkyJoinDropEvent(epoch, pending))
        }
    }

    AndroidView(modifier = modifier, factory = { previewView })
}

/** A successful bind, tagged with the epoch that identifies it. */
private data class SkyBindEvent(
    val epoch: Long,
    val configuration: SkyCaptureConfiguration,
    val cameraInfo: CameraInfo,
)

/** One joined frame/exposure pair, tagged with the epoch of the bind that produced it. */
private data class SkyFrameEvent(
    val epoch: Long,
    val configuration: SkyCaptureConfiguration,
    val joined: SkyJoinedFrame,
)

/** Everything one offer (or one teardown) released without completing a pair. */
private data class SkyJoinDropEvent(
    val epoch: Long,
    val drops: List<SkyJoinDrop>,
)

/**
 * One analyzed sky frame, handed to the join on the analysis thread.
 *
 * [lumaData] is the packed plane [toLumaBufferOrNull] produced — a fresh array per frame, already
 * detached from the `ImageProxy`, so the callback may write it to disk after the proxy is closed.
 * `LumaBuffer` itself is not exposed here: it belongs to the CAM-2c dot-grid detector's own file, and
 * the sky stream should not grow a dependency on that track's types beyond the one plane-reading
 * extension it deliberately reuses.
 *
 * There is no exposure field. A frame and its `CaptureResult` are joined by exact `SENSOR_TIMESTAMP`
 * in [SkyExposureJoin], and until that join completes there is nothing truthful to put here — an
 * `exposure: SkyExposureSample?` on this type is precisely how a late result ends up recorded as
 * "the device reported nothing".
 */
internal data class SkyAnalyzedFrame(
    val metadata: CameraFrameMetadata,
    val lumaData: ByteArray,
    val lumaWidthPx: Int,
    val lumaHeightPx: Int,
    val lumaRowStridePx: Int,
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
