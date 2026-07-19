package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [physicalExperimentCompactStatus]/[buildPhysicalCameraExperimentCompactSummaryText]
 * (correctness fix - a prior revision derived `status = if (bindingResolution == null) "BINDING" else
 * "BOUND"`, which silently reported `"BOUND"` for every one of [PhysicalCameraBindingResolution]'s three
 * non-[PhysicalCameraBindingResolution.Bound] variants too, none of which is a verified physical-camera
 * binding). Every [PhysicalCameraBindingResolution] variant, plus the terminal
 * [ExperimentSessionState.explicitBindFailureReason] case, is exercised independently here so a future
 * regression to the old "non-null means bound" shortcut fails a test immediately.
 */
class PhysicalCameraExperimentCompactStatusTest {
    private val provenance =
        PhysicalCameraProvenance(
            logicalCameraId = "0",
            physicalCameraId = "2",
            bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
            bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
        )

    private val snapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(3.6f),
            sensorPhysicalWidthMm = 6.4f,
            sensorPhysicalHeightMm = 4.8f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4032,
            activeArrayBottomPx = 3024,
            pixelArrayWidthPx = 4032,
            pixelArrayHeightPx = 3024,
            isLogicalMultiCamera = false,
            cameraId = "2",
        )

    private fun baseState(
        attemptId: Long = 1L,
        physicalCameraId: String = "2",
    ) = initialExperimentSessionState(attemptId = attemptId, physicalCameraId = physicalCameraId)

    @Test
    fun noBindingResolutionYieldsBinding() {
        val state = baseState()

        assertEquals(PhysicalExperimentCompactStatus.BINDING, physicalExperimentCompactStatus(state))
        assertTrue(
            buildPhysicalCameraExperimentCompactSummaryText(state).contains("status=BINDING"),
        )
    }

    @Test
    fun boundResolutionYieldsBoundVerified() {
        val state =
            baseState().copy(
                bindingResolution =
                    PhysicalCameraBindingResolution.Bound(
                        provenance = provenance,
                        physicalCharacteristicsSnapshot = snapshot,
                    ),
            )

        assertEquals(PhysicalExperimentCompactStatus.BOUND_VERIFIED, physicalExperimentCompactStatus(state))
        assertTrue(
            buildPhysicalCameraExperimentCompactSummaryText(state).contains("status=BOUND_VERIFIED"),
        )
    }

    @Test
    fun bindingUnavailableYieldsBindingUnavailableNeverBoundVerified() {
        val state =
            baseState().copy(
                bindingResolution =
                    PhysicalCameraBindingResolution.PhysicalCameraBindingUnavailable("explicit_bind_rejected"),
            )

        assertEquals(PhysicalExperimentCompactStatus.BINDING_UNAVAILABLE, physicalExperimentCompactStatus(state))
        val text = buildPhysicalCameraExperimentCompactSummaryText(state)
        assertTrue(text.contains("status=BINDING_UNAVAILABLE"))
        assertTrue(!text.contains("status=BOUND_VERIFIED"))
    }

    @Test
    fun identityUnverifiedYieldsIdentityUnverifiedNeverBoundVerified() {
        val state =
            baseState().copy(
                bindingResolution = PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified,
            )

        assertEquals(PhysicalExperimentCompactStatus.IDENTITY_UNVERIFIED, physicalExperimentCompactStatus(state))
        val text = buildPhysicalCameraExperimentCompactSummaryText(state)
        assertTrue(text.contains("status=IDENTITY_UNVERIFIED"))
        assertTrue(!text.contains("status=BOUND_VERIFIED"))
    }

    @Test
    fun characteristicsMismatchYieldsCharacteristicsMismatchNeverBoundVerified() {
        val state =
            baseState().copy(
                bindingResolution =
                    PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch(
                        expectedPhysicalCameraId = "2",
                        actualCameraId = "3",
                    ),
            )

        assertEquals(PhysicalExperimentCompactStatus.CHARACTERISTICS_MISMATCH, physicalExperimentCompactStatus(state))
        val text = buildPhysicalCameraExperimentCompactSummaryText(state)
        assertTrue(text.contains("status=CHARACTERISTICS_MISMATCH"))
        assertTrue(!text.contains("status=BOUND_VERIFIED"))
    }

    @Test
    fun explicitBindFailureReasonWinsOverABoundResolution() {
        // A terminal explicit-bind/zoom failure reported after a Bound resolution was already recorded -
        // explicitBindFailureReason must still win, never BOUND_VERIFIED.
        val state =
            baseState().copy(
                bindingResolution =
                    PhysicalCameraBindingResolution.Bound(
                        provenance = provenance,
                        physicalCharacteristicsSnapshot = snapshot,
                    ),
                explicitBindFailureReason = "explicit_selector_zoom_failed",
            )

        assertEquals(PhysicalExperimentCompactStatus.EXPLICIT_BIND_FAILED, physicalExperimentCompactStatus(state))
        assertTrue(
            buildPhysicalCameraExperimentCompactSummaryText(state).contains("status=EXPLICIT_BIND_FAILED"),
        )
    }

    @Test
    fun explicitBindFailureReasonWinsOverEveryNonBoundVariantToo() {
        val state =
            baseState().copy(
                bindingResolution = PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified,
                explicitBindFailureReason = "explicit_selector_zoom_failed",
            )

        assertEquals(PhysicalExperimentCompactStatus.EXPLICIT_BIND_FAILED, physicalExperimentCompactStatus(state))
    }

    @Test
    fun summaryAlwaysCarriesPhysicalIdAttemptIdAndFrameCountRegardlessOfStatus() {
        val bound =
            baseState(attemptId = 42L, physicalCameraId = "3")
                .copy(
                    bindingResolution =
                        PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch(
                            expectedPhysicalCameraId = "3",
                            actualCameraId = null,
                        ),
                )
        var state = bound
        repeat(5) { i ->
            state =
                state.copy(
                    matrixStability = state.matrixStability.copy(framesObserved = (i + 1).toLong()),
                )
        }

        val text = buildPhysicalCameraExperimentCompactSummaryText(state)
        assertTrue(text.contains("physicalId=3"))
        assertTrue(text.contains("attemptId=42"))
        assertTrue(text.contains("frames=5"))
        assertTrue(text.contains("status=CHARACTERISTICS_MISMATCH"))
    }
}
