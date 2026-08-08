package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockAlignment

/**
 * SKY-1 (`internalDebug`-only): where a frame timestamp comes from, and what that does — and does not
 * — prove about comparing it to a pose timestamp.
 *
 * ## Why this file exists
 * The capture path used to write `poseToFrameOffsetNanos = 0` into every session header and describe
 * it as measured. Nothing measured it. The justification was that CAM-1d's
 * `pairFrameToNearestRotation` pairs the two directly and usually succeeds — but a successful pairing
 * is evidence, not proof: a device whose clocks differ by less than the pairing tolerance pairs
 * happily and wrongly, and the absence of a `ClockMismatchSuspected` outcome says nothing at all about
 * a device whose skew is small. Camera id, vendor, and observed pairing deltas are all equally
 * inadmissible.
 *
 * What *is* admissible is the platform's own statement. Camera2 publishes
 * `SENSOR_INFO_TIMESTAMP_SOURCE` per camera:
 *
 *  - `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` — the docs are explicit that `SENSOR_TIMESTAMP` is then
 *    on `SystemClock.elapsedRealtimeNanos`, which is the same base
 *    `android.hardware.SensorEvent.timestamp` is documented to use. That, and only that, makes the two
 *    directly comparable with a zero offset.
 *  - `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN` — the timestamps are on an unspecified base that is
 *    explicitly *not* guaranteed to relate to any other clock on the device. Nothing can be aligned.
 *
 * ## Why the mapping lives here and not in `:core:astro-core`
 * `android.hardware.camera2` must not leak into the pure module — that module is JVM-testable
 * precisely because it has no Android types. So the Camera2 constants are read and named here, and
 * only the platform-free [SkyClockAlignment] crosses the boundary. [skyCameraTimestampSourceOf] takes
 * the raw `Int` rather than a `CameraCharacteristics`, which is what makes the mapping itself testable
 * without a device.
 *
 * ## The doubled opt-in annotation
 * `Camera2Interop` is opt-in, and the two toolchains that police it want different annotations:
 * Kotlin's compiler reads `kotlin.OptIn`, Android Lint's `UnsafeOptInUsageError` reads
 * `androidx.annotation.OptIn`. Only one of them is satisfied by either annotation alone, so both are
 * present here (and on `SkyCaptureExposure`'s equivalents, which lint had been failing on).
 */

/** What the bound camera says about its own `SENSOR_TIMESTAMP` time base. */
internal enum class SkyCameraTimestampSource {
    /** `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`: `SystemClock.elapsedRealtimeNanos`, same base as `SensorEvent`. */
    REALTIME,

    /** `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN`: an unspecified base with no documented relation to anything. */
    UNKNOWN,

    /** The characteristic could not be read at all (no `Camera2CameraInfo`, no bound camera, a throwing vendor HAL). */
    UNAVAILABLE,

    /**
     * The device reported a `SENSOR_INFO_TIMESTAMP_SOURCE` value this build does not know. Kept
     * distinct from [UNKNOWN] so a future Android constant shows up as "we did not recognise this"
     * rather than being quietly folded into a value the platform actually defines.
     */
    UNRECOGNIZED,
}

/**
 * Maps a raw `SENSOR_INFO_TIMESTAMP_SOURCE` value. A `null` — the key absent, or unreadable — is
 * [SkyCameraTimestampSource.UNAVAILABLE], never optimistically treated as `REALTIME`.
 */
internal fun skyCameraTimestampSourceOf(rawTimestampSource: Int?): SkyCameraTimestampSource =
    when (rawTimestampSource) {
        null -> SkyCameraTimestampSource.UNAVAILABLE
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> SkyCameraTimestampSource.REALTIME
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> SkyCameraTimestampSource.UNKNOWN
        else -> SkyCameraTimestampSource.UNRECOGNIZED
    }

/**
 * Reads `SENSOR_INFO_TIMESTAMP_SOURCE` for the **actually bound** camera.
 *
 * Never throws, for the same reason [probeSkyManualExposureCapability] does not: every Camera2
 * characteristics read is optional on some device somewhere, and a probe that crashes the experiment
 * is worse than one that reports [SkyCameraTimestampSource.UNAVAILABLE] and lets the log say so.
 */
@OptIn(ExperimentalCamera2Interop::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal fun probeSkyCameraTimestampSource(cameraInfo: CameraInfo): SkyCameraTimestampSource {
    val camera2Info =
        runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull()
            ?: return SkyCameraTimestampSource.UNAVAILABLE
    val rawTimestampSource =
        runCatching {
            camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        }.getOrNull()
    return skyCameraTimestampSourceOf(rawTimestampSource)
}

/**
 * The session's frame/pose clock relationship, derived from [source] and nothing else.
 *
 * Only [SkyCameraTimestampSource.REALTIME] yields a comparable pair, and it does so as
 * `SOURCE_PROVEN_COMPARABLE` — an implied zero grounded in the platform contract — rather than as a
 * `MEASURED_OFFSET` of `0`, which would claim a measurement this code does not perform. Every other
 * source yields an unalignable session: the frames, poses, pixels and exposures are still worth
 * recording, and replay will skip the projection with `POSE_CLOCK_UNALIGNED` instead of inventing a
 * relationship.
 */
internal fun skyClockAlignmentFor(source: SkyCameraTimestampSource): SkyClockAlignment =
    when (source) {
        SkyCameraTimestampSource.REALTIME ->
            SkyClockAlignment.sourceProvenComparable(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
            )

        SkyCameraTimestampSource.UNKNOWN,
        SkyCameraTimestampSource.UNRECOGNIZED,
        ->
            SkyClockAlignment.unknown(
                // The pose side is still known - it is read from SensorEvent.timestamp in RotationFrame.kt
                // - so it is named honestly. It is the frame side whose base the device would not state.
                frameClock = SkyClock.UNKNOWN,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
            )

        SkyCameraTimestampSource.UNAVAILABLE ->
            SkyClockAlignment.unknown(
                frameClock = SkyClock.UNKNOWN,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
            )
    }
