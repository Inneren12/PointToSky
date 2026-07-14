package dev.pointtosky.core.astro.projection.camera

/**
 * Immutable, Android-independent camera-session geometry bundle (CAM-1f): one camera frame
 * (CAM-1c), the rotation sample paired to that exact frame timestamp (CAM-1d), the image↔display
 * [CropScaleTransform] built from that same frame and viewport (CAM-1e), and resolved-or-fallback
 * camera intrinsics (CAM-1b). Intended as the input a future predict-only matcher reads; this PR
 * adds no star matching, detection, interpolation, or renderer change, and nothing yet consumes
 * this bundle.
 *
 * ## Construction invariants
 * The only construction path is [createCameraSessionGeometry]; the primary constructor is
 * `private` and `@ConsistentCopyVisibility` makes the generated `copy()` private too, so no other
 * path — including a hand-edited `copy()` — can produce an instance with fields out of sync. `init`
 * re-derives and checks the cross-field invariants anyway (defense in depth, matching
 * [CropScaleTransform]'s own convention):
 *  - [cropScaleTransform].sourceBufferSize equals [frame]'s buffer dimensions;
 *  - [cropScaleTransform].rotationDegrees equals [frame].rotationDegrees;
 *  - [cropScaleTransform].viewportSize equals [viewportSize];
 *  - [frameRotationDeltaNanos] equals `frame.timestampNanos - pairedRotation.timestampNanos`,
 *    computed with the same overflow-safe helper [FrameRotationPairingResult] uses.
 *
 * A fifth invariant — the paired delta must be within whatever pairing tolerance was configured —
 * is guaranteed **transitively**, not re-checked against a hardcoded constant here:
 * [createCameraSessionGeometry] accepts only [FrameRotationPairingResult.Paired], and that variant
 * is only ever produced by [pairFrameToNearestRotation] when the delta is within *its caller's*
 * `maxAllowedDeltaNanos` (which may differ from [TimestampSyncConfig.MAX_PAIR_DELTA_NANOS] if a
 * session is configured with a non-default tolerance). Re-deriving against the default constant
 * here would wrongly reject a bundle built from a deliberately different tolerance.
 *
 * @property frame the exact camera frame this bundle describes.
 * @property pairedRotation the rotation sample [frame] was paired to; defensively owned — the
 *   canonical factory copies the backing array, so a caller mutating an array it passed in
 *   elsewhere cannot corrupt this bundle, and mutating the array read back out of this property
 *   cannot corrupt the bundle either.
 * @property frameRotationDeltaNanos `frame.timestampNanos - pairedRotation.timestampNanos`.
 * @property cropScaleTransform the FILL_CENTER image↔display mapping for [frame] and
 *   [viewportSize].
 * @property intrinsics resolved-or-fallback camera intrinsics; see [CameraIntrinsicsResolution].
 * @property viewportSize the exact viewport dimensions [cropScaleTransform] was built from.
 */
@ConsistentCopyVisibility
data class CameraSessionGeometry private constructor(
    val frame: CameraFrameMetadata,
    val pairedRotation: TimedRotationSample,
    val frameRotationDeltaNanos: Long,
    val cropScaleTransform: CropScaleTransform,
    val intrinsics: CameraIntrinsicsResolution,
    val viewportSize: PixelSize,
) {
    init {
        val expectedBufferSize = PixelSize(frame.bufferWidthPx.toDouble(), frame.bufferHeightPx.toDouble())
        require(cropScaleTransform.sourceBufferSize == expectedBufferSize) {
            "cropScaleTransform.sourceBufferSize ${cropScaleTransform.sourceBufferSize} must match the frame " +
                "buffer $expectedBufferSize"
        }
        require(cropScaleTransform.rotationDegrees == frame.rotationDegrees) {
            "cropScaleTransform.rotationDegrees ${cropScaleTransform.rotationDegrees} must match " +
                "frame.rotationDegrees ${frame.rotationDegrees}"
        }
        require(cropScaleTransform.viewportSize == viewportSize) {
            "cropScaleTransform.viewportSize ${cropScaleTransform.viewportSize} must match viewportSize $viewportSize"
        }
        val expectedDelta = overflowSafeDeltaNanos(frame.timestampNanos, pairedRotation.timestampNanos)
        require(frameRotationDeltaNanos == expectedDelta) {
            "frameRotationDeltaNanos $frameRotationDeltaNanos must equal frame.timestampNanos - " +
                "pairedRotation.timestampNanos ($expectedDelta)"
        }
    }

    internal companion object {
        /**
         * Canonical, defensive-copying factory. `internal` — the only sanctioned caller is the pure
         * top-level factory [createCameraSessionGeometry], in the same module.
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
                pairedRotation = pairedRotation.copy(rotationMatrix = pairedRotation.rotationMatrix.copyOf()),
                frameRotationDeltaNanos = frameRotationDeltaNanos,
                cropScaleTransform = cropScaleTransform,
                intrinsics = intrinsics,
                viewportSize = viewportSize,
            )
    }
}
