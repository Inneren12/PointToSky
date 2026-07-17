package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure JVM tests for [verifyPhysicalCameraProvenance] (CAM-2c physical-camera provenance experiment,
 * task §5/§6/§11). No CameraX/Android types involved - this is the exact logic that decides whether a
 * physical-camera binding is trustworthy enough for CAM-2c to use.
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
    fun `matching cameraId and non-logical snapshot verifies as Bound`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2"),
            )

        val bound = assertIs<PhysicalCameraBindingResolution.Bound>(result)
        assertEquals("0", bound.provenance.logicalCameraId)
        assertEquals("2", bound.provenance.physicalCameraId)
        assertEquals(PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID, bound.provenance.bindingMethod)
        assertEquals(PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY, bound.provenance.confidence)
        assertEquals("2", bound.physicalCharacteristicsSnapshot.cameraId)
    }

    @Test
    fun `unknown logical camera id still verifies, falling back to the physical id`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = null,
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshotOf(cameraId = "2"),
            )

        val bound = assertIs<PhysicalCameraBindingResolution.Bound>(result)
        assertEquals("2", bound.provenance.logicalCameraId)
    }

    @Test
    fun `no matching physical CameraInfo found is unverified, never treated as Bound`() {
        val result =
            verifyPhysicalCameraProvenance(
                logicalCameraId = "0",
                requestedPhysicalCameraId = "2",
                physicalCameraInfoFound = false,
                physicalCharacteristicsSnapshot = null,
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
                verifyPhysicalCameraProvenance("0", "2", true, snapshotOf(cameraId = "2")),
            )
        val second =
            assertIs<PhysicalCameraBindingResolution.Bound>(
                verifyPhysicalCameraProvenance("0", "3", true, snapshotOf(cameraId = "3")),
            )

        assertEquals("2", first.provenance.physicalCameraId)
        assertEquals("3", second.provenance.physicalCameraId)
    }
}
