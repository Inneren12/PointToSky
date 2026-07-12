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

/**
 * CAM-1c: binds CameraX `Preview` and `ImageAnalysis` together in one [ProcessCameraProvider.bindToLifecycle]
 * call. `ImageAnalysis` extracts frame metadata only (timestamp, buffer size, rotation, crop rect) -
 * it never reads pixel planes - via [CameraFrameAnalyzer]. The metadata provider is `remember`ed
 * for this composition (throttled debug logging only); no production call site reads it yet, and
 * the AR renderer is unchanged.
 *
 * This composable, not the bound `Activity` lifecycle, owns the `Preview`/`ImageAnalysis` use
 * cases and the analysis executor: navigating away does not necessarily stop the `Activity`
 * lifecycle, so [CameraSessionLifecycle] guarantees that *disposing this composable* always clears
 * the analyzer and unbinds exactly these two use cases - never a blanket `unbindAll()` - before the
 * executor is shut down, even if disposal races with an in-flight bind. See
 * `docs/camera_coordinate_calibration_contract.md`.
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
        // main thread. Shut down on dispose (via session.cleanupAndShutdown) so it never leaks
        // across recomposition/navigation, and never outlives the use case bound to it.
        val analysisExecutor = Executors.newSingleThreadExecutor()

        scope.launch {
            val cameraProvider = context.getCameraProvider()
            // Nothing between here and bindToLifecycle() below suspends, so a cancelled job alone
            // cannot stop this coroutine mid-flight - the explicit disposed check is the real guard.
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

            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (_: IllegalStateException) {
                // Ignore; lifecycle might be stopped before binding. Nothing was bound, so there
                // is no session to register - just clear the analyzer we already attached.
                imageAnalysis.clearAnalyzer()
                MobileLog.cameraAnalysisBindFailed("illegal_state")
                return@launch
            } catch (_: IllegalArgumentException) {
                // Device/config does not support this Preview + ImageAnalysis combination
                // (e.g. no back camera on a camera-less device/emulator).
                imageAnalysis.clearAnalyzer()
                MobileLog.cameraAnalysisBindFailed("illegal_argument")
                return@launch
            }

            // Bind succeeded. Register this bind's own cleanup and re-check disposal: if the
            // composable was disposed while bindToLifecycle() was running (it does not suspend),
            // confirmBound() runs that cleanup immediately instead of leaving a live session.
            val boundSessionIsActive =
                session.confirmBound {
                    imageAnalysis.clearAnalyzer()
                    cameraProvider.unbind(preview, imageAnalysis)
                }
            if (boundSessionIsActive) {
                MobileLog.cameraAnalysisBound()
            }
        }

        onDispose {
            session.markDisposed()
            job.cancel()
            session.cleanupAndShutdown {
                // shutdownNow(): immediate teardown on navigation away. By this point the
                // analyzer is already cleared and the use cases already unbound (via the
                // registered cleanup above), so at most one already-queued metadata-extraction
                // task is discarded rather than run - it only reads timestamp/size/rotation and
                // closes the frame, never pixel data, so discarding it is safe. Any frame that
                // was already running completes normally (this analyzer does not block, so
                // interruption does not truncate its finally-close).
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
