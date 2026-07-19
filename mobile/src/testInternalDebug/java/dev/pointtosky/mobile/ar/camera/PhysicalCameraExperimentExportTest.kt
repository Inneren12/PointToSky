package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for the dual-basis experiment export
 * (`PhysicalCameraExperimentExport.kt`) — deterministic text and JSON, full-precision matrix values,
 * explicitly labelled bases, and the mandatory safety statements (task §9/§12/§13).
 */
class PhysicalCameraExperimentExportTest {
    private val pixel9Matrix =
        SensorToBufferMatrix3(
            m00 = 0.1568627506494522, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 0.1568627506494522, m12 = -0.9411764740943909,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun snapshot(cameraId: String, isLogical: Boolean = false) =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(6.9f),
            sensorPhysicalWidthMm = 9.8f,
            sensorPhysicalHeightMm = 7.3f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4080,
            activeArrayBottomPx = 3072,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            isLogicalMultiCamera = isLogical,
            cameraId = cameraId,
        )

    private fun fullSession(): ExperimentSessionState {
        val dualBinding =
            DualBasisBindingResolution(
                binding =
                    PhysicalCameraBindingResolution.Bound(
                        provenance =
                            PhysicalCameraProvenance(
                                logicalCameraId = "0",
                                physicalCameraId = "2",
                                bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                                confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                            ),
                        physicalCharacteristicsSnapshot = snapshot("2"),
                    ),
                openedLogicalCamera =
                    OpenedLogicalCameraSnapshotResolution.Captured(
                        snapshot = snapshot("0", isLogical = true),
                        provenance = OpenedLogicalCameraProvenance.BOUND_CAMERA_INFO_IS_OPENED_LOGICAL_PARENT,
                    ),
            )
        val frame =
            CameraFrameMetadata(
                timestampNanos = 1L,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
                rotationDegrees = 90,
                cropRectLeftPx = 0, cropRectTopPx = 0, cropRectRightPx = 640, cropRectBottomPx = 480,
                sensorToBufferTransform = pixel9Matrix,
            )
        return initialExperimentSessionState(7L, "2", AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3))
            .reduceDualBasisBindingResolved(7L, dualBinding, zoomTargetRatio = 1.0f, observedZoomRatio = 1.0f)
            .reduceFrame(7L, frame)
    }

    @Test
    fun `the text report labels both bases, the geometry class, and the safety statements`() {
        val text = buildPhysicalCameraExperimentReportText(fullSession())

        assertTrue(text.contains("LOGICAL_OPENED_CAMERA_BASIS"))
        assertTrue(text.contains("SELECTED_PHYSICAL_CAMERA_BASIS"))
        assertTrue(text.contains("geometryClass=UNIFORM_SCALE_CENTER_CROP"))
        assertTrue(text.contains("comparisonVerdict=MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE"))
        assertTrue(text.contains("modelComparison=MATCHES_MODEL"))
        assertTrue(text.contains("FRAME_CONTENT_CORRESPONDENCE_UNMEASURED"))
        assertTrue(text.contains("cam2cResult=DOMAIN_NOT_PROVEN"))
        assertTrue(text.contains("geometryClassificationIsEvidenceOnly=true"))
        assertTrue(text.contains("frameContentCorrespondenceMeasured=false"))
        assertTrue(text.contains("provenDomainVariantConstructed=false"))
        assertTrue(text.contains("pinnedCameraXVersion=1.4.2"))
        // The opened logical camera is reported with its own identity, never merged into the physical.
        assertTrue(text.contains("OPENED LOGICAL CAMERA: captured(cameraId=0"))
        // Requested family, requested WxH, and actual bound WxH are three independent lines.
        assertTrue(text.contains("requestedAnalysisResolution=640x480"))
        assertTrue(text.contains("requestedAnalysisResolutionFamily=NEAR_4_3"))
        assertTrue(text.contains("actualAnalysisResolution=640x480"))
        // Stability export is self-describing: threshold name/value and the two separated notions.
        assertTrue(text.contains("exactValueMatrixChanges=0"))
        assertTrue(text.contains("mappedDisplacementChangesBeyondTolerance=0"))
        assertTrue(text.contains("MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX=0.001"))
        assertTrue(text.contains("zoomTargetRatio=1.0"))
        assertTrue(text.contains("observedZoomRatio=1.0"))
        // Full-precision matrix values (widened float32), never rounded for display.
        assertTrue(text.contains("0.1568627506494522"))
        assertTrue(text.contains("-0.9411764740943909"))
    }

    @Test
    fun `the JSON export preserves all nine matrix values at full precision with explicit order and origin`() {
        val json = buildPhysicalCameraExperimentJson(fullSession(), capturedAtEpochMillis = 1_700_000_000_000L)
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals(PHYSICAL_CAMERA_EXPERIMENT_JSON_SCHEMA_VERSION, root["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("1.4.2", root["pinnedCameraXVersion"]!!.jsonPrimitive.content)
        val session = root["session"]!!.jsonObject
        val observedFrame = session["observedFrame"]!!.jsonObject
        val matrix = observedFrame["matrix"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(9, matrix.size)
        assertEquals(0.1568627506494522, matrix[0], 0.0)
        assertEquals(-0.9411764740943909, matrix[5], 0.0)
        assertEquals("m00,m01,m02,m10,m11,m12,m20,m21,m22", observedFrame["matrixValueOrder"]!!.jsonPrimitive.content)
        assertTrue("float32" in observedFrame["matrixValueOrigin"]!!.jsonPrimitive.content)
        assertEquals(90, observedFrame["rotationDegrees"]!!.jsonPrimitive.int)
        assertEquals(0, observedFrame["cropRectLeftPx"]!!.jsonPrimitive.int)
        assertEquals(640, observedFrame["cropRectRightPx"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the JSON export carries labelled dual-basis assessments and the safety state`() {
        val json = buildPhysicalCameraExperimentJson(fullSession(), capturedAtEpochMillis = 0L)
        val session = Json.parseToJsonElement(json).jsonObject["session"]!!.jsonObject

        val evidence = session["dualBasisEvidence"]!!.jsonObject
        assertEquals(
            "MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE",
            evidence["comparisonVerdict"]!!.jsonPrimitive.content,
        )
        val logical = evidence["logicalBasisAssessment"]!!.jsonObject
        assertEquals("LOGICAL_OPENED_CAMERA_BASIS", logical["basisLabel"]!!.jsonPrimitive.content)
        assertEquals("0", logical["cameraId"]!!.jsonPrimitive.content)
        assertEquals("ACTIVE_ARRAY_NATIVE", logical["coordinateSpace"]!!.jsonPrimitive.content)
        assertEquals("UNIFORM_SCALE_CENTER_CROP", logical["geometryClass"]!!.jsonPrimitive.content)
        assertEquals("MATCHES_MODEL", logical["modelComparison"]!!.jsonPrimitive.content)
        assertEquals("euclidean_hypot", logical["maxMappedPointResidualMetric"]!!.jsonPrimitive.content)
        assertEquals(true, logical["matchesCameraX142ImplementationModel"]!!.jsonPrimitive.boolean)
        val physical = evidence["physicalBasisAssessment"]!!.jsonObject
        assertEquals("SELECTED_PHYSICAL_CAMERA_BASIS", physical["basisLabel"]!!.jsonPrimitive.content)
        assertEquals("2", physical["cameraId"]!!.jsonPrimitive.content)

        // Requested family is an independent JSON fact (P1 family fix).
        assertEquals("NEAR_4_3", session["requestedAnalysisResolutionFamily"]!!.jsonPrimitive.content)
        assertEquals(640, session["actualAnalysisResolutionWidthPx"]!!.jsonPrimitive.int)

        val safety = session["safetyState"]!!.jsonObject
        assertEquals("DomainNotProven", safety["cam2cResultType"]!!.jsonPrimitive.content)
        assertEquals(true, safety["geometryClassificationIsEvidenceOnly"]!!.jsonPrimitive.boolean)
        assertEquals(false, safety["frameContentCorrespondenceMeasured"]!!.jsonPrimitive.boolean)
        assertEquals(false, safety["provenDomainVariantConstructed"]!!.jsonPrimitive.boolean)

        val stability = session["matrixStability"]!!.jsonObject
        assertEquals(1, stability["framesObserved"]!!.jsonPrimitive.int)
        assertEquals(0, stability["framesWithNullTransform"]!!.jsonPrimitive.int)
        assertEquals(0, stability["exactValueMatrixChanges"]!!.jsonPrimitive.int)
        assertEquals(0, stability["mappedDisplacementChangesBeyondTolerance"]!!.jsonPrimitive.int)
        // Self-describing thresholds (P2 fix): names, values, and the reference rectangle.
        assertEquals(1e-3, stability["mappedDisplacementTolerancePx"]!!.jsonPrimitive.double, 0.0)
        assertEquals(
            "MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX",
            stability["mappedDisplacementToleranceName"]!!.jsonPrimitive.content,
        )
        assertEquals(4096, stability["referenceRectWidthPx"]!!.jsonPrimitive.int)
        assertEquals(3072, stability["referenceRectHeightPx"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the JSON export is deterministic for the same session and timestamp`() {
        val first = buildPhysicalCameraExperimentJson(fullSession(), 42L)
        val second = buildPhysicalCameraExperimentJson(fullSession(), 42L)
        assertEquals(first, second)
    }

    @Test
    fun `a null session exports a typed empty envelope`() {
        val root = Json.parseToJsonElement(buildPhysicalCameraExperimentJson(null, 42L)).jsonObject
        assertEquals(PHYSICAL_CAMERA_EXPERIMENT_JSON_SCHEMA_VERSION, root["schemaVersion"]!!.jsonPrimitive.int)
        assertTrue(root["session"] is kotlinx.serialization.json.JsonNull)
    }
}
