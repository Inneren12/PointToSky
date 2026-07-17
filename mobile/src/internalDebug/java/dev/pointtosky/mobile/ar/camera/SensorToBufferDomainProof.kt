package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * CAM-2c physical-camera provenance experiment fix (P1 blocker #2). Physical-sensor identity and
 * sensor-to-buffer transform-domain identity are **independent proofs**. A verified
 * [PhysicalCameraBindingResolution.Bound] proves *which sensor* produced this session's
 * characteristics; it says nothing about whether [resolveAnalysisBufferIntrinsics]'s own implicit
 * assumption — that the sensor-to-buffer matrix's source domain is the active-array-local rectangle
 * its K-composition already lives in — actually holds for this device's real matrix. This codebase has
 * never source-traced or device-proven that assumption for any pinned CameraX version (see
 * `WholeActiveArrayMappingHypothesis.kt`'s own KDoc, and `docs/validation/cam_2c_pixel9_evidence.md`
 * §3's real-device identity-matrix finding). [resolveCam2cForExplicitPhysicalCamera] must never call
 * [resolveAnalysisBufferIntrinsics] on the strength of physical-camera identity alone — a
 * [SensorToBufferDomainProof] must independently be one of the `Proven*` variants below first.
 */
internal sealed interface SensorToBufferDomainProof {
    /**
     * Proven — by evidence this codebase does not yet have any automated means to produce — that the
     * transform's source domain is exactly the active-array-local rectangle
     * [resolveAnalysisBufferIntrinsics]'s own K-composition (`mapActiveArrayIntrinsicsThroughMatrix`)
     * already assumes. This is the *only* proof variant [resolveCam2cForExplicitPhysicalCamera]
     * currently accepts before calling [resolveAnalysisBufferIntrinsics] — see that function's own
     * KDoc. No production or experiment code path in this codebase constructs this automatically
     * today; it exists so a future pass that source-traces or device-proves the pinned (or an
     * upgraded) CameraX version's real contract has a typed place to record that proof, and so tests
     * can exercise the `Resolved` path this proof unlocks without waiting for that future pass.
     */
    data object ProvenActiveArrayLocal : SensorToBufferDomainProof

    /**
     * Proven that the transform's source domain is the pre-correction-active-array-local rectangle —
     * a distinct coordinate space from the active array whenever
     * `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` differs from `SENSOR_INFO_ACTIVE_ARRAY_SIZE`.
     * [resolveAnalysisBufferIntrinsics]'s own K-composition assumes active-array-local coordinates,
     * not pre-correction-active-array-local ones, so this proof does **not** currently unlock calling
     * it — a future pass would need to add the corresponding coordinate translation before this
     * variant could too. Kept distinct from [ProvenActiveArrayLocal] so a caller/report can tell the
     * two proven bases apart rather than conflating them.
     */
    data object ProvenPreCorrectionActiveArrayLocal : SensorToBufferDomainProof

    /**
     * A general, explicitly-named proven source-domain basis other than the two above. [basis] is a
     * short, human-readable label describing it — only ever constructed once a real proof exists to
     * describe, never a guess. Does not currently unlock calling [resolveAnalysisBufferIntrinsics]
     * (only [ProvenActiveArrayLocal] does, since that is the one domain its own math assumes).
     */
    data class ProvenAnalysisSourceDomain(val basis: String) : SensorToBufferDomainProof

    /**
     * No proof exists that the transform's source domain matches what
     * [resolveAnalysisBufferIntrinsics] assumes. This is the honest, and currently the *only
     * automatically reachable*, outcome for every real experiment session in this codebase — no
     * CameraX version this codebase has used has ever had its sensor-to-buffer source-domain contract
     * source-traced or device-proven (see `docs/camera_coordinate_calibration_contract.md` §3.5-§3.9).
     * An unresolved identity matrix must never resolve, regardless of how trivial the matrix's own
     * numbers look.
     */
    data object Unresolved : SensorToBufferDomainProof

    /**
     * [assessWholeActiveArrayMappingHypothesis] — the one hypothesis this codebase can currently test —
     * was run and returned [WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH].
     * Distinct from [Unresolved] (no evidence was ever gathered at all) since this carries a specific
     * negative evidentiary result. Per that function's own KDoc, a mismatch is evidence *against* the
     * one tested hypothesis — never proof the matrix is broken — but it is exactly as insufficient as
     * [Unresolved] for actually resolving calibrated intrinsics: this codebase requires *positive*,
     * out-of-band proof (one of the `Proven*` variants above), never merely the absence of a detected
     * mismatch, before treating a transform's source domain as usable.
     */
    data class HypothesisMismatch(val verdict: WholeActiveArrayHypothesisVerdict) : SensorToBufferDomainProof
}

/** `true` only for the one [SensorToBufferDomainProof] variant [resolveAnalysisBufferIntrinsics]'s own
 * K-composition math is actually proven safe for — see [SensorToBufferDomainProof.ProvenActiveArrayLocal]'s
 * own KDoc for why the other `Proven*` variants do not (yet) qualify. */
internal fun SensorToBufferDomainProof.unlocksAnalysisBufferResolution(): Boolean = this is SensorToBufferDomainProof.ProvenActiveArrayLocal

/**
 * The one automatic, currently-reachable way this codebase computes a [SensorToBufferDomainProof] for
 * a real experiment session (never a proof — see [SensorToBufferDomainProof.Unresolved]'s own KDoc):
 * runs [assessWholeActiveArrayMappingHypothesis] against the *physical* camera's own active-array
 * dimensions ([activeArrayWidthPx]/[activeArrayHeightPx] — never the logical camera's) and [matrix],
 * and maps a genuine mismatch to [SensorToBufferDomainProof.HypothesisMismatch]; every other outcome —
 * including a *match*, which per that function's own KDoc is still not proof — maps to
 * [SensorToBufferDomainProof.Unresolved]. `null` [matrix] (no frame observed yet, or the caller never
 * received a transform) is [SensorToBufferDomainProof.Unresolved] directly, without attempting an
 * assessment [assessWholeActiveArrayMappingHypothesis] cannot run without a matrix.
 *
 * This function alone can never return a `Proven*` variant — preserving the existing whole-active-array
 * hypothesis diagnostic as evidence only, never promoting it to proof (task requirement). A `Proven*`
 * result can only ever be constructed by a future pass that actually source-traces or device-proves a
 * real contract, or supplied directly (e.g. by a test) — never derived from this hypothesis check.
 */
internal fun evidenceOnlySensorToBufferDomainProof(
    matrix: SensorToBufferMatrix3?,
    activeArrayWidthPx: Int?,
    activeArrayHeightPx: Int?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): SensorToBufferDomainProof {
    if (matrix == null) return SensorToBufferDomainProof.Unresolved
    val assessment =
        assessWholeActiveArrayMappingHypothesis(
            matrix = matrix,
            sourceWidthPx = activeArrayWidthPx,
            sourceHeightPx = activeArrayHeightPx,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    return if (assessment.verdict == WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH) {
        SensorToBufferDomainProof.HypothesisMismatch(assessment.verdict)
    } else {
        SensorToBufferDomainProof.Unresolved
    }
}
