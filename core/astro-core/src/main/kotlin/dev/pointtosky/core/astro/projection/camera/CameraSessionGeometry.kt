package dev.pointtosky.core.astro.projection.camera

/**
 * Immutable, Android-independent camera-session geometry bundle (CAM-1f): one camera frame
 * (CAM-1c), the rotation sample paired to that exact frame timestamp (CAM-1d), the image↔display
 * [CropScaleTransform] built from that same frame and viewport (CAM-1e), and resolved-or-fallback
 * camera intrinsics (CAM-1b). Intended as the input a future predict-only matcher reads; this PR
 * adds no star matching, detection, interpolation, or renderer change, and nothing yet consumes
 * this bundle.
 *
 * ## True immutability
 * A plain `data class` cannot own this guarantee: exposing a `FloatArray`-bearing property lets a
 * caller mutate the exact array instance stored inside the bundle, either the one it originally
 * handed off or one previously read back out. [CameraSessionGeometry] is therefore a **regular
 * class**, not a `data class`: [pairedRotation] is a computed property backed by a private
 * [pairedRotationSnapshot], and every read returns a **fresh defensive copy** — never the same
 * array instance twice, and never the array a caller can still hold a reference to elsewhere. This
 * is a stricter guarantee than "copied once on ingestion": that alone still lets a caller corrupt
 * the bundle by mutating the array returned from a previous [pairedRotation] read.
 *
 * ## Construction invariants
 * The only construction path is [createCameraSessionGeometry]; the primary constructor is
 * `private`, so no other path can produce an instance with fields out of sync. `init` re-derives
 * and checks the cross-field invariants anyway (defense in depth, matching [CropScaleTransform]'s
 * own convention) — these checks are expected to be unreachable in practice because
 * [createCameraSessionGeometry] validates the same conditions itself and returns a categorized
 * [CameraSessionGeometryResult] instead of ever calling [of] with inconsistent inputs:
 *  - [cropScaleTransform].sourceBufferSize equals [frame]'s buffer dimensions;
 *  - [cropScaleTransform].sourceCrop equals [frame]'s crop rect (or the full buffer when absent —
 *    see [sourceCropFromFrame], the single place that mapping lives);
 *  - [cropScaleTransform].rotationDegrees equals [frame].rotationDegrees;
 *  - [cropScaleTransform].viewportSize equals [viewportSize];
 *  - [frameRotationDeltaNanos] equals `frame.timestampNanos - pairedRotation.timestampNanos`,
 *    computed with the same overflow-safe helper [FrameRotationPairingResult] uses.
 *
 * Pairing-tolerance conformance is **not** an invariant of this class: [createCameraSessionGeometry]
 * validates the configured tolerance explicitly (against a caller-supplied threshold, since
 * [FrameRotationPairingResult.Paired] and [FrameRotationPair] are both publicly constructible and
 * so cannot be trusted as proof of anything on their own) before ever calling [of].
 *
 * @property frame the exact camera frame this bundle describes.
 * @property pairedRotation the rotation sample [frame] was paired to. Returns a fresh defensive
 *   copy on every access.
 * @property frameRotationDeltaNanos `frame.timestampNanos - pairedRotation.timestampNanos`.
 * @property cropScaleTransform the FILL_CENTER image↔display mapping for [frame] and
 *   [viewportSize].
 * @property intrinsics resolved-or-fallback camera intrinsics; see [CameraIntrinsicsResolution].
 * @property viewportSize the exact viewport dimensions [cropScaleTransform] was built from.
 */
class CameraSessionGeometry private constructor(
    val frame: CameraFrameMetadata,
    private val pairedRotationSnapshot: TimedRotationSample,
    val frameRotationDeltaNanos: Long,
    val cropScaleTransform: CropScaleTransform,
    val intrinsics: CameraIntrinsicsResolution,
    val viewportSize: PixelSize,
) {
    val pairedRotation: TimedRotationSample
        get() = pairedRotationSnapshot.copy(rotationMatrix = pairedRotationSnapshot.rotationMatrix.copyOf())

    init {
        val expectedBufferSize = PixelSize(frame.bufferWidthPx.toDouble(), frame.bufferHeightPx.toDouble())
        require(cropScaleTransform.sourceBufferSize == expectedBufferSize) {
            "cropScaleTransform.sourceBufferSize ${cropScaleTransform.sourceBufferSize} must match the frame " +
                "buffer $expectedBufferSize"
        }
        require(cropScaleTransform.sourceCrop == sourceCropFromFrame(frame)) {
            "cropScaleTransform.sourceCrop ${cropScaleTransform.sourceCrop} must match the frame-derived crop " +
                "${sourceCropFromFrame(frame)}"
        }
        require(cropScaleTransform.rotationDegrees == frame.rotationDegrees) {
            "cropScaleTransform.rotationDegrees ${cropScaleTransform.rotationDegrees} must match " +
                "frame.rotationDegrees ${frame.rotationDegrees}"
        }
        require(cropScaleTransform.viewportSize == viewportSize) {
            "cropScaleTransform.viewportSize ${cropScaleTransform.viewportSize} must match viewportSize $viewportSize"
        }
        val expectedDelta = overflowSafeDeltaNanos(frame.timestampNanos, pairedRotationSnapshot.timestampNanos)
        require(frameRotationDeltaNanos == expectedDelta) {
            "frameRotationDeltaNanos $frameRotationDeltaNanos must equal frame.timestampNanos - " +
                "pairedRotation.timestampNanos ($expectedDelta)"
        }
    }

    internal companion object {
        /**
         * Canonical, defensive-copying factory. `internal` — the only sanctioned caller is the pure
         * top-level factory [createCameraSessionGeometry], in the same module. Copies
         * [pairedRotation]'s backing array on the way in, so the bundle's own snapshot is
         * independent of whatever array the caller (or an upstream boundary such as
         * [RotationSampleHistory]) still holds a reference to.
         */
        fun of(
            frame: CameraFrameMetadata,
            pairedRotation: TimedRotationSample,
            frameRotationDeltaNanos: Long,
            cropScaleTransform: CropScaleTransform,
            intrinsics: CameraIntrinsicsResolution,
            viewportSize: PixelSize,
        ): CameraSessionGeometry =
            CameraSessionGeometry(
                frame = frame,
                pairedRotationSnapshot = pairedRotation.copy(rotationMatrix = pairedRotation.rotationMatrix.copyOf()),
                frameRotationDeltaNanos = frameRotationDeltaNanos,
                cropScaleTransform = cropScaleTransform,
                intrinsics = intrinsics,
                viewportSize = viewportSize,
            )
    }
}

/**
 * Returns a new [CameraSessionGeometry] identical to this one in every respect **except**
 * [intrinsics] (CAM-2b diagnostic-fallback hardening). [frame], [CameraSessionGeometry.frame]'s
 * paired rotation, [CameraSessionGeometry.frameRotationDeltaNanos], [CameraSessionGeometry.cropScaleTransform],
 * and [CameraSessionGeometry.viewportSize] are all copied through **exactly** — this is a field-level
 * copy of an already-accepted, already-invariant-checked bundle, never a re-derivation.
 *
 * ## Why this exists, and what it replaces
 * An earlier CAM-2b revision reconstructed a "same but for intrinsics" geometry by re-running the full
 * pure factory [createCameraSessionGeometry] — including its `maxAllowedPairDeltaNanos` tolerance gate
 * — and passed `abs(this.frameRotationDeltaNanos)` (the *actual accepted delta*) as that tolerance. That
 * silently substituted the actual pair delta for the *configured* tolerance the original bundle was
 * validated against (e.g. a real 25 ms configured tolerance became a reconstructed 3 ms one for a
 * geometry whose actual delta happened to be 3 ms) — a fact this bundle never stored and the
 * reconstruction had no way to recover correctly. [withIntrinsics] sidesteps the whole problem: it does
 * not reconstruct a [FrameRotationPairingResult], does not call [pairFrameToNearestRotation], and does
 * not read, derive, or check any tolerance at all — there is no tolerance parameter on this function's
 * signature to conflate with the accepted delta in the first place. It simply copies the already-valid
 * bundle's other fields and swaps in [intrinsics].
 *
 * ## Totality
 * [CameraSessionGeometry.init] never validates anything about [CameraSessionGeometry.intrinsics] — its
 * checks are only the cross-field relationships between [frame]/[cropScaleTransform]/[viewportSize]/
 * [frameRotationDeltaNanos], none of which this function changes. Since `this` already satisfied those
 * checks, the result always satisfies them too: **this function cannot fail** and returns
 * [CameraSessionGeometry] directly, never a [CameraSessionGeometryResult] wrapper.
 *
 * ## Defensive ownership
 * Implemented beside [CameraSessionGeometry.Companion.of] specifically so it can reuse that factory's
 * own defensive-copying contract: [CameraSessionGeometry.pairedRotation] (read here) already returns a
 * fresh copy of the stored snapshot, and [CameraSessionGeometry.Companion.of] copies its own
 * `rotationMatrix` argument again on the way in — so the returned geometry's internal snapshot array is
 * a distinct, independently-owned array from both `this` geometry's internal snapshot and whatever
 * array any caller elsewhere still holds a reference to. Mutating any of those arrays after this call
 * can never affect `this`, the result, or each other.
 *
 * @param intrinsics the resolved-or-fallback intrinsics the returned geometry should carry. Compared by
 *   the caller against `this.intrinsics` when it needs to know whether a substitution actually occurred
 *   (this function does not itself compare or reject an unchanged value).
 */
fun CameraSessionGeometry.withIntrinsics(intrinsics: CameraIntrinsicsResolution): CameraSessionGeometry =
    CameraSessionGeometry.of(
        frame = this.frame,
        pairedRotation = this.pairedRotation,
        frameRotationDeltaNanos = this.frameRotationDeltaNanos,
        cropScaleTransform = this.cropScaleTransform,
        intrinsics = intrinsics,
        viewportSize = this.viewportSize,
    )
