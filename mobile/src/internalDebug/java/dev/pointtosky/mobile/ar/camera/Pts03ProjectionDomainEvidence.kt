package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * PTS-03 Group B (`internalDebug`-only): projection-domain compatibility evidence for one frame.
 *
 * Independent of identity (Group A): a known active physical ID says nothing about the basis the
 * delivered `sensorToBufferTransformMatrix` maps from. CameraX 1.4.2 is source-traced to build it from the
 * **opened (logical)** camera's active array (`docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §2.3), so
 * this records the whole-active-array hypothesis against **both** the logical and the physical arrays,
 * plus the physical pre-correction array, and lets the reader see which (if any) the matrix is consistent with.
 *
 * ## Conservative by construction
 * [classifyPts03ProjectionDomain] returns [Pts03EvidenceOutcome.PROVEN] **only** for a `Proven*`
 * [SensorToBufferDomainProof] — and the only proof this file ever builds is the existing
 * [evidenceOnlySensorToBufferDomainProof], which can return `Unresolved` or `HypothesisMismatch` and
 * nothing else. A hypothesis that *matches*, a frame-content verdict with small residuals, or a known
 * physical ID all stay [Pts03EvidenceOutcome.UNRESOLVED]. `resolveCam2cForExplicitPhysicalCamera` is not
 * called and not changed.
 */
internal enum class Pts03EvidenceOutcome {
    PROVEN,
    UNRESOLVED,
    MISMATCH,
}

/** Maps the typed proof to the report vocabulary. A failure to falsify is UNRESOLVED, never PROVEN. */
internal fun classifyPts03ProjectionDomain(proof: SensorToBufferDomainProof): Pts03EvidenceOutcome =
    when (proof) {
        SensorToBufferDomainProof.ProvenActiveArrayLocal,
        SensorToBufferDomainProof.ProvenPreCorrectionActiveArrayLocal,
        is SensorToBufferDomainProof.ProvenAnalysisSourceDomain,
        -> Pts03EvidenceOutcome.PROVEN
        is SensorToBufferDomainProof.HypothesisMismatch -> Pts03EvidenceOutcome.MISMATCH
        SensorToBufferDomainProof.Unresolved -> Pts03EvidenceOutcome.UNRESOLVED
    }

internal data class Pts03ProjectionDomainEvidence(
    /** The attributed producing camera whose static arrays were assessed (A2: requested; A1: reported active). */
    val producingPhysicalCameraId: String?,
    /** Top-level logical active ID; diagnostic only (in A2 it does not describe the analysed output). */
    val logicalActivePhysicalCameraId: String?,
    val requestedPhysicalCameraId: String?,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val matrixRowMajor: List<Double>?,
    val logicalWholeActiveArrayVerdict: WholeActiveArrayHypothesisVerdict?,
    val physicalWholeActiveArrayVerdict: WholeActiveArrayHypothesisVerdict?,
    val physicalWholePreCorrectionArrayVerdict: WholeActiveArrayHypothesisVerdict?,
    /** The existing evidence-only proof against the **physical** active array. Never `Proven*`. */
    val domainProof: SensorToBufferDomainProof,
    val outcome: Pts03EvidenceOutcome,
    /** Whether the proof would unlock `resolveCam2cForExplicitPhysicalCamera`. Always false for PTS-03 evidence. */
    val unlocksCalibratedProjection: Boolean,
    val frameContentVerdict: String?,
    val note: String,
)

/**
 * Builds the evidence for one frame. [physical] is the static characteristics of the attributed
 * [producingPhysicalCameraId] (see [attributePts03Frame]); `null` when the producer is unknown, in which case
 * only the logical hypothesis can be assessed and the outcome is UNRESOLVED.
 */
internal fun buildPts03ProjectionDomainEvidence(
    matrix: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
    logical: CameraCharacteristicsSnapshot?,
    physical: CameraCharacteristicsSnapshot?,
    producingPhysicalCameraId: String?,
    logicalActivePhysicalCameraId: String?,
    requestedPhysicalCameraId: String?,
    frameContentVerdict: FrameContentVerdict?,
): Pts03ProjectionDomainEvidence {
    fun assess(
        width: Int?,
        height: Int?,
    ): WholeActiveArrayHypothesisVerdict? =
        matrix?.let {
            assessWholeActiveArrayMappingHypothesis(
                matrix = it,
                sourceWidthPx = width,
                sourceHeightPx = height,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            ).verdict
        }

    val proof =
        evidenceOnlySensorToBufferDomainProof(
            matrix = matrix,
            activeArrayWidthPx = physical?.activeWidth(),
            activeArrayHeightPx = physical?.activeHeight(),
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    val outcome = if (physical == null) Pts03EvidenceOutcome.UNRESOLVED else classifyPts03ProjectionDomain(proof)
    return Pts03ProjectionDomainEvidence(
        producingPhysicalCameraId = producingPhysicalCameraId,
        logicalActivePhysicalCameraId = logicalActivePhysicalCameraId,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        matrixRowMajor = matrix?.let { listOf(it.m00, it.m01, it.m02, it.m10, it.m11, it.m12, it.m20, it.m21, it.m22) },
        logicalWholeActiveArrayVerdict = assess(logical?.activeWidth(), logical?.activeHeight()),
        physicalWholeActiveArrayVerdict = assess(physical?.activeWidth(), physical?.activeHeight()),
        physicalWholePreCorrectionArrayVerdict =
            assess(
                physical?.preCorrectionWidth(),
                physical?.preCorrectionHeight(),
            ),
        domainProof = proof,
        outcome = outcome,
        unlocksCalibratedProjection = proof.unlocksAnalysisBufferResolution(),
        frameContentVerdict = frameContentVerdict?.name,
        note =
            if (physical == null) {
                "UNRESOLVED: the producing physical camera is not identified for this frame, " +
                    "so its arrays cannot be assessed"
            } else {
                "Evidence only: a matching whole-array hypothesis or a small frame-content residual " +
                    "fails to falsify a " +
                    "mapping; it does not prove the matrix's source basis (SensorToBufferDomainProof stays non-Proven)"
            },
    )
}

private fun CameraCharacteristicsSnapshot.activeWidth(): Int? =
    if (activeArrayLeftPx != null && activeArrayRightPx != null) activeArrayRightPx - activeArrayLeftPx else null

private fun CameraCharacteristicsSnapshot.activeHeight(): Int? =
    if (activeArrayTopPx != null && activeArrayBottomPx != null) activeArrayBottomPx - activeArrayTopPx else null

private fun CameraCharacteristicsSnapshot.preCorrectionWidth(): Int? =
    if (preCorrectionActiveArrayLeftPx != null && preCorrectionActiveArrayRightPx != null) {
        preCorrectionActiveArrayRightPx - preCorrectionActiveArrayLeftPx
    } else {
        null
    }

private fun CameraCharacteristicsSnapshot.preCorrectionHeight(): Int? =
    if (preCorrectionActiveArrayTopPx != null && preCorrectionActiveArrayBottomPx != null) {
        preCorrectionActiveArrayBottomPx - preCorrectionActiveArrayTopPx
    } else {
        null
    }
