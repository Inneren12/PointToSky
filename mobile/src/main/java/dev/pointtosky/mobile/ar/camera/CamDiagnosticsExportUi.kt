package dev.pointtosky.mobile.ar.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState

/**
 * Variant-safe entry-point boundary for the CAM diagnostics export/freeze workflow (architecture fix
 * §1). This file - the interface, [CamDiagnosticsExportInput], and every type it references - lives in
 * `main` (compiled into every build variant) purely as a *seam*: it carries no clipboard/share/JSON/
 * Compose-dialog implementation of its own, only the shape of the call. `CamDiagnosticTopPanels`
 * (`main`, shared by every variant) calls [CamDiagnosticsExportUiProvider] - a top-level `object`
 * resolved to a **different compiled class per build variant**, never a `when`/reflection dispatch at
 * runtime:
 *
 * - `mobile/src/internalDebug/.../CamDiagnosticsExportUiImpl.kt` provides the real implementation
 *   (snapshot capture, deterministic text/JSON formatting, Freeze/Resume, Copy all, Share log, Share
 *   JSON) - compiled only into the `internalDebug` variant.
 * - `mobile/src/release/.../CamDiagnosticsExportUiNoOp.kt` (covers `internalRelease`+`publicRelease`)
 *   and `mobile/src/publicDebug/.../CamDiagnosticsExportUiNoOp.kt` (covers `publicDebug`) each provide a
 *   [Content] that renders nothing and hold no reference to any clipboard/share/JSON/snapshot code at
 *   all - so that code is never compiled into, and never packaged in, any variant other than
 *   `internalDebug`. See either no-op file's own KDoc for why this exact two-file split (rather than
 *   one shared "not internalDebug" source set, which AGP does not offer directly) covers exactly the
 *   three non-`internalDebug` variants without any variant receiving two conflicting definitions.
 *
 * This is a plain, compile-time Kotlin/AGP source-set resolution - the same `object` fully-qualified
 * name is defined in exactly one of the four variant-specific source sets per compiled variant, so the
 * Kotlin compiler picks the one applicable definition for whichever variant is being built. No
 * reflection, no runtime class lookup (`Class.forName`, `ServiceLoader`, etc.), and no reliance on R8/
 * minification stripping unreached code - `publicDebug` (never minified in this project) still never
 * compiles or packages the real implementation's `.class`/`.dex` output.
 */
data class CamDiagnosticsExportInput(
    val sessionId: Long,
    val cam2bState: PredictedStarOverlayState?,
    val cameraGeometryState: CameraGeometryDiagnosticSnapshot?,
    val cameraGeometryStatusTransitionCount: Int,
    val cameraGeometryObservedFrameCount: Long,
    val cameraGeometryReadyBundleCount: Long,
    val cameraIntrinsicsState: CameraSessionIntrinsicsDiagnosticState?,
    val calibrationDiagnostics: CameraCalibrationDiagnostics?,
)

/**
 * The one seam `CamDiagnosticTopPanels` calls through. [Content] owns everything about the export
 * workflow's own UI - the compact summary, "Open diagnostics", and the full report dialog - and must
 * render nothing that requires scroll/gesture capture beyond its own bounds when it has nothing to show
 * (the no-op implementations render a zero-size `Unit` composable).
 */
interface CamDiagnosticsExportUi {
    @Composable
    fun Content(
        input: CamDiagnosticsExportInput,
        modifier: Modifier,
    )
}
