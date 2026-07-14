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
