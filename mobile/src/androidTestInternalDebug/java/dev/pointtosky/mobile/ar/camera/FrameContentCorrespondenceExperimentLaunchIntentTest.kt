package dev.pointtosky.mobile.ar.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Mirrors [ExperimentLaunchIntentTest] exactly for the CAM-2c frame-content correspondence
 * experiment's own launch path — real `Context`/`PackageManager` via [InstrumentationRegistry], never
 * merely a reflected-class-name comparison. Compiled against real Android/`PackageManager` APIs by
 * `:mobile:compileInternalDebugAndroidTestKotlin`; not yet executed on a device or emulator in this
 * environment (see `docs/validation/cam_2c_pixel9_evidence.md`). */
@RunWith(AndroidJUnit4::class)
class FrameContentCorrespondenceExperimentLaunchIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBuiltIntentIsAnExplicitIntentTargetingTheExperimentActivitysRealClassName() {
        val intent = buildFrameContentCorrespondenceExperimentIntent(context)

        val component = intent.component
        assertNotNull("buildFrameContentCorrespondenceExperimentIntent must produce an explicit Intent", component)
        assertEquals(context.packageName, component!!.packageName)
        assertEquals(FRAME_CONTENT_EXPERIMENT_ACTIVITY_CLASS_NAME, component.className)
    }

    @Suppress("DEPRECATION")
    @Test
    fun theExperimentActivityIsRegisteredAndResolvableByThePackageManagerInThisInternalDebugBuild() {
        val intent = buildFrameContentCorrespondenceExperimentIntent(context)

        val resolvedActivity = context.packageManager.resolveActivity(intent, 0)
        assertNotNull(
            "PackageManager must resolve the exact component buildFrameContentCorrespondenceExperimentIntent " +
                "targets - the same lookup a real startActivity(intent) performs - in this internalDebug build",
            resolvedActivity,
        )
        assertEquals(
            FRAME_CONTENT_EXPERIMENT_ACTIVITY_CLASS_NAME,
            resolvedActivity!!.activityInfo.name,
        )
    }

    @Test
    fun theExperimentActivityStaysNonExportedNeverReachableFromAnotherApp() {
        val activityInfo =
            context.packageManager.getActivityInfo(
                android.content.ComponentName(context.packageName, FRAME_CONTENT_EXPERIMENT_ACTIVITY_CLASS_NAME),
                0,
            )

        assertFalse(
            "FrameContentCorrespondenceExperimentActivity must stay exported=false - only the in-app launch " +
                "path (buildFrameContentCorrespondenceExperimentIntent, same-app/same-process) may reach it",
            activityInfo.exported,
        )
    }
}
