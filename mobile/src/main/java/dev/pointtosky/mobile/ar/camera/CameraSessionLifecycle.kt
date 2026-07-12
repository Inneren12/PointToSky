package dev.pointtosky.mobile.ar.camera

/**
 * Lifecycle-safe controller for one `CameraPreview` bind/dispose cycle (CAM-1c bind/dispose-race
 * fix). It owns only *ordering and idempotency*, never a real CameraX type - [CameraPreview]
 * wires the actual `ImageAnalysis.clearAnalyzer()` / `ProcessCameraProvider.unbind(...)` /
 * executor-shutdown calls through closures, so this class is JVM-testable with plain fakes.
 *
 * [disposed], [cleanedUp], and [cleanupUseCases] are one state machine guarded by a single
 * [lock] - not three independently-atomic fields. An earlier revision guarded `disposed` with an
 * `AtomicBoolean` and `cleanupUseCases` with `@Volatile` separately, which left a check-then-act
 * race in `confirmBound`: `onDispose` could complete `markDisposed()` *and* `cleanupAndShutdown()`
 * - including shutting down the executor - between `confirmBound`'s `disposed` check and its
 * `cleanupUseCases = cleanup` assignment. That interleaving left a newly bound CameraX use case
 * registered for a cleanup that would now never run, with the executor already gone. Two
 * independently-atomic fields cannot close this gap; only serializing the *transition* - decide
 * disposed-or-not, decide who owns cleanup, decide cleaned-up-or-not - through one lock can.
 * Every public method below acquires [lock] for its bookkeeping decision and releases it before
 * invoking any caller-supplied closure, so CameraX calls never run while [lock] is held and can
 * never block another thread's state transition.
 *
 * Two races are closed:
 *  - **early dispose**: the bind coroutine reads [isDisposed] right after the *suspending*
 *    `ProcessCameraProvider.getInstance(...)` resolves, and bails out before touching CameraX at
 *    all if disposal already happened. Nothing between that point and `bindToLifecycle()` suspends,
 *    so a cancelled coroutine `Job` alone cannot guarantee this - cancellation only takes effect at
 *    a suspension point. The explicit flag is the real guard, not the cancelled job.
 *  - **late dispose**: if disposal claims (or has already completed) cleanup by the time
 *    [confirmBound] - called immediately after a successful bind - acquires [lock], `confirmBound`
 *    runs the passed `cleanup` closure itself, immediately, and returns `false` so the caller skips
 *    logging/publishing the bind as an active session. Conversely, if `confirmBound` acquires
 *    [lock] first and disposal has not happened yet, it registers the closure and
 *    [cleanupAndShutdown] is the one that later claims and runs it. Exactly one side ever invokes
 *    a given cleanup closure, for every possible interleaving - see `CameraSessionLifecycleTest`'s
 *    concurrent race test.
 *
 * [cleanupAndShutdown] is the single idempotent entry point for `onDispose`. It always attempts the
 * cleanup claimed from [confirmBound] (a no-op if a bind never completed - e.g. it failed, or
 * disposal happened before the coroutine ever attempted one) before shutting down the executor;
 * the shutdown runs in a `finally` block, so it happens even if that cleanup throws. A thrown
 * cleanup exception itself propagates out of [cleanupAndShutdown] rather than being swallowed - the
 * `cleanedUp` transition is already committed by then, so a second call is still a no-op regardless.
 */
internal class CameraSessionLifecycle {
    private val lock = Any()

    private var disposed = false
    private var cleanedUp = false
    private var cleanupUseCases: (() -> Unit)? = null

    val isDisposed: Boolean
        get() = synchronized(lock) { disposed }

    /** Marks the session disposed. Idempotent; safe to call multiple times. */
    fun markDisposed() {
        synchronized(lock) {
            disposed = true
        }
    }

    /**
     * Call once, immediately after a successful `bindToLifecycle`, passing the cleanup this
     * specific bind now owns (clear analyzer + unbind these exact use cases). Returns `true` if
     * the caller should treat the bind as the live, owned session - safe to log/publish. Returns
     * `false` if disposal has already claimed or completed cleanup by the time this call acquires
     * the lock; in that case [cleanup] has already been invoked by this call, so the caller must
     * not register it again and must not treat the bind as active.
     *
     * Calling this a second time on the same, still-live session (i.e. before disposal) is a
     * programming error - exactly one bind attempt should ever reach a successful [confirmBound]
     * per [CameraSessionLifecycle] instance - and throws [IllegalStateException] rather than
     * silently discarding the first registered cleanup.
     */
    fun confirmBound(cleanup: () -> Unit): Boolean {
        val runImmediately =
            synchronized(lock) {
                if (disposed || cleanedUp) {
                    true
                } else {
                    check(cleanupUseCases == null) {
                        "A camera session cleanup is already registered"
                    }
                    cleanupUseCases = cleanup
                    false
                }
            }

        if (runImmediately) {
            cleanup()
            return false
        }
        return true
    }

    /**
     * Idempotent: claims the cleanup registered by [confirmBound] (if any) and runs it, then runs
     * [shutdownExecutor] in `finally` so the executor is always terminated - even if that cleanup
     * throws. Safe to call more than once; only the first call does anything.
     */
    fun cleanupAndShutdown(shutdownExecutor: () -> Unit) {
        val cleanup =
            synchronized(lock) {
                if (cleanedUp) return
                cleanedUp = true
                cleanupUseCases.also { cleanupUseCases = null }
            }

        try {
            cleanup?.invoke()
        } finally {
            shutdownExecutor()
        }
    }
}
