package dev.pointtosky.mobile.ar

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.mobile.ar.camera.AnalysisBufferIntrinsicsResolution
import dev.pointtosky.mobile.ar.camera.CamDiagnosticLiveness
import dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot
import dev.pointtosky.mobile.ar.camera.CameraCharacteristicsSnapshot
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsCoordinatorState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsFrameCounters
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText
import dev.pointtosky.mobile.ar.camera.captureCamDiagnosticSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [CamDiagnosticFullReportDialog] (CAM diagnostic export/freeze fix §7) - Freeze
 * captures the current values, Resume returns to live updates, "Copy all" sends the exact deterministic
 * report to the real Android [ClipboardManager], the report body is scrollable, and actions stay
 * reachable at a large font scale. Reuses the real Pixel 9 logical-multi-camera evidence fixture
 * (`cameraId=0`, `logical=true`, `physicalIds=2,3,4`, `UnsupportedLogicalMultiCameraMapping`).
 */
@RunWith(AndroidJUnit4::class)
class CamDiagnosticFullReportDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val physicalSensorIntrinsics =
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

    private fun pixel9Snapshot(observedFrameCount: Long): CamDiagnosticSnapshot {
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
                        framesAnalyzed = 1115L,
                        framesWithTransform = 1115L,
                        framesWithNullTransform = 0L,
                        framesWithUsableTransform = 1115L,
                        coordinatorFramesWaited = 1,
                        latestFrameTransform = SensorToBufferMatrix3(0.15686, 0.0, 0.0, 0.0, 0.15686, 0.0, 0.0, 0.0, 1.0),
                        latestFrameTransformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                    ),
            )
        return captureCamDiagnosticSnapshot(
            capturedAtEpochMillis = 1_700_000_000_000L,
            sessionId = 9L,
            cam2bState = null,
            cameraGeometryState = null,
            cameraGeometryStatusTransitionCount = 0,
            cameraGeometryObservedFrameCount = observedFrameCount,
            cameraGeometryReadyBundleCount = 0L,
            cameraIntrinsicsState = intrinsicsState,
            calibrationDiagnostics = null,
        )
    }

    @Test
    fun freezeSnapshotCapturesCurrentValuesAndResumeLiveShowsSubsequentUpdates() {
        var observedFrameCount by mutableStateOf(10L)
        composeTestRule.setContent {
            val snapshot = remember(observedFrameCount) { pixel9Snapshot(observedFrameCount) }
            CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {})
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("LIVE", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 10", substring = true))

        // Freeze: the displayed report must stop tracking observedFrameCount from this point on.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("FROZEN", substring = true))

        composeTestRule.runOnUiThread { observedFrameCount = 20L }
        composeTestRule.waitForIdle()
        // Repeated recompositions (camera/projection "continuing to run normally" underneath) must not
        // replace the frozen snapshot.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 10", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(!hasText("geometry observed frames: 20", substring = true))

        // Resume live: counters immediately reflect every update since freezing, not just the next one.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("LIVE", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 20", substring = true))

        composeTestRule.runOnUiThread { observedFrameCount = 30L }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 30", substring = true))
    }

    @Test
    fun copyAllSendsTheCompleteDeterministicReportToTheRealClipboard() {
        val snapshot = pixel9Snapshot(observedFrameCount = 1115L)
        composeTestRule.setContent {
            CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {})
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG).performClick()

        val expected = buildCamDiagnosticReportText(snapshot, CamDiagnosticLiveness.LIVE)
        val clipboardManager =
            composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clippedText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()

        assertTrue("expected the clipboard to carry the exact report text", clippedText == expected)
        // Never a scraped/truncated subset - the full multi-section report, every header present.
        assertTrue(clippedText!!.contains("POINTTOSKY CAM DIAGNOSTICS"))
        assertTrue(clippedText.contains("COUNTERS"))
    }

    @Test
    fun theFullReportBodyIsScrollable() {
        val snapshot = pixel9Snapshot(observedFrameCount = 1115L)
        composeTestRule.setContent {
            CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {})
        }

        // A scrollable ancestor exists and can bring the trailing content into view without needing a
        // second screenshot/scroll-and-recapture cycle.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_SCROLL_TEST_TAG)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun actionsRemainReachableAtLargeFontScale() {
        val snapshot = pixel9Snapshot(observedFrameCount = 1115L)
        composeTestRule.setContent {
            val inflatedDensity = Density(density = LocalDensity.current.density, fontScale = 2.5f)
            CompositionLocalProvider(LocalDensity provides inflatedDensity) {
                CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {})
            }
        }

        for (tag in
            listOf(
                CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG,
            )
        ) {
            composeTestRule.onNodeWithTag(tag).assertExists()
        }
    }

    /** Mirrors `ArScreen`'s real composition shape: the reticle and the CAM diagnostics HUD/dialog are
     * independent siblings under one root [Box], exactly as in [CamDiagnosticTopPanels]'s own KDoc. */
    @Composable
    private fun ReticleAndFullReportHost(openDiagnostics: Boolean, onOpenDiagnosticsChange: (Boolean) -> Unit) {
        Box(Modifier.size(400.dp, 850.dp)) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                        .testTag(AR_RETICLE_TEST_TAG),
            )
            if (openDiagnostics) {
                CamDiagnosticFullReportDialog(
                    liveSnapshot = pixel9Snapshot(observedFrameCount = 1115L),
                    onDismissRequest = { onOpenDiagnosticsChange(false) },
                )
            }
        }
    }

    @Test
    fun reticleIsNotPermanentlyObscuredAfterTheFullReportIsClosed() {
        var openDiagnostics by mutableStateOf(true)
        composeTestRule.setContent {
            ReticleAndFullReportHost(openDiagnostics = openDiagnostics, onOpenDiagnosticsChange = { openDiagnostics = it })
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG).assertExists()

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
    }
}
