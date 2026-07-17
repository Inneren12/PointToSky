package dev.pointtosky.mobile.ar.camera

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.mobile.ar.rememberStableCallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented regression test for the stale-callback defect: [dev.pointtosky.mobile.ar.CameraPreview]'s
 * own `ImageAnalysis.Analyzer` is installed exactly once, inside a `DisposableEffect(Unit)` that never
 * re-runs on its own - a caller (the CAM-2c experiment's own `PhysicalCameraBindingSession`) constructs a
 * *brand-new* `onFrameMetadata` lambda instance on every recomposition (every time its own `state`
 * parameter changes), yet the already-installed analyzer must still end up invoking whichever lambda is
 * current at the moment a frame actually arrives - never the one instance that happened to exist when
 * the effect first ran. [dev.pointtosky.mobile.ar.rememberStableCallback] is the fix; this test proves it
 * end-to-end, at the Compose level, without needing a real camera - a real `ImageAnalysis.Analyzer`
 * cannot be exercised without physical camera hardware this container does not have (see
 * `docs/validation/cam_2c_pixel9_evidence.md`), but the composition/recomposition/effect-lifetime
 * behavior this bug lives in is exactly what Compose UI testing *can* exercise for real, on-device.
 *
 * [SimulatedPhysicalCameraBindingSession] below deliberately mirrors two real production shapes exactly
 * (not a simplified stand-in that would risk testing something else):
 *  - [dev.pointtosky.mobile.ar.CameraPreview]'s own pattern: a `DisposableEffect(Unit)` that installs a
 *    [dev.pointtosky.mobile.ar.rememberStableCallback]-wrapped reference into a long-lived object exactly
 *    once.
 *  - `PhysicalCameraBindingSession`'s own pattern: `onFrameMetadata = { frame -> ... }` is a plain lambda
 *    literal recreated fresh on every recomposition (never itself `remember`ed by the caller) that reads
 *    the caller's live `state` via [onUpdateSession] at *invocation* time, not at lambda-creation time.
 */
@RunWith(AndroidJUnit4::class)
class ExperimentCallbackFreshnessTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = 640.0 / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 480.0 / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun frame() =
        CameraFrameMetadata(
            timestampNanos = 1L,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            rotationDegrees = 0,
            sensorToBufferTransform = fullFrameTransform,
        )

    private fun boundResolution() =
        PhysicalCameraBindingResolution.Bound(
            provenance =
                PhysicalCameraProvenance(
                    logicalCameraId = "0",
                    physicalCameraId = "2",
                    bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                    bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                    confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                ),
            physicalCharacteristicsSnapshot =
                CameraCharacteristicsSnapshot(
                    availableFocalLengthsMm = floatArrayOf(3.6f),
                    sensorPhysicalWidthMm = 6.4f,
                    sensorPhysicalHeightMm = 4.8f,
                    activeArrayLeftPx = 0,
                    activeArrayTopPx = 0,
                    activeArrayRightPx = 4032,
                    activeArrayBottomPx = 3024,
                    pixelArrayWidthPx = 4032,
                    pixelArrayHeightPx = 3024,
                    isLogicalMultiCamera = false,
                    cameraId = "2",
                ),
        )

    /** Stands in for [dev.pointtosky.mobile.ar.CameraPreview]'s own long-lived `ImageAnalysis.Analyzer` -
     * a plain object a `DisposableEffect(Unit)` installs a callback reference into exactly once, and
     * which this test later invokes directly, exactly as a real analyzer thread would invoke the real
     * analyzer's callback. */
    private class SimulatedAnalyzer {
        var installedCallback: ((CameraFrameMetadata) -> Unit)? = null
    }

    /** Mirrors `PhysicalCameraBindingSession` + [dev.pointtosky.mobile.ar.CameraPreview] exactly enough
     * to reproduce the bug this test guards against: [onFrameMetadata] is a fresh lambda instance every
     * recomposition (constructed by the test's own `setContent` block below, exactly like
     * `PhysicalCameraBindingSession` constructs its own `onFrameMetadata` fresh every time `state`
     * changes), wrapped through [rememberStableCallback] and installed into [analyzer] inside a
     * `DisposableEffect(Unit)` that runs exactly once - never on every recomposition. */
    @Composable
    private fun SimulatedCameraFrameSource(
        analyzer: SimulatedAnalyzer,
        onFrameMetadata: (CameraFrameMetadata) -> Unit,
    ) {
        val currentOnFrameMetadata = rememberStableCallback(onFrameMetadata)
        DisposableEffect(Unit) {
            analyzer.installedCallback = currentOnFrameMetadata
            onDispose { analyzer.installedCallback = null }
        }
    }

    @Test
    fun aFrameCallbackInstalledWhileBindingStillAdvancesTheSessionAfterTransitioningToBound() {
        val analyzer = SimulatedAnalyzer()
        var session by mutableStateOf(initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2"))

        composeTestRule.setContent {
            // Exactly PhysicalCameraBindingSession's own onFrameMetadata shape: a fresh lambda every
            // recomposition of this @Composable (this whole setContent block re-runs whenever `session`
            // changes), closing over the live `session` var - never a value snapshotted at some earlier
            // point in time.
            SimulatedCameraFrameSource(
                analyzer = analyzer,
                onFrameMetadata = { frame -> session = session.reduceFrame(1L, frame) },
            )
        }
        composeTestRule.waitForIdle()

        // The callback was installed while `session` was still Binding (no bindingResolution yet) -
        // captured exactly once, never reassigned by any later recomposition.
        val callbackCreatedWhileBinding = analyzer.installedCallback
        assertNotNull("DisposableEffect(Unit) must install a callback on first composition", callbackCreatedWhileBinding)

        var text = buildPhysicalCameraExperimentReportText(session)
        assertTrue(text, text.contains("latestFrame=none yet"))
        assertTrue(text, text.contains("cam2cResult=awaiting frame"))

        // Transition Binding -> Bound, without recreating the simulated analyzer. This recomposes
        // SimulatedCameraFrameSource (its own `onFrameMetadata` parameter is a brand-new lambda
        // instance now), but its DisposableEffect(Unit) does not re-run merely because that lambda
        // identity changed - exactly like the real CameraPreview.
        composeTestRule.runOnUiThread {
            session = session.reduceCameraInfoResolved(1L, boundResolution())
        }
        composeTestRule.waitForIdle()

        assertSame(
            "the installed callback reference itself must never be recreated merely because the " +
                "onFrameMetadata lambda instance passed in changed",
            callbackCreatedWhileBinding,
            analyzer.installedCallback,
        )

        text = buildPhysicalCameraExperimentReportText(session)
        assertTrue(text, text.contains("status=BOUND"))
        assertTrue(text, text.contains("latestFrame=none yet"))
        assertTrue(text, text.contains("cam2cResult=awaiting frame"))

        // Invoke the ORIGINAL callback reference - created while `session` was still Binding, never
        // recreated since - and prove it advances the CURRENT (Bound) session, not a stale Binding-era
        // value captured at composition time.
        composeTestRule.runOnUiThread {
            analyzer.installedCallback!!.invoke(frame())
        }
        composeTestRule.waitForIdle()

        text = buildPhysicalCameraExperimentReportText(session)
        assertTrue(text, text.contains("latestFrame=640x480"))
        assertTrue(text, text.contains("cam2cResult=DOMAIN_NOT_PROVEN"))
        assertFalse(text, text.contains("awaiting frame"))
    }

    @Test
    fun aFrameArrivingBeforeBindingIsNotDiscardedOnceBindingResolvesLater() {
        // Task: "handle either callback order (frames before onCameraInfo, or vice versa)" - the test
        // above covers binding-then-frame; this covers the reverse order, frame-then-binding, through
        // the same never-recreated callback reference.
        val analyzer = SimulatedAnalyzer()
        var session by mutableStateOf(initialExperimentSessionState(attemptId = 5L, physicalCameraId = "2"))

        composeTestRule.setContent {
            SimulatedCameraFrameSource(
                analyzer = analyzer,
                onFrameMetadata = { frame -> session = session.reduceFrame(5L, frame) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            analyzer.installedCallback!!.invoke(frame())
        }
        composeTestRule.waitForIdle()

        var text = buildPhysicalCameraExperimentReportText(session)
        assertTrue(text, text.contains("latestFrame=640x480"))
        assertTrue(text, text.contains("cam2cResult=awaiting frame"))

        composeTestRule.runOnUiThread {
            session = session.reduceCameraInfoResolved(5L, boundResolution())
        }
        composeTestRule.waitForIdle()

        text = buildPhysicalCameraExperimentReportText(session)
        assertTrue(text, text.contains("latestFrame=640x480"))
        assertTrue(text, text.contains("cam2cResult=DOMAIN_NOT_PROVEN"))
    }
}
