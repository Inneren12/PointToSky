package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CAM-2c physical-camera provenance experiment fix (reachability defect): a prior revision documented
 * launching [PhysicalCameraBindingExperimentActivity] via `adb shell am start -n ...` while the
 * manifest declares it `android:exported="false"` — a launch mechanism this codebase cannot stand
 * behind for a non-exported component. The actual, verified entry point is now an in-app
 * `context.startActivity(buildPhysicalCameraBindingExperimentIntent(context))` call from
 * `CamDiagnosticFullReportDialog`'s "Open physical-camera experiment" action.
 *
 * This test cannot exercise the real `Intent`/`Activity`/`PackageManager` behavior without an Android
 * runtime (this project has no Robolectric dependency), so it is limited to the one thing a plain JVM
 * test *can* prove: the class name [PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME] reflects must
 * exactly match the manifest's own declared `android:name` for that `<activity>` entry
 * (`mobile/src/internalDebug/AndroidManifest.xml`). By itself this does **not** prove the in-app action
 * actually launches the registered `Activity` (fix for a launch-path testability gap) —
 * `ExperimentLaunchIntentTest` (`mobile/src/androidTestInternalDebug/.../ar/camera/`) covers that with a
 * real `Context`/`PackageManager`: the built `Intent`'s `component.className`, that `PackageManager`
 * actually resolves it in this `internalDebug` build, and that it stays `exported=false`.
 * `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` (`.../ar/`) covers the remaining half: that
 * tapping the real button actually invokes the launch action.
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
