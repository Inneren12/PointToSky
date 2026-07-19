package dev.pointtosky.mobile.ar

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import dev.pointtosky.mobile.ar.camera.AnalysisResolutionRequest
import dev.pointtosky.mobile.ar.camera.aspectRatioStrategyFor
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.mobile.ar.camera.CameraFrameAnalyzer
import dev.pointtosky.mobile.ar.camera.CameraFrameMetadataProvider
import dev.pointtosky.mobile.ar.camera.CameraFrameMetadataSink
import dev.pointtosky.mobile.ar.camera.CameraSessionLifecycle
import dev.pointtosky.mobile.logging.MobileLog
import java.util.concurrent.Executor
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
    const val EXPLICIT_SELECTOR_ILLEGAL_STATE = "explicit_selector_illegal_state"
    const val EXPLICIT_SELECTOR_ILLEGAL_ARGUMENT = "explicit_selector_illegal_argument"
    const val EXPLICIT_SELECTOR_ZOOM_FAILED = "explicit_selector_zoom_failed"
}

/** CAM-2c physical-camera-binding experiment (internalDebug-only caller): a fixed, non-optical-zoom
 * ratio applied to a [cameraSelectorOverride] bind, so a zoom-triggered lens switch cannot silently
 * swap which physical sensor produces analyzed frames mid-session (task §9's "use a fixed supported
 * zoom ratio" mitigation - CameraX 1.4.2 exposes no per-frame physical-camera-identity callback to
 * detect such a switch directly; see `docs/camera_coordinate_calibration_contract.md`). This is a
 * mitigation only, not a proof: it removes the one lens-switch trigger (zoom) this codebase can
 * control, but it does not, and cannot, rule out every possible OEM physical-camera-switching behavior
 * (e.g. low-light or stabilization-triggered switches), since no CameraX API in this pinned version
 * exposes live physical-camera identity to verify against. */
internal const val EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO = 1.0f

/**
 * Returns a function reference whose **identity never changes** across recompositions, but which
 * always invokes the most recently supplied [latest] lambda when called (fix for a stale-callback
 * defect: [CameraPreview]'s own bind coroutine and `ImageAnalysis.Analyzer` are installed once, inside
 * a `DisposableEffect(Unit)` that runs exactly once per composition — a long-lived CameraX analyzer
 * may retain whichever specific lambda object was captured when that effect first ran, and a later
 * recomposition that passes a *different* lambda instance for e.g. [CameraPreview]'s `onFrameMetadata`
 * does not, by itself, cause the already-installed analyzer to start invoking the new one). Backed by
 * [rememberUpdatedState] (this is exactly that pattern, wrapped as a directly reusable, independently
 * testable function value) — the returned function is `remember`ed once, so passing it into a
 * long-lived callback/analyzer registration never itself triggers a rebind, while every *invocation* of
 * that stable reference reads whichever [latest] this composable most recently recomposed with.
 */
@Composable
internal fun <T> rememberStableCallback(latest: (T) -> Unit): (T) -> Unit {
    val latestState = rememberUpdatedState(latest)
    return remember { { value: T -> latestState.value(value) } }
}

/**
 * Suspends until this [ListenableFuture] completes, returning `Result.success(Unit)` on success or
 * `Result.failure(cause)` otherwise - never blocking the calling thread, and never treating a
 * still-pending future as complete. [CameraControl.setZoomRatio] (and `ProcessCameraProvider.getInstance`,
 * see [getCameraProvider] below) both return `ListenableFuture`s from CameraX's own API; this is the
 * one, shared suspend-adapter both use, so a caller never has to fire-and-forget an asynchronous camera
 * operation and assume it already completed.
 */
private suspend fun ListenableFuture<*>.awaitCompletion(executor: Executor): Result<Unit> =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                val result = runCatching { get() }.map { }
                continuation.resume(result)
            },
            executor,
        )
        continuation.invokeOnCancellation { cancel(true) }
    }

/**
 * CAM-1c: binds CameraX `Preview` and `ImageAnalysis` together in one [ProcessCameraProvider.bindToLifecycle]
 * call. `ImageAnalysis` extracts frame metadata only (timestamp, buffer size, rotation, crop rect) -
 * it never reads pixel planes - via [CameraFrameAnalyzer]. The AR renderer is unchanged.
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
 *
 * [onFrameMetadata] is called with every extracted [CameraFrameMetadata], alongside (never instead
 * of) the existing `remember`ed [CameraFrameMetadataProvider] (CAM-1d) - a minimal seam for a caller
 * to observe camera frames (e.g. to pair them with rotation samples) without this composable taking
 * on any new ownership. Defaults to a no-op, so existing callers are unaffected; the provider's own
 * lifecycle, bind/dispose handling, and throttled debug logging are unchanged.
 *
 * [onCameraInfo] (CAM-1f) is called exactly once per successful bind - combined `Preview` +
 * `ImageAnalysis`, or the Preview-only fallback - with the real, bound [CameraInfo], and only if
 * that bind is still the live session by the time [CameraSessionLifecycle.confirmBound] confirms
 * it (never for a bind that lost the late-dispose race). It is the seam CAM-1f's
 * `dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver` uses to resolve real
 * per-device intrinsics without this composable owning a second camera/sensor session. Defaults to
 * a no-op.
 *
 * [cameraSelectorOverride] (CAM-2c physical-camera provenance experiment) replaces
 * [CameraSelector.DEFAULT_BACK_CAMERA] for the combined bind attempt when non-`null`. Defaults to
 * `null`, so every existing call site (production and `internalDebug` alike) is unaffected - this
 * parameter's *type* is plain CameraX API available to every build variant, but only an
 * `internalDebug`-only call site (the physical-camera-binding experiment) is ever expected to
 * construct a non-`DEFAULT_BACK_CAMERA` [CameraSelector] (e.g. via
 * `CameraSelector.Builder().setPhysicalCameraId(...)`) and pass it here - the same
 * shared-class-with-debug-only-caller pattern `CameraCharacteristicsSource`'s optional `Context`
 * already uses in this codebase. When [cameraSelectorOverride] is non-`null` and the combined bind
 * fails, this composable does **not** retry the [CameraSelector.DEFAULT_BACK_CAMERA] Preview-only
 * fallback (that would silently substitute a different, unrequested camera/binding for an explicit
 * physical-camera experiment) - it instead reports [onExplicitBindFailure] with a short,
 * non-device-specific reason and leaves the preview surface unbound. When
 * [cameraSelectorOverride] is non-`null` and the combined bind succeeds, the bound camera's zoom
 * ratio is fixed to [EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO] before [onCameraInfo] fires, so a
 * zoom-triggered lens switch cannot occur during the experiment session.
 *
 * [onExplicitBindFailure] is called with a short reason string exactly when [cameraSelectorOverride]
 * is non-`null` and the combined bind throws. Never called when [cameraSelectorOverride] is `null`
 * (the existing Preview-only-fallback path handles that case exactly as before). Defaults to a no-op.
 *
 * [analysisResolutionOverride] (CAM-2c dual-basis experiment, task §11) requests an explicit
 * `ImageAnalysis` resolution via CameraX's `ResolutionSelector`/`ResolutionStrategy` — a complete
 * [AnalysisResolutionRequest] (dimensions **plus** the aspect family of the band that selected
 * them), never a bare `Size`: the aspect strategy comes from
 * [dev.pointtosky.mobile.ar.camera.aspectRatioStrategyFor] applied to the request's explicit
 * family, so a non-exact in-band size (e.g. `848x480`, near-16:9) still binds with the 16:9
 * strategy — the family is never re-inferred from exact integer ratios here. Defaults to `null` —
 * CameraX's own default (typically 640×480) — so every existing call site (production and
 * `internalDebug` alike) is byte-for-byte unaffected; only the `internalDebug` physical-camera
 * experiment ever passes a non-`null` value. The *requested* size is not a guarantee: the
 * actually-bound buffer size is whatever each analyzed frame reports, and the experiment records both
 * without conflating them.
 *
 * **This composable, [AnalysisResolutionRequest], and [AnalysisResolutionFamily] are all
 * `internal`** (architecture-leak fix): nothing outside this Gradle module has a legitimate reason
 * to call [CameraPreview] or construct an [AnalysisResolutionRequest], so none of it belongs in the
 * public production API surface. It remains in `main` — not `internalDebug` — only because this one
 * composable executes the real CameraX bind that both [dev.pointtosky.mobile.ar.ArScreen] (the
 * production caller, which never supplies [analysisResolutionOverride] or [cameraSelectorOverride])
 * and the `internalDebug`-only physical-camera experiment share; `internal` visibility is scoped to
 * the Gradle module, not the source set, so both callers see it across the `main`/`internalDebug`
 * source-set split within one variant compilation, while every other module (and any future
 * public-API consumer) cannot.
 */
@Composable
internal fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrameMetadata: (CameraFrameMetadata) -> Unit = {},
    onCameraInfo: (CameraInfo) -> Unit = {},
    cameraSelectorOverride: CameraSelector? = null,
    onExplicitBindFailure: (String) -> Unit = {},
    analysisResolutionOverride: AnalysisResolutionRequest? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val metadataProvider = remember { CameraFrameMetadataProvider() }
    // Fix for a stale-callback defect: DisposableEffect(Unit) below runs its body - including
    // installing the ImageAnalysis.Analyzer and the bind coroutine's own closures - exactly once.
    // Without these wrappers, a caller passing a new onFrameMetadata/onCameraInfo/onExplicitBindFailure
    // lambda instance on a later recomposition (e.g. because it now closes over updated UI state) would
    // never actually reach the long-lived analyzer/coroutine, which would keep invoking whichever
    // lambda instance existed at the moment this effect first ran. Each wrapper's own identity is
    // stable (remember-ed once) - installing it does not itself trigger a rebind - but every
    // invocation reads the latest lambda this composable most recently recomposed with.
    val currentOnFrameMetadata = rememberStableCallback(onFrameMetadata)
    val currentOnCameraInfo = rememberStableCallback(onCameraInfo)
    val currentOnExplicitBindFailure = rememberStableCallback(onExplicitBindFailure)

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
            // Forwards every extracted frame to both the CAM-1c provider (unchanged throttled debug
            // log) and CAM-1d's onFrameMetadata callback - never instead of the provider, and never
            // broadening what this composable itself owns beyond that one extra forward.
            val metadataSink =
                object : CameraFrameMetadataSink {
                    override fun onFrame(metadata: CameraFrameMetadata) {
                        metadataProvider.onFrame(metadata)
                        currentOnFrameMetadata(metadata)
                    }
                }
            val imageAnalysis =
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .apply {
                        if (analysisResolutionOverride != null) {
                            // CAM-2c dual-basis experiment (task §11, P1 family fix): the aspect
                            // strategy is decided by the request's EXPLICIT family via the pure,
                            // JVM-tested aspectRatioStrategyFor — never re-inferred from an exact
                            // width*9 == height*16 integer check, which would silently send a valid
                            // near-16:9 size (e.g. 848x480) with a 4:3 strategy. The bound size
                            // remains device-decided; frames report the actual buffer dimensions.
                            setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(aspectRatioStrategyFor(analysisResolutionOverride.family))
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(analysisResolutionOverride.widthPx, analysisResolutionOverride.heightPx),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    )
                                    .build(),
                            )
                        }
                    }
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            analysisExecutor,
                            CameraFrameAnalyzer(
                                metadataSink = metadataSink,
                                onFrameFailure = metadataProvider::recordFailedFrame,
                            ),
                        )
                    }

            val effectiveSelector = cameraSelectorOverride ?: CameraSelector.DEFAULT_BACK_CAMERA
            var boundCamera: Camera? = null
            val combinedBindFailure: RuntimeException? =
                try {
                    boundCamera =
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            effectiveSelector,
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
                    val camera =
                        checkNotNull(boundCamera) { "boundCamera must be set once the combined bind succeeded" }
                    if (cameraSelectorOverride != null) {
                        // CAM-2c physical-camera experiment (task §9): fix zoom to a known ratio
                        // *and await its actual completion* before publishing CameraInfo -
                        // CameraControl.setZoomRatio returns an asynchronous ListenableFuture, so
                        // firing it and immediately calling onCameraInfo (as a prior revision did)
                        // would establish provenance before 1.0x was actually applied, not after.
                        val zoomResult =
                            camera.cameraControl
                                .setZoomRatio(EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO)
                                .awaitCompletion(ContextCompat.getMainExecutor(context))
                        if (zoomResult.isFailure) {
                            // Zoom is unsupported or application failed: never call onCameraInfo, so
                            // no caller can mistake this bind for one with confirmed 1.0x zoom. The
                            // already-bound use cases are left registered for the normal onDispose
                            // cleanup path (CameraSessionLifecycle's cleanup is exactly-once and
                            // already claimed by confirmBound above) - reporting a typed failure here
                            // does not unbind a second time.
                            MobileLog.cameraAnalysisBindFailed(CameraBindFailureReason.EXPLICIT_SELECTOR_ZOOM_FAILED)
                            currentOnExplicitBindFailure(CameraBindFailureReason.EXPLICIT_SELECTOR_ZOOM_FAILED)
                            return@launch
                        }
                        // Awaiting the zoom future is itself a suspension point - re-check disposal
                        // the same way every other suspension point in this file does, so a
                        // disposal that raced with (and already unbound/cleaned-up via
                        // cleanupAndShutdown) this session cannot still be reported as bound.
                        if (session.isDisposed) return@launch
                    }
                    // CAM-1f: only for the confirmed-live session, never for a bind that lost the
                    // late-dispose race (confirmBound already ran its cleanup and returned false
                    // above), and only once the physical-camera experiment's zoom future (if any) has
                    // actually completed successfully.
                    currentOnCameraInfo(camera.cameraInfo)
                }
                return@launch
            }

            // The combined bind failed. Either way, ImageAnalysis is abandoned for this session:
            // clear its analyzer and shut down the (now-unused) executor immediately rather than
            // waiting for eventual disposal - shutdownExecutorOnce guarantees this does not double
            // up with onDispose's own shutdown later, however this coroutine ends.
            imageAnalysis.clearAnalyzer()
            session.shutdownExecutorOnce { analysisExecutor.shutdownNow() }

            if (cameraSelectorOverride != null) {
                // CAM-2c physical-camera experiment: never silently fall back to
                // CameraSelector.DEFAULT_BACK_CAMERA for an explicit physical-camera bind request -
                // that would substitute a different, unrequested camera/binding and defeat the
                // whole point of an explicit selection. Report a typed failure instead.
                val reason =
                    if (combinedBindFailure is IllegalStateException) {
                        CameraBindFailureReason.EXPLICIT_SELECTOR_ILLEGAL_STATE
                    } else {
                        CameraBindFailureReason.EXPLICIT_SELECTOR_ILLEGAL_ARGUMENT
                    }
                MobileLog.cameraAnalysisBindFailed(reason)
                currentOnExplicitBindFailure(reason)
                return@launch
            }

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

            val previewCamera: Camera =
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
                currentOnCameraInfo(previewCamera.cameraInfo)
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
