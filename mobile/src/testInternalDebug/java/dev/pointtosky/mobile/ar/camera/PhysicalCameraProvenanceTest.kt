package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pure JVM tests for [verifyPhysicalCameraProvenance] and [selectPhysicalCameraInfoSource] (CAM-2c
 * physical-camera provenance experiment, task §5/§6/§11). No CameraX/Android types involved - this is
 * the exact logic that decides whether a physical-camera binding is trustworthy enough for CAM-2c to
 * use, and which of the two supported `CameraInfo` shapes (fix for a correctness gap) a given binding
 * matched.
 */
class PhysicalCameraProvenanceTest {
    private fun snapshotOf(
        cameraId: String? = "2",
        isLogicalMultiCamera: Boolean = false,
    ) = CameraCharacteristicsSnapshot(
        availableFocalLengthsMm = floatArrayOf(3.6f),
        sensorPhysicalWidthMm = 6.4f,
        sensorPhysicalHeightMm = 4.8f,
        activeArrayLeftPx = 0,
        activeArrayTopPx = 0,
        activeArrayRightPx = 4032,
        activeArrayBottomPx = 3024,
        pixelArrayWidthPx = 4032,
        pixelArrayHeightPx = 3024,
        isLogicalMultiCamera = isLogicalMultiCamera,
        cameraId = cameraId,
    )

    @Test
    fun `matching cameraId and non-logical snapshot verifies as Bound, shape B`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2"),
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        val bound = assertIs<PhysicalCameraBindingResolution.Bound>(result)
        assertEquals("0", bound.provenance.logicalCameraId)
        assertEquals("2", bound.provenance.physicalCameraId)
        assertEquals(PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID, bound.provenance.bindingMethod)
        assertEquals(PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO, bound.provenance.bindingSource)
        assertEquals(PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY, bound.provenance.confidence)
        assertEquals("2", bound.physicalCharacteristicsSnapshot.cameraId)
    }

    @Test
    fun `an unknown logical camera id is preserved as null, never fabricated as the physical id`() {
        // Fix for a correctness gap: a prior revision defaulted a null logicalCameraId to the
        // requested physical id (`logicalCameraId ?: requestedPhysicalCameraId`), fabricating an
        // equality that was never observed. The correct behavior is to preserve the null.
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = null,
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2"),
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        val bound = assertIs<PhysicalCameraBindingResolution.Bound>(result)
        assertNull(bound.provenance.logicalCameraId)
        assertEquals("2", bound.provenance.physicalCameraId)
    }

    @Test
    fun `shape A - bound CameraInfo is itself physical - never carries a fabricated logical id either`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = null,
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2"),
                bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
            )

        val bound = assertIs<PhysicalCameraBindingResolution.Bound>(result)
        assertNull(bound.provenance.logicalCameraId)
        assertEquals(PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL, bound.provenance.bindingSource)
    }

    @Test
    fun `no matching physical CameraInfo found is unverified, never treated as Bound`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = false,
                physicalCharacteristicsSnapshot = null,
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        assertEquals(PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified, result)
    }

    @Test
    fun `physical CameraInfo found but characteristics unreadable is a mismatch, not Bound`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = null,
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        val mismatch = assertIs<PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch>(result)
        assertEquals("2", mismatch.expectedPhysicalCameraId)
        assertEquals(null, mismatch.actualCameraId)
    }

    @Test
    fun `snapshot cameraId differing from the requested candidate is rejected as a mismatch`() {
        // e.g. CameraManager returned characteristics for a different id than the one CameraX's own
        // physicalCameraInfos entry claimed to be - never trusted silently.
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "3"),
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        val mismatch = assertIs<PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch>(result)
        assertEquals("2", mismatch.expectedPhysicalCameraId)
        assertEquals("3", mismatch.actualCameraId)
    }

    @Test
    fun `a physical snapshot that itself reports isLogicalMultiCamera is never trusted`() {
        // A genuine physical sub-camera is never itself expected to declare LOGICAL_MULTI_CAMERA; if it
        // does, this codebase does not know how to interpret that and refuses rather than guesses.
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2", isLogicalMultiCamera = true),
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )

        val mismatch = assertIs<PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch>(result)
        assertEquals("2", mismatch.actualCameraId)
    }

    @Test
    fun `two independent bindings to different candidates never cross-contaminate provenance`() {
        // Task §11 dynamic-provenance-change coverage: proves no shared mutable state links two
        // separate resolution attempts even when both are for the same logical camera.
        val first =
            assertIs<PhysicalCameraBindingResolution.Bound>(
                verifyPhysicalCameraProvenance(
                    "0", "2", true, snapshotOf(cameraId = "2"),
                    PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                ),
            )
        val second =
            assertIs<PhysicalCameraBindingResolution.Bound>(
                verifyPhysicalCameraProvenance(
                    "0", "3", true, snapshotOf(cameraId = "3"),
                    PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                ),
            )

        assertEquals("2", first.provenance.physicalCameraId)
        assertEquals("3", second.provenance.physicalCameraId)
    }

    // --- selectPhysicalCameraInfoSource (fix for a correctness gap: supporting both CameraX shapes) ---

    @Test
    fun `shape A wins when the bound CameraInfo's own id already equals the requested candidate`() {
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = "2",
                requestedPhysicalCameraId = "2",
                declaredPhysicalCameraInfoIds = emptyList(),
            )

        assertEquals(PhysicalCameraInfoSelection.UseBoundCameraInfoDirectly, selection)
    }

    @Test
    fun `shape A is preferred over shape B when both would technically match`() {
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = "2",
                requestedPhysicalCameraId = "2",
                declaredPhysicalCameraInfoIds = listOf("2", "3", "4"),
            )

        assertEquals(PhysicalCameraInfoSelection.UseBoundCameraInfoDirectly, selection)
    }

    @Test
    fun `shape B is selected when the bound CameraInfo is logical and declares the requested candidate`() {
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = "0",
                requestedPhysicalCameraId = "2",
                declaredPhysicalCameraInfoIds = listOf("2", "3", "4"),
            )

        assertEquals(PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo, selection)
    }

    @Test
    fun `neither shape matches when the requested id is declared nowhere`() {
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = "0",
                requestedPhysicalCameraId = "9",
                declaredPhysicalCameraInfoIds = listOf("2", "3", "4"),
            )

        assertEquals(PhysicalCameraInfoSelection.NoMatch, selection)
    }

    @Test
    fun `an unresolvable bound CameraInfo id never accidentally matches shape A`() {
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = null,
                requestedPhysicalCameraId = "2",
                declaredPhysicalCameraInfoIds = listOf("2"),
            )

        assertEquals(PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo, selection)
    }

    @Test
    fun `selection never infers correctness from ID ordering`() {
        // The requested candidate is the *last* declared id, not the first - the selection must still
        // match it correctly, proving no positional/ordering assumption is baked in.
        val selection =
            selectPhysicalCameraInfoSource(
                boundCameraInfoCamera2Id = "0",
                requestedPhysicalCameraId = "4",
                declaredPhysicalCameraInfoIds = listOf("2", "3", "4"),
            )

        assertEquals(PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo, selection)
    }
}
