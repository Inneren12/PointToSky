package dev.pointtosky.mobile.ar.camera

/**
 * Lifecycle-safe controller for one `CameraPreview` bind/dispose cycle (CAM-1c bind/dispose-race
 * fix). It owns only *ordering and idempotency*, never a real CameraX type - [CameraPreview]
 * wires the actual `ImageAnalysis.clearAnalyzer()` / `ProcessCameraProvider.unbind(...)` /
 * executor-shutdown calls through closures, so this class is JVM-testable with plain fakes.
 *
 * [disposed], [useCasesCleanedUp], and [cleanupUseCases] are one state machine guarded by a single
 * [lock] - not independently-atomic fields. An earlier revision guarded `disposed` with an
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
 *    `ProcessCameraProvider.getInstance(...)` resolves - and again before attempting a Preview-only
 *    fallback bind after the combined `Preview` + `ImageAnalysis` bind is rejected - and bails out
 *    before touching CameraX at all if disposal already happened. Nothing between those checks and
 *    the next `bindToLifecycle()` call suspends, so a cancelled coroutine `Job` alone cannot
 *    guarantee this - cancellation only takes effect at a suspension point. The explicit flag is
 *    the real guard, not the cancelled job.
 *  - **late dispose**: if disposal claims (or has already completed) cleanup by the time
 *    [confirmBound] - called immediately after a successful bind, combined or Preview-only - acquires
 *    [lock], `confirmBound` runs the passed `cleanup` closure itself, immediately, and returns
 *    `false` so the caller skips logging/publishing the bind as an active session. Conversely, if
 *    `confirmBound` acquires [lock] first and disposal has not happened yet, it registers the
 *    closure and [cleanupAndShutdown] is the one that later claims and runs it. Exactly one side
 *    ever invokes a given cleanup closure, for every possible interleaving - see
 *    `CameraSessionLifecycleTest`'s concurrent race test.
 *
 * [executorShutdown] is a **separate** idempotency axis from [useCasesCleanedUp]/[cleanupUseCases],
 * guarded by the same [lock] but tracked independently: when the combined bind is rejected with
 * `IllegalArgumentException`, `ImageAnalysis` (and its dedicated executor) is abandoned for the rest
 * of this session *before* a Preview-only fallback is even attempted - there is no use case to
 * unbind yet at that point, so [shutdownExecutorOnce] lets the caller shut the executor down right
 * there, immediately, independently of whichever use-case cleanup (if any) [confirmBound] registers
 * later. [cleanupAndShutdown] also calls [shutdownExecutorOnce] as its own final step, so calling
 * it early from a bind-failure path and again later from `onDispose` still shuts the executor down
 * exactly once, regardless of call order.
 *
 * [cleanupAndShutdown] is the single idempotent entry point for `onDispose`. It always attempts the
 * cleanup claimed from [confirmBound] (a no-op if a bind never completed - e.g. both the combined
 * and Preview-only fallback binds failed, or disposal happened before either was attempted) before
 * shutting down the executor via [shutdownExecutorOnce]; that shutdown runs in a `finally` block, so
 * it happens even if the use-case cleanup throws. A thrown cleanup exception itself propagates out
 * of [cleanupAndShutdown] rather than being swallowed - the `useCasesCleanedUp` transition is
 * already committed by then, so a second call is still a no-op regardless.
 */
internal class CameraSessionLifecycle {
    private val lock = Any()

    private var disposed = false
    private var useCasesCleanedUp = false
    private var cleanupUseCases: (() -> Unit)? = null
    private var executorShutdown = false

    val isDisposed: Boolean
        get() = synchronized(lock) { disposed }

    /** Marks the session disposed. Idempotent; safe to call multiple times. */
    fun markDisposed() {
        synchronized(lock) {
            disposed = true
        }
    }

    /**
     * Call once, immediately after a successful `bindToLifecycle` - combined `Preview` +
     * `ImageAnalysis`, or a Preview-only fallback - passing the cleanup this specific bind now
     * owns (unbind exactly the use case(s) that bind call bound). Returns `true` if the caller
     * should treat the bind as the live, owned session - safe to log/publish. Returns `false` if
     * disposal has already claimed or completed cleanup by the time this call acquires the lock;
     * in that case [cleanup] has already been invoked by this call, so the caller must not
     * register it again and must not treat the bind as active.
     *
     * Calling this a second time on the same, still-live session (i.e. before disposal) is a
     * programming error - exactly one bind attempt (combined or fallback, never both) should ever
     * reach a successful [confirmBound] per [CameraSessionLifecycle] instance - and throws
     * [IllegalStateException] rather than silently discarding the first registered cleanup.
     */
    fun confirmBound(cleanup: () -> Unit): Boolean {
        val runImmediately =
            synchronized(lock) {
                if (disposed || useCasesCleanedUp) {
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
     * Idempotent, independent of any use-case cleanup: shuts the analysis executor down exactly
     * once regardless of how many times, or from how many call sites, this is invoked. Lets a
     * combined-bind failure that immediately abandons `ImageAnalysis` (before a Preview-only
     * fallback is attempted) shut the executor down right away, without that early call causing
     * [cleanupAndShutdown]'s own later shutdown step to run a second time.
     */
    fun shutdownExecutorOnce(shutdownExecutor: () -> Unit) {
        val shouldShutdown =
            synchronized(lock) {
                if (executorShutdown) {
                    false
                } else {
                    executorShutdown = true
                    true
                }
            }
        if (shouldShutdown) {
            shutdownExecutor()
        }
    }

    /**
     * Idempotent: claims and runs the cleanup registered by [confirmBound] (a no-op if none was
     * registered), then shuts the executor down via [shutdownExecutorOnce] in a `finally` block -
     * so the executor is always terminated, even if that cleanup throws, and even if
     * [shutdownExecutorOnce] already ran earlier from a different call site. Safe to call more
     * than once; only the first call attempts the use-case cleanup.
     */
    fun cleanupAndShutdown(shutdownExecutor: () -> Unit) {
        val cleanup =
            synchronized(lock) {
                if (useCasesCleanedUp) {
                    null
                } else {
                    useCasesCleanedUp = true
                    cleanupUseCases.also { cleanupUseCases = null }
                }
            }

        try {
            cleanup?.invoke()
        } finally {
            shutdownExecutorOnce(shutdownExecutor)
        }
    }
}
