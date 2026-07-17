package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c topology recon (`internalDebug` only, task §3). One physical camera candidate declared
 * behind a logical rear camera - via `CameraManager.getCameraCharacteristics(physicalId)` for the
 * exact declared physical ID, never inferred from ID ordering.
 */
data class PhysicalCameraTopologyEntry(
    val camera2Id: String,
    val focalLengthsMm: List<Float>,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    val activeArrayLeftPx: Int?,
    val activeArrayTopPx: Int?,
    val activeArrayRightPx: Int?,
    val activeArrayBottomPx: Int?,
)

/**
 * CAM-2c topology recon (task §3): one rear logical (or non-logical, single-sensor) Camera2 camera,
 * its static characteristics, and every physical camera candidate it declares (empty when the camera
 * is not a logical multi-camera). Deterministic, `internalDebug`-only text/JSON export goes through
 * the existing CAM diagnostics workflow (`CamDiagnosticSnapshot`/`CamDiagnosticReportFormat`).
 */
data class CameraTopologyEntry(
    val camera2Id: String,
    val lensFacing: String,
    val isLogicalMultiCamera: Boolean,
    val declaredPhysicalCameraIds: List<String>,
    val focalLengthsMm: List<Float>,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    val activeArrayLeftPx: Int?,
    val activeArrayTopPx: Int?,
    val activeArrayRightPx: Int?,
    val activeArrayBottomPx: Int?,
    val preCorrectionActiveArrayLeftPx: Int?,
    val preCorrectionActiveArrayTopPx: Int?,
    val preCorrectionActiveArrayRightPx: Int?,
    val preCorrectionActiveArrayBottomPx: Int?,
    val hardwareLevel: String?,
    val capabilities: List<String>,
    val imageAnalysisStreamConfigurationsPx: List<String>,
    val physicalCameraCandidates: List<PhysicalCameraTopologyEntry>,
)

/** All rear-camera topology entries discovered this recon pass, plus which CameraX identified as the
 * currently bound primary rear camera (when known) - the evidence basis task §4 requires before an
 * initial physical-candidate default may be selected. */
data class CameraTopologyReport(
    val entries: List<CameraTopologyEntry>,
    val boundPrimaryRearCamera2Id: String?,
)

private fun formatFocalLengths(values: List<Float>): String = if (values.isEmpty()) "unavailable" else values.joinToString(", ") { "%.2f".format(it) }

private fun formatRect(
    left: Int?,
    top: Int?,
    right: Int?,
    bottom: Int?,
): String =
    if (left == null || top == null || right == null || bottom == null) {
        "unavailable"
    } else {
        "[$left,$top — $right,$bottom]"
    }

private fun formatPhysicalEntry(entry: PhysicalCameraTopologyEntry): String =
    buildString {
        append("    physical id=${entry.camera2Id}")
        append(" focalLengthsMm=${formatFocalLengths(entry.focalLengthsMm)}")
        append(
            " sensorMm=${entry.sensorPhysicalWidthMm?.let { "%.2f".format(it) } ?: "unavailable"}" +
                "x${entry.sensorPhysicalHeightMm?.let { "%.2f".format(it) } ?: "unavailable"}",
        )
        append(" pixelArray=${entry.pixelArrayWidthPx ?: "unavailable"}x${entry.pixelArrayHeightPx ?: "unavailable"}")
        append(
            " activeArray=${
                formatRect(entry.activeArrayLeftPx, entry.activeArrayTopPx, entry.activeArrayRightPx, entry.activeArrayBottomPx)
            }",
        )
    }

/** Deterministic plain-text rendering of [CameraTopologyReport] (task §3's text export). */
fun buildCameraTopologyReportText(report: CameraTopologyReport): String =
    buildString {
        appendLine("CAMERA TOPOLOGY (CAM-2c recon)")
        appendLine("boundPrimaryRearCamera2Id: ${report.boundPrimaryRearCamera2Id ?: "unknown"}")
        if (report.entries.isEmpty()) {
            appendLine("no rear cameras discovered")
            return@buildString
        }
        for (entry in report.entries) {
            appendLine("---")
            appendLine("cameraId=${entry.camera2Id} lensFacing=${entry.lensFacing} logicalMultiCamera=${entry.isLogicalMultiCamera}")
            appendLine("declaredPhysicalCameraIds=${entry.declaredPhysicalCameraIds.ifEmpty { listOf("none") }.joinToString(",")}")
            appendLine("focalLengthsMm=${formatFocalLengths(entry.focalLengthsMm)}")
            appendLine(
                "sensorPhysicalSizeMm=${entry.sensorPhysicalWidthMm?.let { "%.2f".format(it) } ?: "unavailable"}" +
                    "x${entry.sensorPhysicalHeightMm?.let { "%.2f".format(it) } ?: "unavailable"}",
            )
            appendLine("pixelArray=${entry.pixelArrayWidthPx ?: "unavailable"}x${entry.pixelArrayHeightPx ?: "unavailable"}")
            appendLine(
                "activeArray=${
                    formatRect(entry.activeArrayLeftPx, entry.activeArrayTopPx, entry.activeArrayRightPx, entry.activeArrayBottomPx)
                }",
            )
            appendLine(
                "preCorrectionActiveArray=${
                    formatRect(
                        entry.preCorrectionActiveArrayLeftPx,
                        entry.preCorrectionActiveArrayTopPx,
                        entry.preCorrectionActiveArrayRightPx,
                        entry.preCorrectionActiveArrayBottomPx,
                    )
                }",
            )
            appendLine("hardwareLevel=${entry.hardwareLevel ?: "unavailable"}")
            appendLine("capabilities=${entry.capabilities.ifEmpty { listOf("none") }.joinToString(",")}")
            appendLine(
                "imageAnalysisStreamConfigurationsPx=${
                    entry.imageAnalysisStreamConfigurationsPx.ifEmpty { listOf("unavailable") }.joinToString(",")
                }",
            )
            if (entry.physicalCameraCandidates.isEmpty()) {
                appendLine("  no physical candidates declared")
            } else {
                for (physical in entry.physicalCameraCandidates) {
                    appendLine(formatPhysicalEntry(physical))
                }
            }
        }
    }
