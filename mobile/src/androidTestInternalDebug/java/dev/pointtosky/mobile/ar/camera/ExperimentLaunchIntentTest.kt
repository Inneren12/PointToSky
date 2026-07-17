package dev.pointtosky.mobile.ar.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-`Android`-runtime replacement for the prior, insufficient `PhysicalCameraBindingExperimentActivityLaunchTest`
 * (a plain JVM test comparing a reflected class name against a second, independently hand-written string
 * literal - which proves the two strings match, never that the in-app action actually launches the
 * registered `Activity`). This test runs with a real `Context`/`PackageManager`
 * ([InstrumentationRegistry]), so it can assert what a real launch actually resolves to:
 *  - [buildPhysicalCameraBindingExperimentIntent] produces an explicit `Intent` whose
 *    `component.className` is exactly [PhysicalCameraBindingExperimentActivity]'s real class name.
 *  - `PackageManager.resolveActivity` (queried directly by component, the same lookup
 *    `startActivity` itself performs) finds that `Activity` registered in this `internalDebug` build.
 *  - `PackageManager.getActivityInfo` reports `exported == false`, matching
 *    `mobile/src/internalDebug/AndroidManifest.xml`'s own declared `android:exported="false"` - this
 *    experiment must never become reachable from another app/process.
 *
 * `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` (`mobile/src/androidTestInternalDebug/.../ar/`)
 * covers the remaining half: that tapping "Open physical-camera experiment" actually calls this exact
 * `Intent`-builder, via an injected launch action.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentLaunchIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBuiltIntentIsAnExplicitIntentTargetingTheExperimentActivitysRealClassName() {
        val intent = buildPhysicalCameraBindingExperimentIntent(context)

        val component = intent.component
        assertNotNull("buildPhysicalCameraBindingExperimentIntent must produce an explicit Intent", component)
        assertEquals(context.packageName, component!!.packageName)
        assertEquals(PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME, component.className)
    }

    @Suppress("DEPRECATION")
    @Test
    fun theExperimentActivityIsRegisteredAndResolvableByThePackageManagerInThisInternalDebugBuild() {
        val intent = buildPhysicalCameraBindingExperimentIntent(context)

        val resolvedActivity = context.packageManager.resolveActivity(intent, 0)
        assertNotNull(
            "PackageManager must resolve the exact component buildPhysicalCameraBindingExperimentIntent " +
                "targets - the same lookup a real startActivity(intent) performs - in this internalDebug build",
            resolvedActivity,
        )
        assertEquals(
            PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME,
            resolvedActivity!!.activityInfo.name,
        )
    }

    @Test
    fun theExperimentActivityStaysNonExportedNeverReachableFromAnotherApp() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                android.content.ComponentName(context.packageName, PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME),
                0,
            )

        assertFalse(
            "PhysicalCameraBindingExperimentActivity must stay exported=false - only the in-app launch " +
                "path (buildPhysicalCameraBindingExperimentIntent, same-app/same-process) may reach it",
            activityInfo.exported,
        )
    }
}
