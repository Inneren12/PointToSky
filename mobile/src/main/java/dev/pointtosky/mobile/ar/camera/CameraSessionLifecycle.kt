package dev.pointtosky.mobile.ar.camera

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-safe controller for one `CameraPreview` bind/dispose cycle (CAM-1c bind/dispose-race
 * fix). It owns only *ordering and idempotency*, never a real CameraX type - [CameraPreview]
 * wires the actual `ImageAnalysis.clearAnalyzer()` / `ProcessCameraProvider.unbind(...)` /
 * executor-shutdown calls through closures, so this class is JVM-testable with plain fakes.
 *
 * Two races are closed:
 *  - **early dispose**: the bind coroutine reads [isDisposed] right after the *suspending*
 *    `ProcessCameraProvider.getInstance(...)` resolves, and bails out before touching CameraX at
 *    all if disposal already happened. Nothing between that point and `bindToLifecycle()` suspends,
 *    so a cancelled coroutine `Job` alone cannot guarantee this - cancellation only takes effect at
 *    a suspension point. The explicit flag is the real guard, not the cancelled job.
 *  - **late dispose**: if disposal happens *during* the synchronous `bindToLifecycle()` call,
 *    [confirmBound] - called immediately after a successful bind - detects [isDisposed] and runs
 *    the passed `cleanup` closure (clear analyzer + unbind) right there, returning `false` so the
 *    caller skips logging/publishing the bind as an active session.
 *
 * [cleanupAndShutdown] is the single idempotent entry point for `onDispose`. It always runs the
 * cleanup registered by [confirmBound] (a no-op if a bind never completed - e.g. it failed, or
 * disposal happened before the coroutine ever attempted one) before shutting down the executor,
 * and is safe to call more than once.
 */
internal class CameraSessionLifecycle {
    private val disposed = AtomicBoolean(false)
    private val cleanedUp = AtomicBoolean(false)

    @Volatile
    private var cleanupUseCases: (() -> Unit)? = null

    val isDisposed: Boolean get() = disposed.get()

    /** Marks the session disposed. Idempotent; safe to call multiple times. */
    fun markDisposed() {
        disposed.set(true)
    }

    /**
     * Call once, immediately after a successful `bindToLifecycle`, passing the cleanup this
     * specific bind now owns (clear analyzer + unbind these exact use cases). Returns `true` if
     * the caller should treat the bind as the live, owned session - safe to log/publish. Returns
     * `false` if disposal already happened during the bind window; in that case [cleanup] has
     * already been invoked by this call, so the caller must not register it again.
     */
    fun confirmBound(cleanup: () -> Unit): Boolean {
        if (disposed.get()) {
            cleanup()
            return false
        }
        cleanupUseCases = cleanup
        return true
    }

    /**
     * Idempotent: runs the registered cleanup (if any) and [shutdownExecutor] at most once,
     * regardless of how many times this is called.
     */
    fun cleanupAndShutdown(shutdownExecutor: () -> Unit) {
        if (!cleanedUp.compareAndSet(false, true)) return
        cleanupUseCases?.invoke()
        shutdownExecutor()
    }
}
