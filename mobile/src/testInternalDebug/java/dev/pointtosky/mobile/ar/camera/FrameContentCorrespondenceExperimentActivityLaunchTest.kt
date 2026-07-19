package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors [PhysicalCameraBindingExperimentActivityLaunchTest]: proves the reflected `Activity` class
 * name matches `mobile/src/internalDebug/AndroidManifest.xml`'s hand-typed `android:name` string. */
class FrameContentCorrespondenceExperimentActivityLaunchTest {
    @Test
    fun `activity class name matches the manifest declaration`() {
        assertEquals(
            "dev.pointtosky.mobile.ar.camera.FrameContentCorrespondenceExperimentActivity",
            FRAME_CONTENT_EXPERIMENT_ACTIVITY_CLASS_NAME,
        )
    }
}
