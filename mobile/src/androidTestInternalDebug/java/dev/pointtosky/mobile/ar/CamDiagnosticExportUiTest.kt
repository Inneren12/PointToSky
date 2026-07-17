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
import androidx.compose.ui.test.hasTestTag
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
import dev.pointtosky.mobile.ar.camera.CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG
import dev.pointtosky.mobile.ar.camera.CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG
import dev.pointtosky.mobile.ar.camera.CamDiagnosticLiveness
import dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportInput
import dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUiProvider
import dev.pointtosky.mobile.ar.camera.CameraCharacteristicsSnapshot
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsCoordinatorState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsDiagnosticState
import dev.pointtosky.mobile.ar.camera.CameraSessionIntrinsicsFrameCounters
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticJson
import dev.pointtosky.mobile.ar.camera.buildCamDiagnosticReportText
import dev.pointtosky.mobile.ar.camera.captureCamDiagnosticSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `internalDebug`-only Compose UI tests for [CamDiagnosticsExportUiProvider]/[CamDiagnosticFullReportDialog]
 * (architecture fix §1/§7) - the real export implementation, unreachable from shared `androidTest`
 * sources now that it lives in `mobile/src/internalDebug`. Reuses the real Pixel 9 logical-multi-camera
 * evidence fixture (`cameraId=0`, `logical=true`, `physicalIds=2,3,4`, `UnsupportedLogicalMultiCameraMapping`,
 * a `4080x3072` active array, and the identity sensor-to-buffer matrix actually observed on that device -
 * see `docs/validation/cam_2c_pixel9_evidence.md`; this is a real recorded value, not a synthetic scale).
 */
@RunWith(AndroidJUnit4::class)
class CamDiagnosticExportUiTest {
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

    private fun pixel9Input(observedFrameCount: Long = 1115L): CamDiagnosticsExportInput {
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
                        // The real matrix observed on this device was the identity matrix - not a scale
                        // (docs/validation/cam_2c_pixel9_evidence.md). AXIS_ALIGNED_0 structurally; not
                        // proven CONSISTENT semantically (see SensorToBufferDomainConsistency).
                        latestFrameTransform = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                        latestFrameTransformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                    ),
            )
        return CamDiagnosticsExportInput(
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
    fun compactSummarySurfacesTheCam2cRootCauseAndOpenDiagnosticsShowsTheFullReport() {
        composeTestRule.setContent {
            CamDiagnosticsExportUiProvider.Content(input = pixel9Input(), modifier = Modifier)
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
            .assert(hasText("CAM-2c: BLOCKED", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
            .assert(hasText("reason: logical multi-camera", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
            .assert(hasText("camera: 0", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
            .assert(hasText("physical: 2, 3, 4", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COMPACT_SUMMARY_TEST_TAG)
            .assert(hasText("published: PhysicalSensor", substring = true))

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("attempt: UnsupportedLogicalMultiCameraMapping", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("publication: Resolved", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("reference: PhysicalSensor", substring = true))
    }

    @Test
    fun freezeSnapshotCapturesCurrentValuesAndResumeLiveShowsSubsequentUpdates() {
        var observedFrameCount by mutableStateOf(10L)
        composeTestRule.setContent {
            val input = remember(observedFrameCount) { pixel9Input(observedFrameCount) }
            CamDiagnosticsExportUiProvider.Content(input = input, modifier = Modifier)
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("LIVE", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 10", substring = true))

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

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("LIVE", substring = true))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_TEXT_TEST_TAG)
            .assert(hasText("geometry observed frames: 20", substring = true))
    }

    @Test
    fun copyAllSendsTheCompleteDeterministicReportToTheRealClipboard() {
        val input = pixel9Input()
        composeTestRule.setContent {
            CamDiagnosticsExportUiProvider.Content(input = input, modifier = Modifier)
        }
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG).performClick()

        val clipboardManager =
            composeTestRule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clippedText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()

        assertTrue("expected the clipboard to carry the complete report", clippedText != null)
        assertTrue(clippedText!!.contains("POINTTOSKY CAM DIAGNOSTICS"))
        assertTrue(clippedText.contains("COUNTERS"))
        assertTrue(clippedText.contains("attempt: UnsupportedLogicalMultiCameraMapping"))
    }

    /** Records every dispatched Copy/Share call verbatim (share-wiring test fix) - injected in place of
     * [AndroidCamDiagnosticActions] so a payload-wiring regression (e.g. two buttons swapping their
     * payloads) fails a test instead of only being detectable by manually reading the real clipboard or
     * a real chooser Activity, neither of which distinguishes *which* button sent *which* payload. */
    private class RecordingCamDiagnosticActions : CamDiagnosticActions {
        data class Copy(val label: String, val text: String)

        data class Share(val subject: String, val text: String)

        val copies = mutableListOf<Copy>()
        val shares = mutableListOf<Share>()

        override fun copy(
            label: String,
            text: String,
        ) {
            copies += Copy(label, text)
        }

        override fun share(
            subject: String,
            text: String,
        ) {
            shares += Share(subject, text)
        }
    }

    @Test
    fun copyAllShareLogAndShareJsonDispatchTheExactExpectedLabelSubjectAndPayloadThroughTheInjectedActions() {
        val snapshot = captureCamDiagnosticSnapshot(input = pixel9Input(), capturedAtEpochMillis = 1_700_000_000_000L)
        val actions = RecordingCamDiagnosticActions()
        composeTestRule.setContent {
            CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {}, actions = actions)
        }

        val expectedReportText = buildCamDiagnosticReportText(snapshot, CamDiagnosticLiveness.LIVE)
        val expectedJsonText = buildCamDiagnosticJson(snapshot, CamDiagnosticLiveness.LIVE)

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG).performClick()

        assertEquals(1, actions.copies.size)
        assertEquals("PointToSky CAM diagnostics", actions.copies[0].label)
        assertEquals(expectedReportText, actions.copies[0].text)

        assertEquals(2, actions.shares.size)
        assertEquals("PointToSky CAM diagnostics", actions.shares[0].subject)
        assertEquals(expectedReportText, actions.shares[0].text)
        assertEquals("PointToSky CAM diagnostics (JSON)", actions.shares[1].subject)
        assertEquals(expectedJsonText, actions.shares[1].text)
    }

    @Test
    fun frozenSnapshotShareLogAndShareJsonPayloadsDescribeTheFrozenSnapshotNotNewerLiveInput() {
        var observedFrameCount by mutableStateOf(10L)
        val actions = RecordingCamDiagnosticActions()
        composeTestRule.setContent {
            val snapshot =
                remember(observedFrameCount) {
                    captureCamDiagnosticSnapshot(
                        input = pixel9Input(observedFrameCount),
                        capturedAtEpochMillis = 1_700_000_000_000L,
                    )
                }
            CamDiagnosticFullReportDialog(liveSnapshot = snapshot, onDismissRequest = {}, actions = actions)
        }

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG).performClick()

        composeTestRule.runOnUiThread { observedFrameCount = 99L }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG).performClick()

        val frozenSnapshot =
            captureCamDiagnosticSnapshot(input = pixel9Input(10L), capturedAtEpochMillis = 1_700_000_000_000L)
        val expectedFrozenReportText = buildCamDiagnosticReportText(frozenSnapshot, CamDiagnosticLiveness.FROZEN)
        val expectedFrozenJsonText = buildCamDiagnosticJson(frozenSnapshot, CamDiagnosticLiveness.FROZEN)

        assertEquals(1, actions.shares.count { it.subject == "PointToSky CAM diagnostics" })
        val shareLogText = actions.shares.first { it.subject == "PointToSky CAM diagnostics" }.text
        assertEquals(expectedFrozenReportText, shareLogText)
        assertTrue(shareLogText.contains("geometry observed frames: 10"))
        assertTrue(!shareLogText.contains("geometry observed frames: 99"))

        val shareJsonText = actions.shares.first { it.subject == "PointToSky CAM diagnostics (JSON)" }.text
        assertEquals(expectedFrozenJsonText, shareJsonText)
        assertTrue(!shareJsonText.contains("\"observedFrameCount\":99"))
    }

    @Test
    fun theFullReportScrollsAllTheWayToItsOwnEndMarker() {
        composeTestRule.setContent {
            CamDiagnosticsExportUiProvider.Content(input = pixel9Input(), modifier = Modifier)
        }
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()

        // Proof the scrollable container reaches its actual end - not merely that the giant report Text
        // itself exists (test-hardening fix §4).
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_SCROLL_TEST_TAG)
            .performScrollToNode(hasTestTag(CAM_DIAGNOSTIC_REPORT_END_TEST_TAG))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_REPORT_END_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun everyActionIsIndividuallyReachableAndClickableAtLargeFontScale() {
        composeTestRule.setContent {
            val inflatedDensity = Density(density = LocalDensity.current.density, fontScale = 2.5f)
            CompositionLocalProvider(LocalDensity provides inflatedDensity) {
                CamDiagnosticsExportUiProvider.Content(input = pixel9Input(), modifier = Modifier)
            }
        }
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()

        // test-hardening fix §4: scroll the horizontal action row to bring each action into view - not
        // merely assert it exists in the semantics tree - proving it is actually reachable and clickable
        // at 2.5x font scale, not just present.
        for (tag in
            listOf(
                CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_COPY_ALL_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_SHARE_LOG_BUTTON_TEST_TAG,
                CAM_DIAGNOSTIC_SHARE_JSON_BUTTON_TEST_TAG,
            )
        ) {
            composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_ACTIONS_ROW_TEST_TAG).performScrollToNode(hasTestTag(tag))
            composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
            composeTestRule.onNodeWithTag(tag).assert(androidx.compose.ui.test.hasClickAction())
        }
        // "Close" is not inside the scrollable action row - a fixed corner button - assert directly.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG).assert(androidx.compose.ui.test.hasClickAction())

        // Every action, once scrolled into view, is genuinely clickable - not just visible.
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_ACTIONS_ROW_TEST_TAG)
            .performScrollToNode(hasTestTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FREEZE_RESUME_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_LIVENESS_BANNER_TEST_TAG).assert(hasText("FROZEN", substring = true))
    }

    /** Mirrors `ArScreen`'s real composition shape: the reticle and the CAM diagnostics export UI are
     * independent siblings under one root [Box]. */
    @Composable
    private fun ReticleAndExportUiHost(input: CamDiagnosticsExportInput) {
        Box(Modifier.size(400.dp, 850.dp)) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(24.dp)
                        .testTag(AR_RETICLE_TEST_TAG),
            )
            CamDiagnosticsExportUiProvider.Content(input = input, modifier = Modifier)
        }
    }

    @Test
    fun reticleIsNotPermanentlyObscuredAfterTheFullReportIsClosed() {
        composeTestRule.setContent {
            ReticleAndExportUiHost(input = pixel9Input())
        }

        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_OPEN_BUTTON_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG).assertExists()

        composeTestRule.onNodeWithTag(CAM_DIAGNOSTIC_CLOSE_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(CAM_DIAGNOSTIC_FULL_REPORT_DIALOG_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(AR_RETICLE_TEST_TAG).assertIsDisplayed()
    }
}
