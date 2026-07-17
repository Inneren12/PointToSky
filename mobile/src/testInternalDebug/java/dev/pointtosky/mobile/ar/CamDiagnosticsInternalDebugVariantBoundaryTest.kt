package dev.pointtosky.mobile.ar

import kotlin.test.Test

/**
 * `internalDebug`-only sanity check, sibling of `CamDiagnosticsPublicVariantBoundaryTest`
 * (`testPublicDebug`) - proves the real export implementation classes ARE present on the `internalDebug`
 * classpath, so the public-variant absence test can't pass merely because the class names were
 * misspelled or the file never existed at all.
 */
class CamDiagnosticsInternalDebugVariantBoundaryTest {
    private val exportOnlyClassNames =
        listOf(
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshot",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticReportFormatKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticSnapshotJsonKt",
            "dev.pointtosky.mobile.ar.CamDiagnosticActionsKt",
            "dev.pointtosky.mobile.ar.CamDiagnosticFullReportDialogKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUiImplKt",
            "dev.pointtosky.mobile.ar.camera.CamDiagnosticsExportUiProvider",
            // The whole-active-array hypothesis diagnostic (moved out of :core:astro-core's public
            // production API since its only caller is this internalDebug-only export) - see
            // WholeActiveArrayMappingHypothesis.kt's own KDoc.
            "dev.pointtosky.mobile.ar.camera.SensorToBufferDomainBounds",
            "dev.pointtosky.mobile.ar.camera.SourceDomainBasis",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayHypothesisVerdict",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayMappingAssessment",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayMappingHypothesisKt",
        )

    @Test
    fun `the real export implementation classes are present on the internalDebug classpath`() {
        for (className in exportOnlyClassNames) {
            Class.forName(className)
        }
    }
}
