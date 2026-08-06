package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * SKY-1 (`internalDebug`-only): the manual-exposure gate.
 *
 * Setting `CONTROL_AE_MODE_OFF` is a request, not a fact. These tests pin the two places that turn it
 * into one: [evaluateSkyRecordingGate], which refuses to start a session that cannot be manually
 * exposed, and [validateSkyManualExposure], which refuses a frame whose own `CaptureResult` says
 * otherwise.
 */
class SkyManualExposureGateTest {
    private val fixtures = SkySessionCaptureFixtures

    // -----------------------------------------------------------------------------------------
    // Resolution, including the frame-duration constraint
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a request inside every advertised range resolves unchanged with frame duration equal to exposure`() {
        val resolved =
            assertIs<SkyExposureResolution.Resolved>(
                fixtures.manualExposureCapability().resolve(DEFAULT_SKY_EXPOSURE),
            ).exposure

        assertEquals(DEFAULT_SKY_EXPOSURE.exposureTimeNanos, resolved.exposureTimeNanos)
        assertEquals(DEFAULT_SKY_EXPOSURE.sensitivityIso, resolved.sensitivityIso)
        assertEquals(resolved.exposureTimeNanos, resolved.frameDurationNanos)
    }

    @Test
    fun `an over-long request is clamped into the advertised exposure range`() {
        val capability = fixtures.manualExposureCapability(exposureTimeRangeNanos = 100_000L..250_000_000L)

        val resolved =
            assertIs<SkyExposureResolution.Resolved>(
                capability.resolve(
                    SkyManualExposureRequest(exposureTimeNanos = 2_000_000_000L, sensitivityIso = 12_800),
                ),
            ).exposure

        assertEquals(250_000_000L, resolved.exposureTimeNanos)
        assertEquals(250_000_000L, resolved.frameDurationNanos)
    }

    @Test
    fun `an out-of-range ISO is clamped`() {
        val capability = fixtures.manualExposureCapability(sensitivityRange = 50..3200)

        val resolved =
            assertIs<SkyExposureResolution.Resolved>(
                capability.resolve(SkyManualExposureRequest(exposureTimeNanos = 500_000_000L, sensitivityIso = 12_800)),
            ).exposure

        assertEquals(3200, resolved.sensitivityIso)
    }

    @Test
    fun `an exposure longer than the max frame duration is capped rather than requested illegally`() {
        // The device advertises a 4 s exposure range but can only produce 1 s frames. Requesting a 2 s
        // exposure with a 2 s frame duration would be rejected wholesale by the HAL, which then falls
        // back to auto - so the exposure is capped instead.
        val capability =
            fixtures.manualExposureCapability(
                exposureTimeRangeNanos = 100_000L..4_000_000_000L,
                maxFrameDurationNanos = 1_000_000_000L,
            )

        val resolved =
            assertIs<SkyExposureResolution.Resolved>(
                capability.resolve(SkyManualExposureRequest(exposureTimeNanos = 2_000_000_000L, sensitivityIso = 400)),
            ).exposure

        assertEquals(1_000_000_000L, resolved.exposureTimeNanos)
        assertEquals(1_000_000_000L, resolved.frameDurationNanos)
    }

    @Test
    fun `a minimum frame duration longer than the exposure raises the frame duration, not the exposure`() {
        val capability =
            fixtures
                .manualExposureCapability(
                    maxFrameDurationNanos = 4_000_000_000L,
                ).copy(minFrameDurationNanos = 800_000_000L)

        val resolved =
            assertIs<SkyExposureResolution.Resolved>(
                capability.resolve(SkyManualExposureRequest(exposureTimeNanos = 500_000_000L, sensitivityIso = 1600)),
            ).exposure

        assertEquals(500_000_000L, resolved.exposureTimeNanos)
        assertEquals(800_000_000L, resolved.frameDurationNanos)
    }

    @Test
    fun `a minimum frame duration beyond the maximum is unresolvable rather than silently ignored`() {
        val capability =
            fixtures
                .manualExposureCapability(maxFrameDurationNanos = 500_000_000L)
                .copy(minFrameDurationNanos = 900_000_000L)

        val unresolvable =
            assertIs<SkyExposureResolution.Unresolvable>(
                capability.resolve(SkyManualExposureRequest(exposureTimeNanos = 100_000_000L, sensitivityIso = 800)),
            )

        assertEquals(SkyExposureUnresolvableReason.FRAME_DURATION_UNSATISFIABLE, unresolvable.reason)
    }

    @Test
    fun `a max frame duration below the minimum exposure leaves no legal exposure`() {
        val capability =
            fixtures.manualExposureCapability(
                exposureTimeRangeNanos = 1_000_000_000L..4_000_000_000L,
                maxFrameDurationNanos = 100_000_000L,
            )

        val unresolvable = assertIs<SkyExposureResolution.Unresolvable>(capability.resolve(DEFAULT_SKY_EXPOSURE))

        assertEquals(SkyExposureUnresolvableReason.EXPOSURE_RANGE_UNSATISFIABLE, unresolvable.reason)
    }

    @Test
    fun `an unsupported camera resolves nothing`() {
        val capability =
            fixtures.manualExposureCapability(
                supported = false,
                unsupportedReason = SkyManualExposureUnsupportedReason.MANUAL_SENSOR_CAPABILITY_ABSENT,
            )

        val unresolvable = assertIs<SkyExposureResolution.Unresolvable>(capability.resolve(DEFAULT_SKY_EXPOSURE))

        assertEquals(SkyExposureUnresolvableReason.MANUAL_EXPOSURE_UNSUPPORTED, unresolvable.reason)
    }

    @Test
    fun `a capability must carry a reason exactly when unsupported`() {
        assertFailsWith<IllegalArgumentException> { SkyManualExposureCapability(supported = false) }
        assertFailsWith<IllegalArgumentException> {
            SkyManualExposureCapability(
                supported = true,
                unsupportedReason = SkyManualExposureUnsupportedReason.CAMERA2_INFO_UNAVAILABLE,
            )
        }
    }

    @Test
    fun `a resolved exposure whose frame duration is shorter than its exposure is impossible to construct`() {
        assertFailsWith<IllegalArgumentException> {
            SkyResolvedExposure(exposureTimeNanos = 500L, sensitivityIso = 100, frameDurationNanos = 499L)
        }
    }

    // -----------------------------------------------------------------------------------------
    // The recording gate
    // -----------------------------------------------------------------------------------------

    private fun gate(
        requested: SkyManualExposureRequest? = DEFAULT_SKY_EXPOSURE,
        capability: SkyManualExposureCapability? = fixtures.manualExposureCapability(),
        applied: SkyResolvedExposure? =
            (
                fixtures.manualExposureCapability().resolve(
                    DEFAULT_SKY_EXPOSURE,
                ) as SkyExposureResolution.Resolved
            ).exposure,
        intrinsicsResolved: Boolean = true,
    ) = evaluateSkyRecordingGate(requested, capability, applied, intrinsicsResolved)

    @Test
    fun `a fully resolved manual session is allowed`() {
        assertIs<SkyRecordingGate.Allowed>(gate())
    }

    @Test
    fun `auto exposure is never allowed for this dataset`() {
        assertEquals(
            SkyRecordingBlockedReason.AUTO_EXPOSURE_NOT_ALLOWED,
            assertIs<SkyRecordingGate.Blocked>(gate(requested = null)).reason,
        )
    }

    @Test
    fun `an unbound camera blocks with an unknown-capability reason`() {
        assertEquals(
            SkyRecordingBlockedReason.CAMERA_CAPABILITY_UNKNOWN,
            assertIs<SkyRecordingGate.Blocked>(gate(capability = null)).reason,
        )
    }

    @Test
    fun `a camera without MANUAL_SENSOR blocks`() {
        val capability =
            fixtures.manualExposureCapability(
                supported = false,
                unsupportedReason = SkyManualExposureUnsupportedReason.MANUAL_SENSOR_CAPABILITY_ABSENT,
            )

        assertEquals(
            SkyRecordingBlockedReason.MANUAL_SENSOR_CAPABILITY_ABSENT,
            assertIs<SkyRecordingGate.Blocked>(gate(capability = capability, applied = null)).reason,
        )
    }

    @Test
    fun `a camera without CONTROL_AE_MODE_OFF blocks with its own reason`() {
        val capability =
            fixtures.manualExposureCapability(
                supported = false,
                unsupportedReason = SkyManualExposureUnsupportedReason.CONTROL_AE_MODE_OFF_UNAVAILABLE,
            )

        assertEquals(
            SkyRecordingBlockedReason.CONTROL_AE_MODE_OFF_UNAVAILABLE,
            assertIs<SkyRecordingGate.Blocked>(gate(capability = capability, applied = null)).reason,
        )
    }

    @Test
    fun `a request that cannot be clamped into the device ranges blocks`() {
        val capability =
            fixtures.manualExposureCapability(
                exposureTimeRangeNanos = 1_000_000_000L..4_000_000_000L,
                maxFrameDurationNanos = 100_000_000L,
            )

        assertEquals(
            SkyRecordingBlockedReason.EXPOSURE_RANGE_UNSATISFIABLE,
            assertIs<SkyRecordingGate.Blocked>(gate(capability = capability, applied = null)).reason,
        )
    }

    @Test
    fun `the resolved exposure must be the one actually bound before recording is allowed`() {
        // Phase one of the two-phase bind: the capability is known and the request resolves, but the
        // session is still running the pre-probe bind that carries no exposure at all.
        assertEquals(
            SkyRecordingBlockedReason.EXPOSURE_NOT_APPLIED_YET,
            assertIs<SkyRecordingGate.Blocked>(gate(applied = null)).reason,
        )
    }

    @Test
    fun `a stale applied exposure from a previous request blocks until the rebind lands`() {
        val stale =
            SkyResolvedExposure(
                exposureTimeNanos = 125_000_000L,
                sensitivityIso = 3200,
                frameDurationNanos = 125_000_000L,
            )

        assertEquals(
            SkyRecordingBlockedReason.EXPOSURE_NOT_APPLIED_YET,
            assertIs<SkyRecordingGate.Blocked>(gate(applied = stale)).reason,
        )
    }

    @Test
    fun `unresolved intrinsics block recording`() {
        assertEquals(
            SkyRecordingBlockedReason.INTRINSICS_NOT_RESOLVED,
            assertIs<SkyRecordingGate.Blocked>(gate(intrinsicsResolved = false)).reason,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Per-frame validation
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a matching manual-exposure result is accepted`() {
        val validation =
            validateSkyManualExposure(fixtures.exposureSample(sensorTimestampNanos = 42L), frameTimestampNanos = 42L)

        assertIs<SkyExposureValidation.Accepted>(validation)
    }

    @Test
    fun `a result belonging to another frame is rejected`() {
        val validation =
            validateSkyManualExposure(fixtures.exposureSample(sensorTimestampNanos = 41L), frameTimestampNanos = 42L)

        assertEquals(
            SkyExposureRejectReason.SENSOR_TIMESTAMP_MISMATCH,
            assertIs<SkyExposureValidation.Rejected>(validation).reason,
        )
    }

    @Test
    fun `a result with no exposure time is rejected`() {
        val validation =
            validateSkyManualExposure(
                fixtures.exposureSample(sensorTimestampNanos = 42L, exposureTimeNanos = null),
                frameTimestampNanos = 42L,
            )

        assertEquals(
            SkyExposureRejectReason.EXPOSURE_TIME_MISSING,
            assertIs<SkyExposureValidation.Rejected>(validation).reason,
        )
    }

    @Test
    fun `a result with no sensitivity is rejected`() {
        val validation =
            validateSkyManualExposure(
                fixtures.exposureSample(sensorTimestampNanos = 42L, sensitivityIso = null),
                frameTimestampNanos = 42L,
            )

        assertEquals(
            SkyExposureRejectReason.SENSITIVITY_MISSING,
            assertIs<SkyExposureValidation.Rejected>(validation).reason,
        )
    }

    @Test
    fun `a result whose AE was still on is rejected`() {
        val validation =
            validateSkyManualExposure(
                fixtures.exposureSample(sensorTimestampNanos = 42L, aeMode = "ON"),
                frameTimestampNanos = 42L,
            )

        assertEquals(
            SkyExposureRejectReason.AE_MODE_NOT_OFF,
            assertIs<SkyExposureValidation.Rejected>(validation).reason,
        )
    }

    @Test
    fun `a result that did not report an AE mode at all is rejected`() {
        val validation =
            validateSkyManualExposure(
                fixtures.exposureSample(sensorTimestampNanos = 42L, aeMode = null),
                frameTimestampNanos = 42L,
            )

        assertEquals(
            SkyExposureRejectReason.AE_MODE_NOT_OFF,
            assertIs<SkyExposureValidation.Rejected>(validation).reason,
        )
    }
}
