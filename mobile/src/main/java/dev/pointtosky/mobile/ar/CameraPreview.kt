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
 * call. `ImageAnalysis` extracts frame metadata only (timestamp, buffer size, rotation, crop rect) —
 * it never reads pixel planes — via [CameraFrameAnalyzer]. The metadata provider is owned and
 * scoped to this composable for now (throttled debug logging only); no production call site reads
 * it yet, and the AR renderer is unchanged. See `docs/camera_coordinate_calibration_contract.md`.
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
        // Dedicated single-thread executor: ImageAnalysis.Analyzer callbacks must not run on the
        // main thread. Shut down on dispose so it never leaks across recomposition/navigation.
        val analysisExecutor = Executors.newSingleThreadExecutor()

        scope.launch {
            val cameraProvider = context.getCameraProvider()
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
            cameraProvider.unbindAll()
            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
                MobileLog.cameraAnalysisBound()
            } catch (_: IllegalStateException) {
                // Ignore; lifecycle might be stopped before binding.
                MobileLog.cameraAnalysisBindFailed("illegal_state")
            } catch (_: IllegalArgumentException) {
                // Device/config does not support this Preview + ImageAnalysis combination
                // (e.g. no back camera on a camera-less device/emulator).
                MobileLog.cameraAnalysisBindFailed("illegal_argument")
            }
        }

        onDispose {
            job.cancel()
            analysisExecutor.shutdown()
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
