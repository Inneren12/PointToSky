package dev.pointtosky.mobile.ar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.mobile.ar.camera.AnalysisBufferIntrinsicsResolution
import dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportInput
import dev.pointtosky.mobile.ar.camera.CameraCharacteristicsSnapshot
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsCoordinatorState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsFrameCounters
import dev.pointtosky.mobile.ar.camera.captureCamDiagnosticSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real "Open physical-camera experiment" button in [CamDiagnosticFullReportDialog] actually
 * emits a launch request when tapped (fix for a launch-path testability gap - task §3). This is the
 * missing counterpart to the prior, insufficient class-name-string comparison test: that test could not
 * prove the in-app *action* launches anything at all. Here, `onOpenPhysicalCameraExperiment` - the same
 * injection seam `actions: CamDiagnosticActions?` already uses for Copy/Share - is replaced with a
 * recording lambda, so a click is asserted to invoke it exactly once, directly, rather than only inferred
 * from a reflected class name.
 *
 * `ExperimentLaunchIntentTest` (`mobile/src/androidTestInternalDebug/.../ar/camera/`) covers the other
 * half: that the *real*, non-injected default (`buildPhysicalCameraBindingExperimentIntent`) is an
 * explicit `Intent` the `PackageManager` actually resolves to the registered, `exported="false"`
 * `Activity` in this `internalDebug` build.
 */
@RunWith(AndroidJUnit4::class)
class CamDiagnosticPhysicalCameraExperimentLaunchUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // Mirrors CamDiagnosticExportUiTest's own real Pixel 9 evidence fixture (a real recorded value, not
    // a synthetic one - see docs/validation/cam_2c_pixel9_evidence.md) - this test's own subject is the
    // launch button, not the report content, so the exact diagnostic values otherwise don't matter here.
    private fun minimalInput(): CamDiagnosticsExportInput {
        val physicalSensorIntrinsics =
            CameraIntrinsics(
                horizontalFovDeg = 70.7,
                verticalFovDeg = 56.2,
                focalLengthMm = 6.81,
                sensorWidthMm = 9.80,
                sensorHeightMm = 7.35,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.PhysicalSensor,
            )
        val characteristics =
            CameraCharacteristicsSnapshot(
                availableFocalLengthsMm = floatArrayOf(6.81f),
                sensorPhysicalWidthMm = 9.80f,
                sensorPhysicalHeightMm = 7.35f,
                activeArrayLeftPx = 0,
                activeArrayTopPx = 0,
                activeArrayRightPx = 4080,
                activeArrayBottomPx = 3072,
                pixelArrayWidthPx = 4080,
                pixelArrayHeightPx = 3072,
                isLogicalMultiCamera = true,
                cameraId = "0",
                physicalCameraIds = setOf("2", "3", "4"),
            )
        val intrinsicsState =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt =
                    AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                        cameraId = "0",
                        physicalCameraIdsForDiagnostics = setOf("2", "3", "4"),
                    ),
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = characteristics,
                frameCounters =
                    CameraSessionIntrinsicsFrameCounters(
                        framesAnalyzed = 1L,
                        framesWithTransform = 1L,
                        framesWithNullTransform = 0L,
                        framesWithUsableTransform = 1L,
                        coordinatorFramesWaited = 1,
                        latestFrameTransform = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                        latestFrameTransformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                    ),
            )
        return CamDiagnosticsExportInput(
            sessionId = 1L,
            cam2bState = null,
            cameraGeometryState = null,
            cameraGeometryStatusTransitionCount = 0,
            cameraGeometryObservedFrameCount = 0L,
            cameraGeometryReadyBundleCount = 0L,
            cameraIntrinsicsState = intrinsicsState,
            calibrationDiagnostics = null,
        )
    }

    @Test
    fun tappingOpenPhysicalCameraExperimentInvokesTheInjectedLaunchActionExactlyOnce() {
        val snapshot = captureCamDiagnosticSnapshot(input = minimalInput(), capturedAtEpochMillis = 1_700_000_000_000L)
        var launchCount = 0

        composeTestRule.setContent {
            CamDiagnosticFullReportDialog(
                liveSnapshot = snapshot,
                onDismissRequest = {},
                onOpenPhysicalCameraExperiment = { launchCount += 1 },
            )
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_PHYSICAL_CAMERA_EXPERIMENT_BUTTON_TEST_TAG).performClick()

        assertEquals(1, launchCount)
    }
}
