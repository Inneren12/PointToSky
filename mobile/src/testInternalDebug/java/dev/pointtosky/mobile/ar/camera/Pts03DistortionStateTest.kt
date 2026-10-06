package dev.pointtosky.mobile.ar.camera

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PTS-03 Group C: requested vs effective mode, the metadata basis each mode implies, and a residual
 * measurement that decides "distortion remains" from pixels — never from the mode name.
 */
class Pts03DistortionStateTest {
    private fun truthWithMode(mode: Int?): Pts03CaptureTruth =
        pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues(distortionMode = mode)), 35)

    @Test
    fun `OFF FAST and HIGH_QUALITY map to their metadata coordinate bases`() {
        assertEquals(Pts03EffectiveDistortionMode.Off, truthWithMode(0).effectiveDistortionMode())
        assertEquals(
            Pts03MetadataCoordinateBasis.PRE_CORRECTION_ACTIVE_ARRAY,
            Pts03EffectiveDistortionMode.Off.metadataCoordinateBasis(),
        )
        assertEquals(Pts03EffectiveDistortionMode.Fast, truthWithMode(1).effectiveDistortionMode())
        assertEquals(
            Pts03MetadataCoordinateBasis.ACTIVE_ARRAY,
            Pts03EffectiveDistortionMode.Fast.metadataCoordinateBasis(),
        )
        assertEquals(Pts03EffectiveDistortionMode.HighQuality, truthWithMode(2).effectiveDistortionMode())
        assertEquals(
            Pts03MetadataCoordinateBasis.ACTIVE_ARRAY,
            Pts03EffectiveDistortionMode.HighQuality.metadataCoordinateBasis(),
        )
    }

    @Test
    fun `unavailable or null mode has an UNKNOWN basis, never assumed OFF`() {
        assertEquals(Pts03EffectiveDistortionMode.NotReported, truthWithMode(null).effectiveDistortionMode())
        assertEquals(
            Pts03MetadataCoordinateBasis.UNKNOWN,
            Pts03EffectiveDistortionMode.NotReported.metadataCoordinateBasis(),
        )
        assertEquals(
            Pts03MetadataCoordinateBasis.UNKNOWN,
            Pts03EffectiveDistortionMode.ApiUnsupported.metadataCoordinateBasis(),
        )
    }

    @Test
    fun `requested and effective are kept apart, including a request the device did not honour`() {
        val honoured =
            observeDistortionMode(Pts03DistortionModeRequest.HIGH_QUALITY, Pts03EffectiveDistortionMode.HighQuality)
        assertEquals(true, honoured.requestedMatchesEffective)

        val notHonoured = observeDistortionMode(Pts03DistortionModeRequest.OFF, Pts03EffectiveDistortionMode.Fast)
        assertEquals(false, notHonoured.requestedMatchesEffective)
        assertEquals(
            Pts03MetadataCoordinateBasis.ACTIVE_ARRAY,
            notHonoured.metadataCoordinateBasis,
            "basis follows the effective mode",
        )

        assertNull(
            observeDistortionMode(
                Pts03DistortionModeRequest.DEVICE_DEFAULT,
                Pts03EffectiveDistortionMode.Fast,
            ).requestedMatchesEffective,
        )
        assertNull(
            observeDistortionMode(
                Pts03DistortionModeRequest.FAST,
                Pts03EffectiveDistortionMode.NotReported,
            ).requestedMatchesEffective,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Residual measurement
    // -----------------------------------------------------------------------------------------

    private val centerX = 320.0
    private val centerY = 240.0
    private val halfDiagonal = hypot(640.0, 480.0) / 2

    /** A ring of points per placement with an injected radial residual `k3 · ρ³` px (observed − predicted). */
    private fun points(
        placements: List<TargetPlacementLabel>,
        cubicPx: Double,
        rhos: List<Double> = listOf(0.1, 0.25, 0.4, 0.55, 0.7, 0.85),
        noiseSeed: Int = 1,
    ): List<Pts03TargetResidualPoint> {
        var state = noiseSeed

        fun noise(): Double {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            return (state % 1000) / 1000.0 * 0.2 - 0.1
        }
        return placements.flatMapIndexed { pi, placement ->
            rhos.flatMap { rho ->
                (0 until 4).map { k ->
                    val theta = Math.toRadians(45.0 + 90.0 * k + 10.0 * pi)
                    val r = rho * halfDiagonal
                    val px = centerX + r * cos(theta)
                    val py = centerY + r * sin(theta)
                    val radial = cubicPx * rho * rho * rho + noise()
                    Pts03TargetResidualPoint(
                        placement = placement,
                        generation = pi.toLong(),
                        observedXPx = px + radial * cos(theta),
                        observedYPx = py + radial * sin(theta),
                        predictedXPx = px,
                        predictedYPx = py,
                        centerXPx = centerX,
                        centerYPx = centerY,
                        normalizationRadiusPx = halfDiagonal,
                    )
                }
            }
        }
    }

    private val allPlacements = TargetPlacementLabel.values().toList()

    @Test
    fun `an injected cubic radial residual is measured as residual distortion`() {
        val m = measurePts03ResidualDistortion(points(allPlacements, cubicPx = 6.0))
        assertEquals(Pts03ResidualDistortionVerdict.RESIDUAL_DISTORTION_MEASURED, m.verdict, m.reason)
        assertEquals(6.0, m.cubicCoefficientPx!!, 0.3)
        assertEquals(
            Pts03ApplicationDistortionModelGuidance.MODEL_EXPLICITLY_OR_REJECT_CALIBRATED_PROJECTION,
            m.verdict.guidance(),
        )
    }

    @Test
    fun `no residual with edge coverage is NOT_DETECTED_AT_THRESHOLD, which is observed and not proven`() {
        val m = measurePts03ResidualDistortion(points(allPlacements, cubicPx = 0.0))
        assertEquals(Pts03ResidualDistortionVerdict.NOT_DETECTED_AT_THRESHOLD, m.verdict, m.reason)
        assertEquals(
            Pts03ApplicationDistortionModelGuidance.DO_NOT_REAPPLY_LENS_DISTORTION_AT_THIS_THRESHOLD,
            m.verdict.guidance(),
        )
    }

    @Test
    fun `centre-only, too few points or no edge coverage is unresolved`() {
        val centreOnly = measurePts03ResidualDistortion(points(List(5) { TargetPlacementLabel.CENTER }, cubicPx = 6.0))
        assertEquals(Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE, centreOnly.verdict)

        val few = measurePts03ResidualDistortion(points(allPlacements, cubicPx = 6.0).take(10))
        assertEquals(Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE, few.verdict)

        val noEdges = measurePts03ResidualDistortion(points(allPlacements, cubicPx = 6.0, rhos = listOf(0.1, 0.2, 0.3)))
        assertEquals(Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE, noEdges.verdict)
        assertEquals(Pts03ApplicationDistortionModelGuidance.UNRESOLVED, noEdges.verdict.guidance())
    }

    @Test
    fun `a pure focal-scale error is absorbed by the linear term, not reported as distortion`() {
        val linearOnly =
            points(allPlacements, cubicPx = 0.0).map { p ->
                val dx = p.predictedXPx - p.centerXPx
                val dy = p.predictedYPx - p.centerYPx
                p.copy(observedXPx = p.observedXPx + 0.02 * dx, observedYPx = p.observedYPx + 0.02 * dy)
            }
        val m = measurePts03ResidualDistortion(linearOnly)
        assertFalse(m.verdict == Pts03ResidualDistortionVerdict.RESIDUAL_DISTORTION_MEASURED, m.reason)
        assertTrue(m.linearCoefficientPx!! > 3.0)
    }

    @Test
    fun `the FAST mode name never establishes the final pixel domain`() {
        val residual = measurePts03ResidualDistortion(points(allPlacements, cubicPx = 0.0))
        val chain =
            buildPts03DistortionChain(
                cameraId = "3",
                requested = Pts03DistortionModeRequest.FAST,
                effectiveModes = listOf(Pts03EffectiveDistortionMode.Fast),
                characteristics = Pts03Fixtures.characteristicsSet().forCameraId("3"),
                residual = residual,
                matrixDomainOutcome = Pts03EvidenceOutcome.UNRESOLVED,
            )
        assertFalse(chain.finalPixelDomainEstablished)
        assertTrue(chain.finalPixelDomainNote.startsWith("UNRESOLVED"))
        assertEquals(Pts03MetadataCoordinateBasis.PRE_CORRECTION_ACTIVE_ARRAY, chain.intrinsicCalibrationSourceBasis)
        assertEquals(false, chain.arrays?.arraysIdentical)
        assertEquals(8, chain.arrays?.activeOffsetInPreCorrectionLeftPx)
    }

    @Test
    fun `a stream whose effective mode changed is never a single established pixel domain`() {
        val chain =
            buildPts03DistortionChain(
                cameraId = "3",
                requested = Pts03DistortionModeRequest.DEVICE_DEFAULT,
                effectiveModes = listOf(Pts03EffectiveDistortionMode.Fast, Pts03EffectiveDistortionMode.Off),
                characteristics = null,
                residual = measurePts03ResidualDistortion(points(allPlacements, 0.0)),
                matrixDomainOutcome = Pts03EvidenceOutcome.PROVEN,
            )
        assertFalse(chain.finalPixelDomainEstablished)
        assertEquals(Pts03MetadataCoordinateBasis.UNKNOWN, chain.metadataCoordinateBasis)
    }
}
