package dev.pointtosky.core.astro.projection.camera

/**
 * CAM-1d: every timestamp-pairing/compatibility threshold lives here, in one place, with explicit
 * units in the names — nowhere else in the pairing/history/diagnostics/UI code may hardcode one of
 * these as a magic literal.
 *
 * These are conservative starting points chosen from first principles, not from a single device
 * measurement (see the physical-device gate in `docs/camera_coordinate_calibration_contract.md`
 * §4 for what has actually been observed): they must not be read as "the" correct values for all
 * Android devices.
 */
object TimestampSyncConfig {
    /**
     * A camera frame and a rotation sample may be paired only when their timestamps are within this
     * many nanoseconds of each other. `SENSOR_DELAY_GAME` nominally delivers events every ~20 ms; 50
     * ms covers a couple of sensor ticks of jitter plus scheduling latency on either stream while
     * staying well below the angular motion a person would perceive as mis-pointed (star fields move
     * slowly — see §4.3 of the calibration contract).
     */
    const val MAX_PAIR_DELTA_NANOS = 50_000_000L // 50 ms

    /**
     * Deltas at or beyond this size cannot plausibly arise from scheduling jitter between two streams
     * that share a real time base — they are evidence the camera and sensor clocks do not share a
     * usable base at all, not merely that samples are temporarily sparse. Two orders of magnitude
     * above [MAX_PAIR_DELTA_NANOS] so ordinary tolerance rejections are never misclassified as a
     * clock-base mismatch.
     */
    const val CLOCK_MISMATCH_THRESHOLD_NANOS = 5_000_000_000L // 5 s

    /**
     * Bounded rotation-sample history capacity. At `SENSOR_DELAY_GAME` (~50 Hz nominal on most
     * devices), 120 samples is roughly 2 seconds of history — comfortably more than
     * [MAX_PAIR_DELTA_NANOS] wide, so a live camera frame almost always has a bracketing sample
     * without the history growing unbounded.
     */
    const val ROTATION_HISTORY_CAPACITY = 120

    /**
     * Number of consecutive successfully paired frames (within [MAX_PAIR_DELTA_NANOS]) required
     * before the diagnostic status may move from UNKNOWN to COMPATIBLE_OBSERVED. A handful of
     * observations is enough to rule out a one-off coincidence without over-claiming after a single
     * lucky pair.
     */
    const val MIN_OBSERVATIONS_FOR_COMPATIBLE = 5

    /**
     * Number of *consecutive* clock-mismatch-suspected results required before the diagnostic status
     * may move to MISMATCH_SUSPECTED. One isolated bad sample (e.g. a single delayed frame) must not
     * flip the whole session's verdict; only a run of them is treated as real evidence.
     */
    const val MIN_CONSECUTIVE_MISMATCHES_FOR_SUSPECTED = 3
}
