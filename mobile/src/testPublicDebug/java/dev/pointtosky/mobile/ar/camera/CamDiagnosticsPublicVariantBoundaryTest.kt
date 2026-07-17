package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `publicDebug`-only. Proves the CAM diagnostics export implementation genuinely does not exist on this
 * variant's own compiled/runtime classpath (architecture fix §1) - not merely "never called at
 * runtime," but "the class was never compiled into this variant and is absent from its output at all."
 * `Class.forName` here is a **verification technique for this test itself**, not a production runtime
 * lookup mechanism for the export feature (which the architecture fix requires to use plain compile-time
 * source-set resolution only, never reflection) - see `CamDiagnosticsExportUi.kt`'s own KDoc.
 *
 * This test runs as part of `:mobile:testPublicDebugUnitTest`, which only exists (and only picks up
 * this file) for the `publicDebug` variant - `testInternalDebug`'s sibling test asserts the opposite.
 */
class CamDiagnosticsPublicVariantBoundaryTest {
    private val exportOnlyClassNames =
        listOf(
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticReportFormatKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotJsonKt",
            "dev.pointtosky.mobile.ar.CamDiagnosticActionsKt",
            "dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialogKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUiImplKt",
        )

    @Test
    fun `the real export implementation classes are absent from the publicDebug classpath`() {
        for (className in exportOnlyClassNames) {
            assertFailsWith<ClassNotFoundException>("expected $className to be absent from publicDebug, but it loaded") {
                Class.forName(className)
            }
        }
    }

    @Test
    fun `the no-op export provider itself is present, unlike the real implementation`() {
        // The no-op CamDiagnosticsExportUiProvider (mobile/src/publicDebug/...) IS expected to exist -
        // only the real implementation's classes above are expected to be absent. Class.forName
        // succeeding (no exception) is the assertion here.
        Class.forName("dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUiProvider")
    }
}
