package dev.pointtosky.mobile.ar

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.pointtosky.mobile.ar.camera.CameraFrameAnalyzer
import dev.pointtosky.mobile.ar.camera.CameraFrameMetadataProvider
import dev.pointtosky.mobile.ar.camera.CameraSessionLifecycle
import dev.pointtosky.mobile.logging.MobileLog
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** CAM-1c: short, non-device-specific bind-failure/fallback reason categories for [MobileLog]. */
private object CameraBindFailureReason {
    const val ILLEGAL_STATE = "illegal_state"
    const val ILLEGAL_ARGUMENT_FALLBACK_PREVIEW_ONLY = "illegal_argument_fallback_preview_only"
    const val ILLEGAL_STATE_FALLBACK_FAILED = "illegal_state_fallback_failed"
    const val ILLEGAL_ARGUMENT_FALLBACK_FAILED = "illegal_argument_fallback_failed"
}

/**
 * CAM-1c: binds CameraX `Preview` and `ImageAnalysis` together in one [ProcessCameraProvider.bindToLifecycle]
 * call. `ImageAnalysis` extracts frame metadata only (timestamp, buffer size, rotation, crop rect) -
 * it never reads pixel planes - via [CameraFrameAnalyzer]. The metadata provider is `remember`ed
 * for this composition (throttled debug logging only); no production call site reads it yet, and
 * the AR renderer is unchanged.
 *
 * If the device rejects the combined `Preview` + `ImageAnalysis` bind (`IllegalArgumentException`),
 * `ImageAnalysis` is abandoned and a Preview-*only* bind is retried, so a device that supports
 * `Preview` alone does not lose the AR camera view entirely just because the optional metadata
 * use case is unsupported. See "Preview-only fallback" below.
 *
 * This composable, not the bound `Activity` lifecycle, owns whichever use case(s) end up bound and
 * the analysis executor: navigating away does not necessarily stop the `Activity` lifecycle, so
 * [CameraSessionLifecycle] guarantees that *disposing this composable* always clears the analyzer
 * (if it was ever attached) and unbinds exactly the use case(s) this composition bound - never a
 * blanket `unbindAll()` - before the executor is shut down, even if disposal races with an
 * in-flight bind. See `docs/camera_coordinate_calibration_contract.md`.
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val metadataProvider = remember { CameraFrameMetadataProvider() }

    DisposableEffect(Unit) {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        val session = CameraSessionLifecycle()
        // Dedicated single-thread executor: ImageAnalysis.Analyzer callbacks must not run on the
        // main thread. Shut down exactly once - either early, if the combined bind is rejected and
        // ImageAnalysis is abandoned, or on dispose (via session.cleanupAndShutdown) - so it never
        // leaks across recomposition/navigation, and never outlives the use case bound to it.
        val analysisExecutor = Executors.newSingleThreadExecutor()

        scope.launch {
            val cameraProvider = context.getCameraProvider()
            // Nothing between here and the bindToLifecycle() calls below suspends, so a cancelled
            // job alone cannot stop this coroutine mid-flight - the explicit disposed check is the
            // real guard.
            if (session.isDisposed) return@launch

            val preview =
                Preview.Builder().build().also { builder ->
                    builder.setSurfaceProvider(previewView.surfaceProvider)
                }
            val imageAnalysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            analysisExecutor,
                            CameraFrameAnalyzer(
                                metadataSink = metadataProvider,
                                onFrameFailure = metadataProvider::recordFailedFrame,
                            ),
                        )
                    }

            val combinedBindFailure: RuntimeException? =
                try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    null
                } catch (e: IllegalStateException) {
                    e
                } catch (e: IllegalArgumentException) {
                    e
                }

            if (combinedBindFailure == null) {
                // Combined bind succeeded. Register this bind's own cleanup and re-check disposal:
                // if the composable was disposed while bindToLifecycle() was running (it does not
                // suspend), confirmBound() runs that cleanup immediately instead of leaving a live
                // session.
                val boundSessionIsActive =
                    session.confirmBound {
                        imageAnalysis.clearAnalyzer()
                        cameraProvider.unbind(preview, imageAnalysis)
                    }
                if (boundSessionIsActive) {
                    MobileLog.cameraAnalysisBound()
                }
                return@launch
            }

            // The combined bind failed. Either way, ImageAnalysis is abandoned for this session:
            // clear its analyzer and shut down the (now-unused) executor immediately rather than
            // waiting for eventual disposal - shutdownExecutorOnce guarantees this does not double
            // up with onDispose's own shutdown later, however this coroutine ends.
            imageAnalysis.clearAnalyzer()
            session.shutdownExecutorOnce { analysisExecutor.shutdownNow() }

            if (combinedBindFailure is IllegalStateException) {
                // Lifecycle might be stopped before binding; do not retry Preview-only, since the
                // lifecycle owner itself may already be gone.
                MobileLog.cameraAnalysisBindFailed(CameraBindFailureReason.ILLEGAL_STATE)
                return@launch
            }

            // IllegalArgumentException: this device/config rejects the combined Preview +
            // ImageAnalysis use-case combination (e.g. a legacy-level camera, or no back camera on
            // a camera-less device/emulator). Fall back to a Preview-only bind so the AR screen
            // does not go black on a device that still supports Preview alone.
            MobileLog.cameraAnalysisBindFailed(CameraBindFailureReason.ILLEGAL_ARGUMENT_FALLBACK_PREVIEW_ONLY)

            // Re-check disposal before attempting the fallback bind - it may have happened while
            // handling the failure above, and bindToLifecycle() does not suspend, so this is the
            // same explicit guard as the initial isDisposed check.
            if (session.isDisposed) return@launch

            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            } catch (_: IllegalStateException) {
                MobileLog.cameraAnalysisBindFailed(CameraBindFailureReason.ILLEGAL_STATE_FALLBACK_FAILED)
                return@launch
            } catch (_: IllegalArgumentException) {
                MobileLog.cameraAnalysisBindFailed(CameraBindFailureReason.ILLEGAL_ARGUMENT_FALLBACK_FAILED)
                return@launch
            }

            // Preview-only fallback succeeded. Register its cleanup - unbind Preview alone; never
            // reference or unbind ImageAnalysis again, it was never bound in this path - with the
            // same late-dispose race handling as the combined-bind success path above.
            val previewSessionIsActive =
                session.confirmBound {
                    cameraProvider.unbind(preview)
                }
            if (previewSessionIsActive) {
                MobileLog.cameraPreviewBoundWithoutAnalysis()
            }
        }

        onDispose {
            session.markDisposed()
            job.cancel()
            session.cleanupAndShutdown {
                // shutdownNow(): immediate teardown on navigation away. By this point the
                // analyzer is already cleared and the use case(s) already unbound (via the
                // registered cleanup above, or the early shutdown on a combined-bind failure), so
                // at most one already-queued metadata-extraction task is discarded rather than run
                // - it only reads timestamp/size/rotation and closes the frame, never pixel data,
                // so discarding it is safe. Any frame that was already running completes normally
                // (this analyzer does not block, so interruption does not truncate its
                // finally-close). If ImageAnalysis was never bound (Preview-only fallback path) or
                // the executor was already shut down early, this call is a no-op by construction.
                analysisExecutor.shutdownNow()
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView },
    )
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                continuation.resume(providerFuture.get())
            },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation {
            providerFuture.cancel(true)
        }
    }
