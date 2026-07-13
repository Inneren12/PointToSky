package dev.pointtosky.core.astro.projection.camera

/**
 * One display-remapped rotation-matrix sample with the sensor timestamp it was produced from
 * (CAM-1d). Android-independent mirror of `RotationFrame` (`dev.pointtosky.mobile.ar.RotationFrame`)
 * carrying only what timestamp pairing needs — no forward-vector convenience field, no Android
 * types.
 *
 * [timestampNanos] is the raw `SensorEvent.timestamp` the sample was built from, propagated
 * unmodified from the production rotation pipeline — never `System.nanoTime()` or a wall-clock
 * value. It shares no assumed clock base with a camera frame's `timestampNanos`; see
 * [pairFrameToNearestRotation] and `docs/camera_coordinate_calibration_contract.md` §4.
 *
 * This class does not defensively copy [rotationMatrix] itself — matching the existing
 * `RotationFrame` convention of copying at the call site that hands off ownership, not inside the
 * data class. The boundary that matters for CAM-1d, [RotationSampleHistory], performs its own
 * defensive copy on both ingestion ([RotationSampleHistory.add]) and retrieval
 * ([RotationSampleHistory.nearest]/[RotationSampleHistory.snapshot]).
 */
data class TimedRotationSample(
    val timestampNanos: Long,
    val rotationMatrix: FloatArray,
) {
    init {
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative; was $timestampNanos" }
        require(rotationMatrix.size == ROTATION_MATRIX_SIZE) {
            "rotationMatrix must have exactly $ROTATION_MATRIX_SIZE elements; was ${rotationMatrix.size}"
        }
    }

    private companion object {
        const val ROTATION_MATRIX_SIZE = 9
    }
}
