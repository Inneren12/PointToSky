package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * PTS-03 Group C (`internalDebug`-only): distortion state and the final pixel coordinate space.
 *
 * Three things are kept strictly apart, because Camera2 keeps them apart:
 * 1. **What was asked** — [Pts03DistortionModeRequest] (including "nothing was asked": the device default).
 * 2. **What the result says ran** — [Pts03EffectiveDistortionMode], per frame, from
 *    `CaptureResult.DISTORTION_CORRECTION_MODE`.
 * 3. **What the pixels actually show** — [Pts03ResidualDistortionMeasurement], measured from printed-target
 *    residuals. Never inferred from 2: `FAST` is allowed to behave like `OFF`, and `HIGH_QUALITY` is allowed
 *    to leave residual error.
 *
 * The mode decides only the **metadata** coordinate basis ([Pts03MetadataCoordinateBasis]): `OFF` →
 * pre-correction active array, any other mode → active array. `LENS_INTRINSIC_CALIBRATION` is always
 * defined in the pre-correction active-array basis, whatever the mode.
 *
 * Nothing in this file applies `LENS_DISTORTION` to anything.
 */

/** What the experiment asked the capture request for. */
internal enum class Pts03DistortionModeRequest(
    /** The Camera2 value set on the request, or `null` when nothing is set (device default). */
    val camera2Value: Int?,
) {
    DEVICE_DEFAULT(null),
    OFF(0),
    FAST(1),
    HIGH_QUALITY(2),
}

/** What one frame's `CaptureResult` says ran. */
internal sealed interface Pts03EffectiveDistortionMode {
    data object Off : Pts03EffectiveDistortionMode

    data object Fast : Pts03EffectiveDistortionMode

    data object HighQuality : Pts03EffectiveDistortionMode

    /** A value Camera2 does not define (as of this code). Preserved, never coerced to a known mode. */
    data class UnknownValue(
        val raw: Int,
    ) : Pts03EffectiveDistortionMode

    /** The result did not carry the key. Not the same as `OFF`. */
    data object NotReported : Pts03EffectiveDistortionMode

    /** API < 28: the key does not exist on this platform. */
    data object ApiUnsupported : Pts03EffectiveDistortionMode

    /**
     * A2 explicit-physical frame with no usable physical result for the bound camera: the stream's mode is
     * unknown, and the logical (top-level) mode is deliberately not substituted.
     */
    data object PhysicalResultUnavailable : Pts03EffectiveDistortionMode
}

internal val Pts03EffectiveDistortionMode.label: String
    get() =
        when (this) {
            Pts03EffectiveDistortionMode.Off -> "OFF"
            Pts03EffectiveDistortionMode.Fast -> "FAST"
            Pts03EffectiveDistortionMode.HighQuality -> "HIGH_QUALITY"
            is Pts03EffectiveDistortionMode.UnknownValue -> "UNKNOWN_$raw"
            Pts03EffectiveDistortionMode.NotReported -> "NOT_REPORTED"
            Pts03EffectiveDistortionMode.ApiUnsupported -> "API_UNSUPPORTED"
            Pts03EffectiveDistortionMode.PhysicalResultUnavailable -> "UNRESOLVED_PHYSICAL_RESULT_UNAVAILABLE"
        }

internal fun Pts03CaptureTruth.effectiveDistortionMode(): Pts03EffectiveDistortionMode =
    when (distortionCorrectionModeAvailability) {
        Pts03KeyAvailability.API_UNSUPPORTED -> Pts03EffectiveDistortionMode.ApiUnsupported
        Pts03KeyAvailability.NOT_REPORTED -> Pts03EffectiveDistortionMode.NotReported
        Pts03KeyAvailability.REPORTED ->
            when (val raw = distortionCorrectionMode?.raw) {
                0 -> Pts03EffectiveDistortionMode.Off
                1 -> Pts03EffectiveDistortionMode.Fast
                2 -> Pts03EffectiveDistortionMode.HighQuality
                null -> Pts03EffectiveDistortionMode.NotReported
                else -> Pts03EffectiveDistortionMode.UnknownValue(raw)
            }
    }

/** The coordinate basis Camera2 metadata (crop region, intrinsics-derived regions…) uses under a mode. */
internal enum class Pts03MetadataCoordinateBasis {
    PRE_CORRECTION_ACTIVE_ARRAY,
    ACTIVE_ARRAY,
    UNKNOWN,
}

/** Camera2 metadata contract only — says nothing about how much correction the pixels received. */
internal fun Pts03EffectiveDistortionMode.metadataCoordinateBasis(): Pts03MetadataCoordinateBasis =
    when (this) {
        Pts03EffectiveDistortionMode.Off -> Pts03MetadataCoordinateBasis.PRE_CORRECTION_ACTIVE_ARRAY
        Pts03EffectiveDistortionMode.Fast, Pts03EffectiveDistortionMode.HighQuality ->
            Pts03MetadataCoordinateBasis.ACTIVE_ARRAY
        is Pts03EffectiveDistortionMode.UnknownValue,
        Pts03EffectiveDistortionMode.NotReported,
        Pts03EffectiveDistortionMode.ApiUnsupported,
        Pts03EffectiveDistortionMode.PhysicalResultUnavailable,
        -> Pts03MetadataCoordinateBasis.UNKNOWN
    }

/** Requested versus effective, for one frame. */
internal data class Pts03DistortionModeObservation(
    val requested: Pts03DistortionModeRequest,
    val effective: Pts03EffectiveDistortionMode,
    /** `null` when nothing was requested (device default) or the effective mode is not a known value. */
    val requestedMatchesEffective: Boolean?,
    val metadataCoordinateBasis: Pts03MetadataCoordinateBasis,
)

internal fun observeDistortionMode(
    requested: Pts03DistortionModeRequest,
    effective: Pts03EffectiveDistortionMode,
): Pts03DistortionModeObservation {
    val effectiveRaw =
        when (effective) {
            Pts03EffectiveDistortionMode.Off -> 0
            Pts03EffectiveDistortionMode.Fast -> 1
            Pts03EffectiveDistortionMode.HighQuality -> 2
            else -> null
        }
    val matches = requested.camera2Value?.let { req -> effectiveRaw?.let { it == req } }
    return Pts03DistortionModeObservation(requested, effective, matches, effective.metadataCoordinateBasis())
}

/** Active versus pre-correction array for one camera — the relationship the intrinsics basis depends on. */
internal data class Pts03ArrayRelationship(
    val activeArray: Pts03IntRect?,
    val preCorrectionActiveArray: Pts03IntRect?,
    val pixelArrayWidthPx: Int?,
    val pixelArrayHeightPx: Int?,
    /** `null` when either array is missing. Equal arrays do **not** show the pixels are uncorrected. */
    val arraysIdentical: Boolean?,
    val activeOffsetInPreCorrectionLeftPx: Int?,
    val activeOffsetInPreCorrectionTopPx: Int?,
)

internal fun CameraCharacteristicsSnapshot.arrayRelationship(): Pts03ArrayRelationship {
    val active =
        if (activeArrayLeftPx != null && activeArrayTopPx != null && activeArrayRightPx != null &&
            activeArrayBottomPx != null
        ) {
            Pts03IntRect(activeArrayLeftPx, activeArrayTopPx, activeArrayRightPx, activeArrayBottomPx)
        } else {
            null
        }
    val pre =
        if (preCorrectionActiveArrayLeftPx != null && preCorrectionActiveArrayTopPx != null &&
            preCorrectionActiveArrayRightPx != null && preCorrectionActiveArrayBottomPx != null
        ) {
            Pts03IntRect(
                preCorrectionActiveArrayLeftPx,
                preCorrectionActiveArrayTopPx,
                preCorrectionActiveArrayRightPx,
                preCorrectionActiveArrayBottomPx,
            )
        } else {
            null
        }
    return Pts03ArrayRelationship(
        activeArray = active,
        preCorrectionActiveArray = pre,
        pixelArrayWidthPx = pixelArrayWidthPx,
        pixelArrayHeightPx = pixelArrayHeightPx,
        arraysIdentical = if (active != null && pre != null) active == pre else null,
        activeOffsetInPreCorrectionLeftPx = if (active != null && pre != null) active.left - pre.left else null,
        activeOffsetInPreCorrectionTopPx = if (active != null && pre != null) active.top - pre.top else null,
    )
}

// -------------------------------------------------------------------------------------------------
// Residual distortion measurement (printed target)
// -------------------------------------------------------------------------------------------------

/**
 * One printed-target point from one frozen frame: the detected position, the pinhole prediction under that
 * frame's own pose (pose-reference hypothesis), and the centre the radial trend is measured from.
 */
internal data class Pts03TargetResidualPoint(
    val placement: TargetPlacementLabel,
    val generation: Long,
    val observedXPx: Double,
    val observedYPx: Double,
    val predictedXPx: Double,
    val predictedYPx: Double,
    val centerXPx: Double,
    val centerYPx: Double,
    /** Half the buffer diagonal; radii are normalised by it so placements at different sizes are comparable. */
    val normalizationRadiusPx: Double,
)

/** Measurement thresholds. Defaults are conservative placeholders until Pixel 9 noise is measured. */
internal data class Pts03ResidualDistortionThresholds(
    val minPoints: Int = 30,
    val minPlacements: Int = 3,
    /** At least one point must reach this normalised radius (0 = centre, 1 = buffer corner). */
    val minCoverageNormalizedRadius: Double = 0.6,
    /** Smallest radial displacement at the outermost point that counts as measurable distortion. */
    val minDisplacementPx: Double = 1.0,
    /** Required significance (|b| / standard error of b) to call distortion measured. */
    val minSignificance: Double = 3.0,
)

internal enum class Pts03ResidualDistortionVerdict {
    /** A cubic radial residual above both the pixel floor and the significance bar. */
    RESIDUAL_DISTORTION_MEASURED,

    /**
     * Coverage is sufficient and even the upper bound of the cubic term stays below the pixel floor.
     * Observed, not proven.
     */
    NOT_DETECTED_AT_THRESHOLD,

    /** Too few points/placements, no edge coverage, or a fit that neither clears nor excludes the floor. */
    UNRESOLVED_INSUFFICIENT_EVIDENCE,
}

/**
 * Least-squares fit of the radial residual `d = a·ρ + b·ρ³` (`ρ` = normalised radius from the centre,
 * `d` = observed minus predicted, projected on the outward radial direction; positive = observed farther
 * out than the pinhole prediction).
 *
 * The linear term absorbs focal-scale error of the approximate pinhole K the pose was fit with; the cubic
 * term is the lowest-order lens-distortion signature. Because each frame's pose is fit through the same
 * pinhole model, part of any real distortion is absorbed into the pose — so this measurement can
 * under-report distortion, never invent it from a mode name.
 */
internal data class Pts03ResidualDistortionMeasurement(
    val pointCount: Int,
    val placements: List<TargetPlacementLabel>,
    val maxNormalizedRadius: Double?,
    val rmsResidualPx: Double?,
    val maxResidualPx: Double?,
    val linearCoefficientPx: Double?,
    val cubicCoefficientPx: Double?,
    val cubicStandardErrorPx: Double?,
    /** `b · ρmax³`: the cubic radial displacement at the outermost measured point. */
    val cubicDisplacementAtMaxRadiusPx: Double?,
    val fitResidualRmsPx: Double?,
    /** Mean radial residual in five normalised-radius bins [0,0.2) … [0.8,∞); `null` for an empty bin. */
    val meanRadialResidualByRadiusBinPx: List<Double?>,
    val thresholds: Pts03ResidualDistortionThresholds,
    val verdict: Pts03ResidualDistortionVerdict,
    val reason: String,
)

internal fun measurePts03ResidualDistortion(
    points: List<Pts03TargetResidualPoint>,
    thresholds: Pts03ResidualDistortionThresholds = Pts03ResidualDistortionThresholds(),
): Pts03ResidualDistortionMeasurement {
    val placements = points.map { it.placement }.distinct().sortedBy { it.ordinal }
    val samples =
        points.mapNotNull { p ->
            val rx = p.observedXPx - p.centerXPx
            val ry = p.observedYPx - p.centerYPx
            val r = hypot(rx, ry)
            if (r <= 0.0 || p.normalizationRadiusPx <= 0.0) return@mapNotNull null
            val dx = p.observedXPx - p.predictedXPx
            val dy = p.observedYPx - p.predictedYPx
            RadialSample(
                rho = r / p.normalizationRadiusPx,
                radialPx = (dx * rx + dy * ry) / r,
                euclideanPx = hypot(dx, dy),
            )
        }
    val bins = MutableList(5) { mutableListOf<Double>() }
    samples.forEach { bins[(it.rho / 0.2).toInt().coerceIn(0, 4)] += it.radialPx }
    val binMeans = bins.map { b -> if (b.isEmpty()) null else b.average() }

    if (samples.size < 2) {
        return Pts03ResidualDistortionMeasurement(
            pointCount = samples.size,
            placements = placements,
            maxNormalizedRadius = samples.maxOfOrNull { it.rho },
            rmsResidualPx = rms(samples.map { it.euclideanPx }),
            maxResidualPx = samples.maxOfOrNull { it.euclideanPx },
            linearCoefficientPx = null,
            cubicCoefficientPx = null,
            cubicStandardErrorPx = null,
            cubicDisplacementAtMaxRadiusPx = null,
            fitResidualRmsPx = null,
            meanRadialResidualByRadiusBinPx = binMeans,
            thresholds = thresholds,
            verdict = Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE,
            reason = "fewer than 2 usable points",
        )
    }

    // Normal equations for d = a·ρ + b·ρ³.
    var s11 = 0.0
    var s13 = 0.0
    var s33 = 0.0
    var t1 = 0.0
    var t3 = 0.0
    samples.forEach { s ->
        val r1 = s.rho
        val r3 = s.rho * s.rho * s.rho
        s11 += r1 * r1
        s13 += r1 * r3
        s33 += r3 * r3
        t1 += r1 * s.radialPx
        t3 += r3 * s.radialPx
    }
    val det = s11 * s33 - s13 * s13
    val maxRho = samples.maxOf { it.rho }
    val n = samples.size
    val (a, b, seB, fitRms) =
        if (abs(det) < 1e-12) {
            FitResult(null, null, null, null)
        } else {
            val aFit = (t1 * s33 - t3 * s13) / det
            val bFit = (s11 * t3 - s13 * t1) / det
            val sse = samples.sumOf { s -> (s.radialPx - aFit * s.rho - bFit * s.rho * s.rho * s.rho).let { it * it } }
            val dof = max(1, n - 2)
            val sigma2 = sse / dof
            FitResult(aFit, bFit, sqrt(sigma2 * s11 / det), sqrt(sse / n))
        }
    val cubicAtMax = b?.let { it * maxRho * maxRho * maxRho }
    val cubicSeAtMax = seB?.let { it * maxRho * maxRho * maxRho }

    val (verdict, reason) =
        when {
            n < thresholds.minPoints ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to "points=$n < ${thresholds.minPoints}"
            placements.size < thresholds.minPlacements ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to
                    "placements=${placements.size} < ${thresholds.minPlacements}"
            placements.all { it == TargetPlacementLabel.CENTER } ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to "no off-centre placement"
            maxRho < thresholds.minCoverageNormalizedRadius ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to
                    "max normalised radius $maxRho < ${thresholds.minCoverageNormalizedRadius}: edges not constrained"
            b == null || seB == null || cubicAtMax == null || cubicSeAtMax == null ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to "radial fit is degenerate"
            abs(cubicAtMax) >= thresholds.minDisplacementPx &&
                (seB == 0.0 || abs(b) / seB >= thresholds.minSignificance) ->
                Pts03ResidualDistortionVerdict.RESIDUAL_DISTORTION_MEASURED to
                    "cubic radial displacement ${"%.3f".format(cubicAtMax)} px at rho=${"%.3f".format(maxRho)} " +
                    "(significance ${if (seB == 0.0) "inf" else "%.1f".format(abs(b) / seB)})"
            abs(cubicAtMax) + thresholds.minSignificance * cubicSeAtMax < thresholds.minDisplacementPx ->
                Pts03ResidualDistortionVerdict.NOT_DETECTED_AT_THRESHOLD to
                    "upper bound |b·rho^3| + ${thresholds.minSignificance}·se = " +
                    "${"%.3f".format(abs(cubicAtMax) + thresholds.minSignificance * cubicSeAtMax)} px " +
                    "< ${thresholds.minDisplacementPx} px (observed, not proven; part of real distortion may be " +
                    "absorbed by per-frame pose)"
            else ->
                Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE to
                    "cubic displacement ${"%.3f".format(
                        cubicAtMax,
                    )} ± ${"%.3f".format(cubicSeAtMax)} px neither clears nor excludes the floor"
        }

    return Pts03ResidualDistortionMeasurement(
        pointCount = n,
        placements = placements,
        maxNormalizedRadius = maxRho,
        rmsResidualPx = rms(samples.map { it.euclideanPx }),
        maxResidualPx = samples.maxOf { it.euclideanPx },
        linearCoefficientPx = a,
        cubicCoefficientPx = b,
        cubicStandardErrorPx = seB,
        cubicDisplacementAtMaxRadiusPx = cubicAtMax,
        fitResidualRmsPx = fitRms,
        meanRadialResidualByRadiusBinPx = binMeans,
        thresholds = thresholds,
        verdict = verdict,
        reason = reason,
    )
}

private data class RadialSample(
    val rho: Double,
    val radialPx: Double,
    val euclideanPx: Double,
)

private data class FitResult(
    val a: Double?,
    val b: Double?,
    val seB: Double?,
    val fitRms: Double?,
)

private fun rms(values: List<Double>): Double? =
    if (values.isEmpty()) {
        null
    } else {
        sqrt(values.sumOf { it * it } / values.size)
    }

/**
 * What the measurement implies for any later geometry (PTS-09) — the double-correction rule, stated
 * per stream. Never "apply `LENS_DISTORTION`" by default.
 */
internal enum class Pts03ApplicationDistortionModelGuidance {
    /** Distortion remains in the YUV: later geometry must model it explicitly or reject calibrated projection. */
    MODEL_EXPLICITLY_OR_REJECT_CALIBRATED_PROJECTION,

    /** Nothing measurable remains at the threshold: do not re-apply `LENS_DISTORTION` to these pixels. */
    DO_NOT_REAPPLY_LENS_DISTORTION_AT_THIS_THRESHOLD,

    /** Not established: calibrated projection must not assume either answer. */
    UNRESOLVED,
}

internal fun Pts03ResidualDistortionVerdict.guidance(): Pts03ApplicationDistortionModelGuidance =
    when (this) {
        Pts03ResidualDistortionVerdict.RESIDUAL_DISTORTION_MEASURED ->
            Pts03ApplicationDistortionModelGuidance.MODEL_EXPLICITLY_OR_REJECT_CALIBRATED_PROJECTION
        Pts03ResidualDistortionVerdict.NOT_DETECTED_AT_THRESHOLD ->
            Pts03ApplicationDistortionModelGuidance.DO_NOT_REAPPLY_LENS_DISTORTION_AT_THIS_THRESHOLD
        Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE ->
            Pts03ApplicationDistortionModelGuidance.UNRESOLVED
    }

/**
 * The full Group C chain for one (camera, effective mode) stream:
 * requested → effective → metadata basis → arrays → intrinsics source basis → measured residual →
 * matrix domain → final analysis-buffer pixel domain.
 */
internal data class Pts03DistortionChain(
    val cameraId: String,
    val requested: Pts03DistortionModeRequest,
    val effectiveModes: List<String>,
    val metadataCoordinateBasis: Pts03MetadataCoordinateBasis,
    val arrays: Pts03ArrayRelationship?,
    /** Camera2 defines `LENS_INTRINSIC_CALIBRATION` in this basis regardless of mode. */
    val intrinsicCalibrationSourceBasis: Pts03MetadataCoordinateBasis,
    val availableModes: List<String>?,
    val residual: Pts03ResidualDistortionMeasurement,
    val guidance: Pts03ApplicationDistortionModelGuidance,
    val matrixDomainOutcome: Pts03EvidenceOutcome,
    /**
     * Established only when the matrix domain is PROVEN **and** the residual state is measured either way.
     * PTS-03 code cannot prove the domain, so this is UNRESOLVED for every stream it records.
     */
    val finalPixelDomainEstablished: Boolean,
    val finalPixelDomainNote: String,
)

internal fun buildPts03DistortionChain(
    cameraId: String,
    requested: Pts03DistortionModeRequest,
    effectiveModes: List<Pts03EffectiveDistortionMode>,
    characteristics: Pts03CameraStaticCharacteristics?,
    residual: Pts03ResidualDistortionMeasurement,
    matrixDomainOutcome: Pts03EvidenceOutcome,
): Pts03DistortionChain {
    val distinct = effectiveModes.distinct()
    val basis = distinct.singleOrNull()?.metadataCoordinateBasis() ?: Pts03MetadataCoordinateBasis.UNKNOWN
    val established =
        matrixDomainOutcome == Pts03EvidenceOutcome.PROVEN &&
            residual.verdict != Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE &&
            distinct.size == 1
    val note =
        when {
            established -> "matrix domain proven and residual distortion measured for a single effective mode"
            distinct.size > 1 -> "UNRESOLVED: effective mode changed within the stream (${distinct.map { it.label }})"
            matrixDomainOutcome != Pts03EvidenceOutcome.PROVEN ->
                "UNRESOLVED: sensor-to-buffer matrix domain is $matrixDomainOutcome (Group B); the buffer's pixel " +
                    "coordinate space cannot be tied to any sensor array basis yet"
            else -> "UNRESOLVED: residual distortion not established (${residual.reason})"
        }
    return Pts03DistortionChain(
        cameraId = cameraId,
        requested = requested,
        effectiveModes = distinct.map { it.label },
        metadataCoordinateBasis = basis,
        arrays = characteristics?.snapshot?.arrayRelationship(),
        intrinsicCalibrationSourceBasis = Pts03MetadataCoordinateBasis.PRE_CORRECTION_ACTIVE_ARRAY,
        availableModes = characteristics?.distortionCorrectionAvailableModes?.map { it.name },
        residual = residual,
        guidance = residual.verdict.guidance(),
        matrixDomainOutcome = matrixDomainOutcome,
        finalPixelDomainEstablished = established,
        finalPixelDomainNote = note,
    )
}
