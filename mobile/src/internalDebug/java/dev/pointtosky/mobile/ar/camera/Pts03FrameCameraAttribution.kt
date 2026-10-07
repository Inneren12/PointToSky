package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample

/**
 * PTS-03 (`internalDebug`-only): which physical camera produced one analysis frame, and which result's
 * dynamic metadata may be read as that frame's truth.
 *
 * The two session classes mean different things:
 *
 * - **A1 [Pts03SessionClass.LOGICAL_UNPINNED]**: the analysed stream is the logical camera's. Its producing
 *   physical camera is the top-level `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` when reported, otherwise
 *   unknown. The top-level result is the stream's own dynamic metadata. Natural switching is inferred only here.
 * - **A2 [Pts03SessionClass.EXPLICIT_PHYSICAL]**: the requested ID is set on the `CameraSelector` and, since
 *   the PTS-03 follow-up, on both use cases via `Camera2Interop` (see [Pts03PhysicalBindingRequest]), so the
 *   producing camera is the requested ID **by configuration**. The
 *   top-level active ID describes the logical camera's backing sensor, not this output: it is kept as a
 *   diagnostic and never confirms or contradicts the pin. Dynamic metadata for this output comes only from
 *   the physical result entry for the requested ID with an exactly matching `SENSOR_TIMESTAMP`; when that
 *   entry is absent or mismatched the dynamic part is UNRESOLVED and the top-level (logical) values are
 *   **not** substituted.
 *
 * Groups B and C read only [producingPhysicalCameraId] (static characteristics) and [dynamicTruth] (dynamic
 * metadata) from here, so pixels from one camera are never paired with another camera's metadata.
 */
internal enum class Pts03DynamicMetadataSource {
    /** A1, active ID reported: the top-level result describes the stream produced by that camera. */
    LOGICAL_TOP_LEVEL_RESULT,

    /** A1, active ID not reported: the top-level result describes the stream, but its producer is unknown. */
    LOGICAL_TOP_LEVEL_RESULT_PRODUCER_UNKNOWN,

    /** A2: the physical result entry for the requested ID, timestamp matched. */
    PHYSICAL_RESULT_FOR_REQUESTED_ID,

    /** A2: no physical result entry for the requested ID. Dynamic physical metadata UNRESOLVED. */
    UNRESOLVED_PHYSICAL_RESULT_NOT_REPORTED,

    /** A2: entry present with a different `SENSOR_TIMESTAMP`. Dynamic physical metadata UNRESOLVED. */
    UNRESOLVED_PHYSICAL_RESULT_TIMESTAMP_MISMATCH,

    /** A2: entry present without a `SENSOR_TIMESTAMP`. Dynamic physical metadata UNRESOLVED. */
    UNRESOLVED_PHYSICAL_RESULT_TIMESTAMP_MISSING,
}

/**
 * PTS-03 follow-up: how one A2 frame's physical-result lookup turned out, together with what the top-level
 * logical active ID said about the logical camera on the same result. The top-level part is a **diagnostic
 * qualifier only**: [PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED] is unresolved dynamic
 * metadata, not evidence that the analysed pixels came from the top-level camera, and
 * [PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED] does not resolve physical metadata either. Only
 * [PHYSICAL_RESULT_PRESENT] does.
 */
internal enum class Pts03PhysicalResultObservation {
    /** A timestamp-matched physical result for the requested ID. */
    PHYSICAL_RESULT_PRESENT,

    /** No physical result for the requested ID; the top-level active ID equals the requested ID. */
    PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED,

    /** No physical result for the requested ID; the top-level active ID is a different camera. */
    PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED,

    /** No physical result for the requested ID, and the top-level active ID is not reported either. */
    PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_NOT_REPORTED,

    /** A physical result for the requested ID whose `SENSOR_TIMESTAMP` differs from the frame's. */
    PHYSICAL_RESULT_TIMESTAMP_MISMATCH,

    /** A physical result for the requested ID without a `SENSOR_TIMESTAMP`. */
    PHYSICAL_RESULT_TIMESTAMP_MISSING,

    /** Session level only: an A1 session, where no physical ID is requested. */
    NOT_APPLICABLE_LOGICAL_SESSION,

    /** Session level only: no matched frames. */
    NO_MATCHED_FRAMES,

    /** Session level only: matched frames did not all share one observation. */
    MIXED,
}

/** The per-frame observation for a requested ID, a lookup status and the top-level logical active ID. */
internal fun pts03PhysicalResultObservationOf(
    requestedPhysicalCameraId: String,
    status: Pts03PhysicalResultStatus,
    logicalActivePhysicalCameraId: String?,
): Pts03PhysicalResultObservation =
    when (status) {
        Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED -> Pts03PhysicalResultObservation.PHYSICAL_RESULT_PRESENT
        Pts03PhysicalResultStatus.TIMESTAMP_MISMATCH ->
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_TIMESTAMP_MISMATCH
        Pts03PhysicalResultStatus.TIMESTAMP_MISSING -> Pts03PhysicalResultObservation.PHYSICAL_RESULT_TIMESTAMP_MISSING
        Pts03PhysicalResultStatus.NOT_REPORTED ->
            when (logicalActivePhysicalCameraId) {
                null -> Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_NOT_REPORTED
                requestedPhysicalCameraId ->
                    Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED
                else -> Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED
            }
    }

internal data class Pts03FrameCameraAttribution(
    val sessionClass: Pts03SessionClass,
    val frameSensorTimestampNanos: Long,
    /** A2 only: the configured physical output. */
    val requestedPhysicalCameraId: String?,
    /** Top-level `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`. In A2 a diagnostic only. */
    val logicalActivePhysicalCameraId: String?,
    /** A1: the reported logical active ID, else `null` (unknown). A2: the requested ID. */
    val producingPhysicalCameraId: String?,
    val dynamicMetadataSource: Pts03DynamicMetadataSource,
    /** A2 only: the requested ID's physical-result status; `null` in A1. */
    val physicalResultStatus: Pts03PhysicalResultStatus?,
    /** A2 only: the requested ID's entry timestamp when an entry exists (also on mismatch). */
    val physicalResultSensorTimestampNanos: Long?,
    /** The dynamic metadata that describes the analysed stream, or `null` when unresolved. */
    val dynamicTruth: Pts03CaptureTruth?,
    val dynamicExposure: SkyExposureSample?,
) {
    /** A2 only: [physicalResultStatus] qualified by the top-level active ID (diagnostic); `null` in A1. */
    val physicalResultObservation: Pts03PhysicalResultObservation?
        get() =
            if (requestedPhysicalCameraId != null && physicalResultStatus != null) {
                pts03PhysicalResultObservationOf(
                    requestedPhysicalCameraId,
                    physicalResultStatus,
                    logicalActivePhysicalCameraId,
                )
            } else {
                null
            }

    /** True when per-camera dynamic metadata is attributable to [producingPhysicalCameraId]. */
    val physicalDynamicMetadataResolved: Boolean
        get() = producingPhysicalCameraId != null && dynamicTruth != null
}

/** Attributes one exact-joined frame. [captureResult]'s top-level timestamp must equal [frameSensorTimestampNanos]. */
internal fun attributePts03Frame(
    sessionClass: Pts03SessionClass,
    requestedPhysicalCameraId: String?,
    frameSensorTimestampNanos: Long,
    captureResult: SkyCaptureResultSnapshot,
): Pts03FrameCameraAttribution {
    require(captureResult.sensorTimestampNanos == frameSensorTimestampNanos) {
        "attribution requires the exact SENSOR_TIMESTAMP join; frame=$frameSensorTimestampNanos " +
            "result=${captureResult.sensorTimestampNanos}"
    }
    val logicalActive = captureResult.logicalTruth.activePhysicalCameraId
    if (sessionClass == Pts03SessionClass.LOGICAL_UNPINNED || requestedPhysicalCameraId == null) {
        return Pts03FrameCameraAttribution(
            sessionClass = Pts03SessionClass.LOGICAL_UNPINNED,
            frameSensorTimestampNanos = frameSensorTimestampNanos,
            requestedPhysicalCameraId = null,
            logicalActivePhysicalCameraId = logicalActive,
            producingPhysicalCameraId = logicalActive,
            dynamicMetadataSource =
                if (logicalActive != null) {
                    Pts03DynamicMetadataSource.LOGICAL_TOP_LEVEL_RESULT
                } else {
                    Pts03DynamicMetadataSource.LOGICAL_TOP_LEVEL_RESULT_PRODUCER_UNKNOWN
                },
            physicalResultStatus = null,
            physicalResultSensorTimestampNanos = null,
            dynamicTruth = captureResult.logicalTruth,
            dynamicExposure = captureResult.exposure,
        )
    }
    val lookup = captureResult.physicalResultFor(requestedPhysicalCameraId, frameSensorTimestampNanos)
    val usable = lookup.usable
    return Pts03FrameCameraAttribution(
        sessionClass = Pts03SessionClass.EXPLICIT_PHYSICAL,
        frameSensorTimestampNanos = frameSensorTimestampNanos,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        logicalActivePhysicalCameraId = logicalActive,
        producingPhysicalCameraId = requestedPhysicalCameraId,
        dynamicMetadataSource =
            when (lookup.status) {
                Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED ->
                    Pts03DynamicMetadataSource.PHYSICAL_RESULT_FOR_REQUESTED_ID
                Pts03PhysicalResultStatus.NOT_REPORTED ->
                    Pts03DynamicMetadataSource.UNRESOLVED_PHYSICAL_RESULT_NOT_REPORTED
                Pts03PhysicalResultStatus.TIMESTAMP_MISMATCH ->
                    Pts03DynamicMetadataSource.UNRESOLVED_PHYSICAL_RESULT_TIMESTAMP_MISMATCH
                Pts03PhysicalResultStatus.TIMESTAMP_MISSING ->
                    Pts03DynamicMetadataSource.UNRESOLVED_PHYSICAL_RESULT_TIMESTAMP_MISSING
            },
        physicalResultStatus = lookup.status,
        physicalResultSensorTimestampNanos = lookup.entrySensorTimestampNanos,
        dynamicTruth = usable?.truth,
        dynamicExposure = usable?.exposure,
    )
}

/**
 * The effective distortion mode of the analysed stream as far as attribution allows: an A2 frame without a
 * usable physical result is [Pts03EffectiveDistortionMode.PhysicalResultUnavailable], never the logical mode.
 */
internal fun Pts03FrameCameraAttribution.effectiveDistortionMode(): Pts03EffectiveDistortionMode =
    dynamicTruth?.effectiveDistortionMode() ?: Pts03EffectiveDistortionMode.PhysicalResultUnavailable
