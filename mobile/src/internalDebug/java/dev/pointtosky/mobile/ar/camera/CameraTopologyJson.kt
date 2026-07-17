package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `internalDebug`-only. Schema version for [buildCameraTopologyJson] (task §3's JSON export) - a new,
 * independent export, versioned separately from [CAM_DIAGNOSTIC_JSON_SCHEMA_VERSION] since it
 * describes static camera topology, not a CAM-2c resolution attempt. */
const val CAMERA_TOPOLOGY_JSON_SCHEMA_VERSION: Int = 1

private fun physicalCandidateJson(entry: PhysicalCameraTopologyEntry) =
    buildJsonObject {
        put("camera2Id", entry.camera2Id)
        put("focalLengthsMm", buildJsonArray { entry.focalLengthsMm.forEach { add(it) } })
        put("sensorPhysicalWidthMm", entry.sensorPhysicalWidthMm)
        put("sensorPhysicalHeightMm", entry.sensorPhysicalHeightMm)
        put("pixelArrayWidthPx", entry.pixelArrayWidthPx)
        put("pixelArrayHeightPx", entry.pixelArrayHeightPx)
        put("activeArrayLeftPx", entry.activeArrayLeftPx)
        put("activeArrayTopPx", entry.activeArrayTopPx)
        put("activeArrayRightPx", entry.activeArrayRightPx)
        put("activeArrayBottomPx", entry.activeArrayBottomPx)
    }

private fun entryJson(entry: CameraTopologyEntry) =
    buildJsonObject {
        put("camera2Id", entry.camera2Id)
        put("lensFacing", entry.lensFacing)
        put("isLogicalMultiCamera", entry.isLogicalMultiCamera)
        put("declaredPhysicalCameraIds", buildJsonArray { entry.declaredPhysicalCameraIds.forEach { add(it) } })
        put("focalLengthsMm", buildJsonArray { entry.focalLengthsMm.forEach { add(it) } })
        put("sensorPhysicalWidthMm", entry.sensorPhysicalWidthMm)
        put("sensorPhysicalHeightMm", entry.sensorPhysicalHeightMm)
        put("pixelArrayWidthPx", entry.pixelArrayWidthPx)
        put("pixelArrayHeightPx", entry.pixelArrayHeightPx)
        put("activeArrayLeftPx", entry.activeArrayLeftPx)
        put("activeArrayTopPx", entry.activeArrayTopPx)
        put("activeArrayRightPx", entry.activeArrayRightPx)
        put("activeArrayBottomPx", entry.activeArrayBottomPx)
        put("preCorrectionActiveArrayLeftPx", entry.preCorrectionActiveArrayLeftPx)
        put("preCorrectionActiveArrayTopPx", entry.preCorrectionActiveArrayTopPx)
        put("preCorrectionActiveArrayRightPx", entry.preCorrectionActiveArrayRightPx)
        put("preCorrectionActiveArrayBottomPx", entry.preCorrectionActiveArrayBottomPx)
        put("hardwareLevel", entry.hardwareLevel)
        put("capabilities", buildJsonArray { entry.capabilities.forEach { add(it) } })
        put("imageAnalysisStreamConfigurationsPx", buildJsonArray { entry.imageAnalysisStreamConfigurationsPx.forEach { add(it) } })
        put("physicalCameraCandidates", buildJsonArray { entry.physicalCameraCandidates.forEach { add(physicalCandidateJson(it)) } })
    }

/** Deterministic JSON element rendering of [CameraTopologyReport] (task §3's JSON export), explicit
 * `null`s, field order matching [buildCameraTopologyReportText]. */
fun buildCameraTopologyJsonElement(report: CameraTopologyReport): JsonObject =
    buildJsonObject {
        put("schemaVersion", CAMERA_TOPOLOGY_JSON_SCHEMA_VERSION)
        put("boundPrimaryRearCamera2Id", report.boundPrimaryRearCamera2Id)
        put("entries", buildJsonArray { report.entries.forEach { add(entryJson(it)) } })
    }

/** [buildCameraTopologyJsonElement] serialized to a pretty-printed string via the same [camDiagnosticJson]
 * instance the rest of the CAM diagnostics export uses. */
fun buildCameraTopologyJson(
    report: CameraTopologyReport,
    json: Json = camDiagnosticJson,
): String = json.encodeToString(JsonObject.serializer(), buildCameraTopologyJsonElement(report))
