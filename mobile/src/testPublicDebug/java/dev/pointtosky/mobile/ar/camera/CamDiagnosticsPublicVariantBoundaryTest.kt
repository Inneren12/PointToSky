package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.mobile.ar.CameraPreview
import kotlin.reflect.KVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
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
            // The whole-active-array hypothesis diagnostic (moved out of :core:astro-core's public
            // production API since its only caller is the internalDebug-only export) - see
            // WholeActiveArrayMappingHypothesis.kt's own KDoc.
            "dev.pointtosky.mobile.ar.camera.SensorToBufferDomainBounds",
            "dev.pointtosky.mobile.ar.camera.SourceDomainBasis",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayHypothesisVerdict",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayMappingAssessment",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayMappingHypothesisKt",
            // CAM-2c physical-camera provenance experiment (physical-camera binding, topology recon,
            // and the transform-domain-proof gate) - internalDebug-only, must be absent here too.
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
            // screen-level attempt/retry model - internalDebug-only, must be absent here too.
            "dev.pointtosky.mobile.ar.camera.ExperimentSessionState",
            "dev.pointtosky.mobile.ar.camera.ExperimentSessionStateKt",
            "dev.pointtosky.mobile.ar.camera.ExperimentUiModel",
            "dev.pointtosky.mobile.ar.camera.ExperimentUiModelKt",
            // CAM-2c dual-basis diagnostic slice - internalDebug-only, must be absent here too.
            "dev.pointtosky.mobile.ar.camera.CameraCoordinateBasis",
            "dev.pointtosky.mobile.ar.camera.CameraCoordinateBasisKt",
            "dev.pointtosky.mobile.ar.camera.CameraBasisRole",
            "dev.pointtosky.mobile.ar.camera.CameraBasisCoordinateSpace",
            "dev.pointtosky.mobile.ar.camera.CameraBasisMetadataSource",
            "dev.pointtosky.mobile.ar.camera.CameraBasisRect",
            "dev.pointtosky.mobile.ar.camera.CameraX142MatrixModelKt",
            "dev.pointtosky.mobile.ar.camera.CameraX142PredictedSensorToBuffer",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayGeometryKt",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayGeometryClass",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayGeometryAssessment",
            "dev.pointtosky.mobile.ar.camera.WholeActiveArrayGeometryTolerances",
            "dev.pointtosky.mobile.ar.camera.DualBasisMatrixEvidenceKt",
            "dev.pointtosky.mobile.ar.camera.DualBasisMatrixEvidence",
            "dev.pointtosky.mobile.ar.camera.BasisMatrixAssessment",
            "dev.pointtosky.mobile.ar.camera.MatrixBasisLabel",
            "dev.pointtosky.mobile.ar.camera.DualBasisEvidenceLevel",
            "dev.pointtosky.mobile.ar.camera.DualBasisComparisonVerdict",
            "dev.pointtosky.mobile.ar.camera.OpenedLogicalCameraSnapshotResolution",
            "dev.pointtosky.mobile.ar.camera.OpenedLogicalCameraProvenance",
            "dev.pointtosky.mobile.ar.camera.DualBasisBindingResolution",
            "dev.pointtosky.mobile.ar.camera.MatrixStabilityCounters",
            "dev.pointtosky.mobile.ar.camera.MatrixStabilityCountersKt",
            "dev.pointtosky.mobile.ar.camera.AnalysisResolutionCandidate",
            "dev.pointtosky.mobile.ar.camera.AnalysisResolutionSize",
            "dev.pointtosky.mobile.ar.camera.AnalysisResolutionCandidatesKt",
            "dev.pointtosky.mobile.ar.camera.PhysicalCameraExperimentExportKt",
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

    /**
     * Architecture-leak fix, checked from the `publicDebug` side too (the seam lives in `main`, so
     * it is present - and must stay non-public - in *every* variant, not just `internalDebug`):
     * [AnalysisResolutionRequest], [AnalysisResolutionFamily], the `aspectRatioStrategyFor` mapping,
     * and [dev.pointtosky.mobile.ar.CameraPreview] are all `internal`, never public production API.
     * `KVisibility` (via `kotlin-reflect`) is the only reliable way to assert this - plain
     * `Class.forName`/`java.lang.reflect` cannot distinguish `internal` from `public`, since both
     * compile to a JVM-public class. Callable references (`::aspectRatioStrategyFor`,
     * `::CameraPreview`) are used rather than `Class.forName(...).kotlin.staticFunctions` - the
     * latter does not resolve top-level file-facade functions reliably; a direct reference to the
     * (already-imported, friend-visible) declaration is unambiguous and does not require invoking
     * it - `CameraPreview`'s `@Composable` context is only needed to *call* it, never to merely
     * reference it for reflection.
     */
    @Test
    fun `the resolution-request seam types and mapping function are internal, never public API`() {
        assertEquals(KVisibility.INTERNAL, AnalysisResolutionFamily::class.visibility)
        assertEquals(KVisibility.INTERNAL, AnalysisResolutionRequest::class.visibility)
        assertEquals(KVisibility.INTERNAL, (::aspectRatioStrategyFor).visibility)
        assertEquals(KVisibility.INTERNAL, (::CameraPreview).visibility)
    }
}
