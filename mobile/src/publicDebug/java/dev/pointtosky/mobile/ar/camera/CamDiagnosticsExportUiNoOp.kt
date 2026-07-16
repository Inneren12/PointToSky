package dev.pointtosky.mobile.ar.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No-op [CamDiagnosticsExportUi] (architecture fix §1) - compiled into the `publicDebug` variant only.
 * Renders nothing and references no clipboard/share/JSON/snapshot/dialog code at all, so none of that
 * code is ever compiled into, or packaged in, `publicDebug` (which - unlike `internalRelease`/
 * `publicRelease` - is never R8-minified in this project, so without this file the real implementation's
 * `.class`/`.dex` output would otherwise ship in the debuggable public APK even though never invoked).
 *
 * See `mobile/src/release/.../CamDiagnosticsExportUiNoOp.kt`'s own KDoc for why `publicDebug` needs
 * this separate copy rather than sharing that file's `release`-build-type source set.
 */
internal object CamDiagnosticsExportUiProvider : CamDiagnosticsExportUi {
    @Composable
    override fun Content(
        input: CamDiagnosticsExportInput,
        modifier: Modifier,
    ) {
        // Intentionally empty - see this file's own KDoc.
    }
}
