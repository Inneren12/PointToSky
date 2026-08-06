package dev.pointtosky.mobile.ar.camera

/**
 * SKY-1 (`internalDebug`-only): which camera bind a callback belongs to.
 *
 * ## The problem this exists to solve
 * `SessionScopedCameraIntrinsicsResolver.resolveOnce` is, by contract, once **per instance**: it
 * caches the first resolution and returns it forever after. That is right for the AR screen, where
 * one instance belongs to one camera session. It is wrong the moment a screen can rebind — and this
 * one can, on three axes: the operator picks a different physical camera, a different analysis
 * resolution, or a different manual exposure.
 *
 * Reusing one resolver across a rebind means the second bind's frames are projected through the first
 * bind's intrinsics, and — worse — a session header can pair the *new* camera id with the *old*
 * camera's calibration. Nothing downstream can detect that: both halves are individually plausible.
 *
 * ## The fix
 * Every bind gets a monotonically increasing epoch and its own [SkyCaptureConfiguration]. The whole
 * per-session apparatus — intrinsics resolver, pairing synchronizer, geometry provider — is rebuilt
 * for each epoch and the previous one disposed. Callbacks carry their epoch; anything from an epoch
 * older than the current one is dropped rather than folded into the live state.
 *
 * [SkyCaptureGenerationTracker] is the pure half of that: it decides which epoch is current and
 * whether a given callback may act. It holds no Android type and no camera state, so the rule can be
 * tested directly rather than inferred from a device session.
 */

/**
 * Everything about a bind that, if changed, invalidates the intrinsics and calibration resolved under
 * it.
 *
 * All three fields genuinely matter:
 *  - [physicalCameraId] changes the sensor, so every characteristic changes;
 *  - [resolution] changes the analysis buffer, and `CameraIntrinsicsReference.AnalysisBuffer` records
 *    the exact buffer its FOV was measured over — `projectStars` rejects a mismatch outright;
 *  - [exposure] is a capture-request option CameraX only applies at bind time, so changing it *is* a
 *    rebind whether or not the caller thinks of it that way.
 */
internal data class SkyCaptureConfiguration(
    val physicalCameraId: String,
    val resolution: AnalysisResolutionRequest,
    val exposure: SkyResolvedExposure?,
)

/**
 * Tracks which bind epoch is live.
 *
 * Epochs are supplied by the caller (minted inside the bind's own `DisposableEffect`, so exactly one
 * epoch exists per bind attempt) rather than generated here. That keeps this class free of any
 * assumption about *when* a bind starts relative to composition, which is precisely the ordering that
 * is hard to reason about in Compose and easy to get subtly wrong.
 *
 * The rule is "highest epoch seen wins", not "most recent call wins": a stale callback that arrives
 * late — a frame already queued on the analysis executor when the rebind happened — must not be able
 * to reinstate the epoch it came from.
 *
 * Not thread-safe on its own; [SkySessionCaptureSession] owns one instance under its own lock.
 */
internal class SkyCaptureGenerationTracker {
    /** `0` until the first bind is announced. Real epochs are always positive. */
    var currentEpoch: Long = 0L
        private set

    var currentConfiguration: SkyCaptureConfiguration? = null
        private set

    /**
     * Announces contact from bind [epoch].
     *
     * @return [SkyGenerationTransition.Started] when this epoch is newer than anything seen (the
     *   caller must rebuild its per-session state), [SkyGenerationTransition.Current] when it is the
     *   live one, and [SkyGenerationTransition.Stale] when it is older and must be ignored.
     */
    fun observe(
        epoch: Long,
        configuration: SkyCaptureConfiguration,
    ): SkyGenerationTransition {
        require(epoch > 0L) { "epoch must be positive; was $epoch" }
        return when {
            epoch > currentEpoch -> {
                currentEpoch = epoch
                currentConfiguration = configuration
                SkyGenerationTransition.STARTED
            }

            epoch == currentEpoch -> SkyGenerationTransition.CURRENT
            else -> SkyGenerationTransition.STALE
        }
    }

    /** Whether [epoch] is the live generation. `false` for anything stale, and for `0`. */
    fun isCurrent(epoch: Long): Boolean = epoch != 0L && epoch == currentEpoch

    /** Forgets the live generation entirely — used when the whole capture session is disposed. */
    fun clear() {
        currentEpoch = 0L
        currentConfiguration = null
    }
}

/** What [SkyCaptureGenerationTracker.observe] decided about one callback's epoch. */
internal enum class SkyGenerationTransition {
    /** A newer bind than anything seen before. Per-session state must be rebuilt from scratch. */
    STARTED,

    /** The live bind. Proceed. */
    CURRENT,

    /** An older bind whose callbacks are still draining. Ignore it. */
    STALE,
}
