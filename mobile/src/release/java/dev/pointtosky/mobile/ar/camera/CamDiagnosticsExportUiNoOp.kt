package dev.pointtosky.mobile.ar.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * No-op [CamDiagnosticsExportUi] (architecture fix §1) - compiled into every `release` variant
 * (`internalRelease` **and** `publicRelease`, via AGP's `release` build-type source set). Renders
 * nothing and references no clipboard/share/JSON/snapshot/dialog code at all, so none of that code is
 * ever compiled into, or packaged in, a release build - not even `internalRelease` (the export
 * workflow's own runtime gate, `CameraGeometryDiagnosticsGate.isEnabled`, already restricts it to
 * `internalDebug` at runtime; this file additionally removes it at *compile* time, per this fix's own
 * "no reliance on R8" requirement).
 *
 * See [CamDiagnosticsExportUi]'s own KDoc for the full source-set split. `publicDebug` is covered by
 * the sibling no-op at `mobile/src/publicDebug/.../CamDiagnosticsExportUiNoOp.kt` instead, since AGP has
 * no single source set spanning exactly {`internalRelease`, `publicDebug`, `publicRelease`} - `release`
 * (this file) covers `internalRelease`+`publicRelease`; `publicDebug` needs its own copy.
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
