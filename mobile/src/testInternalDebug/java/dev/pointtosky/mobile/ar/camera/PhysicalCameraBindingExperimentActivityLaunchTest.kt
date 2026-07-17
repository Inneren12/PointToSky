package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CAM-2c physical-camera provenance experiment fix (reachability defect): a prior revision documented
 * launching [PhysicalCameraBindingExperimentActivity] via `adb shell am start -n ...` while the
 * manifest declares it `android:exported="false"` — a launch mechanism this codebase cannot stand
 * behind for a non-exported component. The actual, verified entry point is now an in-app
 * `context.startActivity(Intent(context, PhysicalCameraBindingExperimentActivity::class.java))` call
 * from `CamDiagnosticFullReportDialog`'s "Open physical-camera experiment" action.
 *
 * This test cannot exercise the real `Intent`/`Activity` launch without an Android runtime (this
 * project has no Robolectric dependency), so it isolates and unit-tests the one thing that *can* drift
 * silently and break that launch: the class name the `Intent` (implicitly, via
 * `PhysicalCameraBindingExperimentActivity::class.java`) resolves to must exactly match the manifest's
 * own declared `android:name` for that `<activity>` entry
 * (`mobile/src/internalDebug/AndroidManifest.xml`) — a class rename without updating the manifest
 * would compile successfully but fail to launch at runtime with an `ActivityNotFoundException`.
 */
class PhysicalCameraBindingExperimentActivityLaunchTest {
    @Test
    fun `the experiment activity's reflected class name matches the manifest's declared android_name`() {
        assertEquals(
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentActivity",
            PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME,
        )
    }
}
