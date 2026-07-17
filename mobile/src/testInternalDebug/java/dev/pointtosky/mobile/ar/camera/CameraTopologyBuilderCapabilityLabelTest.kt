package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pure JVM tests for [capabilityLabel] (CAM-2c physical-camera provenance experiment fix — P1
 * correctness blocker). A prior revision mapped raw capability code `29` to `LOGICAL_MULTI_CAMERA`;
 * the real Android constant `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`
 * is `11` (confirmed by `javap` inspection of this project's pinned `android-35` `android.jar`; see
 * [capabilityLabel]'s own KDoc). These tests exercise the real constant directly — never a re-guessed
 * literal — via the injectable `apiLevel` parameter, since this project has no Robolectric dependency
 * to fake `Build.VERSION.SDK_INT` at the unit-test level.
 */
class CameraTopologyBuilderCapabilityLabelTest {
    @Test
    fun `the real LOGICAL_MULTI_CAMERA capability constant maps correctly on API 28+`() {
        val label =
            capabilityLabel(
                capability = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                apiLevel = Build.VERSION_CODES.P,
            )

        assertEquals(CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL, label)
    }

    @Test
    fun `the real LOGICAL_MULTI_CAMERA capability constant is not the previously hardcoded magic 29`() {
        assertNotEquals(29, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        assertEquals(11, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
    }

    @Test
    fun `below API 28, the logical-multi-camera capability code is never labeled as logical`() {
        val label =
            capabilityLabel(
                capability = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
                apiLevel = Build.VERSION_CODES.O,
            )

        assertNotEquals(CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL, label)
    }

    @Test
    fun `unrelated capabilities never map to LOGICAL_MULTI_CAMERA`() {
        val unrelated =
            listOf(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO,
                29, // the old, wrong value must map to a generic label, never LOGICAL_MULTI_CAMERA
            )

        for (capability in unrelated) {
            assertNotEquals(
                CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL,
                capabilityLabel(capability, apiLevel = Build.VERSION_CODES.P),
                "capability=$capability must not map to LOGICAL_MULTI_CAMERA",
            )
        }
    }

    @Test
    fun `a Pixel-9-like capability array is reported as logical via capabilityLabel`() {
        // The Pixel 9's own rear logical camera reports BACKWARD_COMPATIBLE and LOGICAL_MULTI_CAMERA
        // among REQUEST_AVAILABLE_CAPABILITIES (docs/validation/cam_2c_pixel9_evidence.md: physicalIds=2,3,4).
        val pixel9LikeCapabilities =
            intArrayOf(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW,
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            )

        val labels = pixel9LikeCapabilities.map { capabilityLabel(it, apiLevel = Build.VERSION_CODES.P) }

        assertEquals(true, labels.contains(CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL))
    }
}
