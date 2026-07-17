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
            // CAM-2c physical-camera provenance experiment (physical-camera binding, topology recon,
            // and the transform-domain-proof gate) - internalDebug-only, no production API surface.
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraProvenance",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingMethod",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraProvenanceConfidence",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingSource",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingResolution",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraInfoSelection",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraProvenanceKt",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentKt",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentActivity",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraBindingExperimentScreenKt",
            "dev.pointtosky.mobile.ar.camera.Cam2cPhysicalCameraResolution",
            "dev.pointtosky.mobile.ar.camera.Cam2cPhysicalCameraResolutionKt",
            "dev.pointtosky.mobile.ar.camera.SensorToBufferDomainProof",
            "dev.pointtosky.mobile.ar.camera.SensorToBufferDomainProofKt",
            "dev.pointtosky.mobile.ar.camera.CameraTopologyEntry",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraTopologyEntry",
            "dev.pointtosky.mobile.ar.camera.CameraTopologyReport",
            "dev.pointtosky.mobile.ar.camera.CameraTopologyReportKt",
            "dev.pointtosky.mobile.ar.camera.CameraTopologyBuilderKt",
            "dev.pointtosky.mobile.ar.camera.CameraTopologyJsonKt",
            // CAM-2c runtime correctness fix: the pure per-attempt session state machine and the
            // screen-level attempt/retry model - internalDebug-only, no production API surface.
            "dev.pointtosky.mobile.ar.camera.ExperimentSessionState",
            "dev.pointtosky.mobile.ar.camera.ExperimentSessionStateKt",
            "dev.pointtosky.mobile.ar.camera.ExperimentUiModel",
            "dev.pointtosky.mobile.ar.camera.ExperimentUiModelKt",
        )

    @Test
    fun `the real export implementation classes are present on the internalDebug classpath`() {
        for (className in exportOnlyClassNames) {
            Class.forName(className)
        }
    }
}
