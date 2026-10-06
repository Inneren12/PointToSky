package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.testkit.Angles.angularSeparationDeg
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import dev.pointtosky.core.astro.units.wrapDeg0To360
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Correctness oracle for [SimpleEphemerisComputer]. Every expected value here has external provenance;
 * none is produced by [SimpleEphemerisComputer], `ephem_golden_v1.json` or any helper that calls them.
 * `SimpleEphemerisComputerGoldenTest` is only a regression snapshot of this implementation.
 *
 * Conventions of the model under test (Paul Schlyter, "How to compute planetary positions",
 * https://stjarnhimlen.se/comp/tutorial.html):
 * - time scale: the UTC instant is used directly as the time argument (no ΔT applied);
 * - geocentric, not topocentric;
 * - geometric positions referred to the mean equator and mean equinox of date;
 * - no light-time, no aberration, no nutation, no precession to any other epoch.
 *
 * Reference sets:
 * 1. [SCHLYTER_1990] — Schlyter's own published worked example, 1990-04-19T00:00 UT, d = -3543.0.
 *    Same algorithm and same conventions as the model, so the tolerances only absorb the rounding of the
 *    published figures. Sun and Moon RA/Dec/distance are quoted directly. For Jupiter and Saturn Schlyter
 *    publishes heliocentric ecliptic longitude/latitude after perturbations and the unperturbed distance;
 *    [schlyterGeocentric] turns those into geocentric RA/Dec/distance with the procedure of tutorial §14,
 *    using the published Sun rectangular coordinates (x = 0.881048, y = 0.482098) and oblecl = 23.4406°.
 * 2. [HORIZONS_MODERN] — JPL Horizons (DE441; jup365/sat441 satellite solutions) geocentric GEOMETRIC
 *    state vectors (VEC_CORR='NONE': no light-time, no aberration), ICRF, TIME_TYPE='UT', retrieved
 *    2026-10-06 with:
 *    `https://ssd.jpl.nasa.gov/api/horizons.api?format=text&COMMAND='<10|301|599|699>'&EPHEM_TYPE='VECTORS'
 *    &CENTER='500@399'&REF_PLANE='FRAME'&REF_SYSTEM='ICRF'&VEC_CORR='NONE'&VEC_TABLE='2'&OUT_UNITS='AU-D'
 *    &CSV_FORMAT='YES'&TIME_TYPE='UT'&TLIST='2460676.5','2460827.5'`.
 *    [precessIcrfToMeanOfDate] rotates them to the mean equator/equinox of date with IAU 1976 precession
 *    (Lieske 1977; Meeus, Astronomical Algorithms, eq. 21.2–21.4), giving geometric, mean-of-date,
 *    geocentric positions — the model's conventions. No nutation or aberration is added because the model
 *    has none. The ICRF/FK5 frame bias (~0.02") is ignored. Remaining differences are the accuracy of the
 *    low-precision model itself plus ΔT ≈ 69 s, because Horizons evaluates the true UT instant while the
 *    model uses UT as its time argument.
 *
 * Tolerances are per reference and per body (see each [ReferenceCase]); they are tight enough that the
 * former one-day-early epoch `JD - 2451544.5` fails every angular check (see
 * `old JD 2451544_5 epoch would fail every reference case`).
 */
class SimpleEphemerisComputerReferenceTest {
    private val computer = SimpleEphemerisComputer()

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    fun `matches convention-matched external reference`(case: ReferenceCase) {
        val actual = computer.compute(case.body, case.instant)
        val separation = angularSeparationDeg(actual.eq.raDeg, actual.eq.decDeg, case.raDeg, case.decDeg)
        assertTrue(
            separation <= case.angleToleranceDeg,
            "$case: RA/Dec off by $separation° (tolerance ${case.angleToleranceDeg}°), " +
                "actual RA=${actual.eq.raDeg} Dec=${actual.eq.decDeg}",
        )
        val distance = requireNotNull(actual.distanceAu) { "${case.body} distance must be reported" }
        val distanceDelta = abs(distance - case.distanceAu)
        assertTrue(
            distanceDelta <= case.distanceToleranceAu,
            "$case: distance off by $distanceDelta AU (tolerance ${case.distanceToleranceAu} AU), actual=$distance",
        )
    }

    /**
     * Regression guard for the one-day epoch bug. The pre-PTS-01 code computed the Schlyter day number as
     * `JD - 2451544.5`, i.e. one day too small, which is the same as evaluating the model one day earlier
     * (the obliquity polynomial's 0.5-day difference is ~2e-7° and irrelevant here). If the epoch is ever
     * moved back to 2451544.5, `matches convention-matched external reference` goes red; this test proves
     * that every reference case is tight enough for that to happen.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    fun `old JD 2451544_5 epoch would fail every reference case`(case: ReferenceCase) {
        val oneDayEarly = computer.compute(case.body, case.instant.minus(Duration.ofDays(1)))
        val separation = angularSeparationDeg(oneDayEarly.eq.raDeg, oneDayEarly.eq.decDeg, case.raDeg, case.decDeg)
        assertTrue(
            separation > case.angleToleranceDeg,
            "$case: a one-day epoch shift is only $separation° away, inside tolerance ${case.angleToleranceDeg}°",
        )
    }

    @Test
    fun `all four bodies stay finite and canonical from 1990 to 2040`() {
        var instant = Instant.parse("1990-01-01T00:00:00Z")
        val end = Instant.parse("2040-01-01T00:00:00Z")
        while (instant <= end) {
            Body.entries.forEach { body ->
                val ephemeris = computer.compute(body, instant)
                val ra = ephemeris.eq.raDeg
                val dec = ephemeris.eq.decDeg
                assertTrue(ra.isFinite() && ra >= 0.0 && ra < 360.0, "$body @ $instant RA $ra")
                assertTrue(dec.isFinite() && dec >= -90.0 && dec <= 90.0, "$body @ $instant Dec $dec")
                val distance = requireNotNull(ephemeris.distanceAu) { "$body @ $instant distance" }
                assertTrue(distance.isFinite() && distance > 0.0, "$body @ $instant distance $distance")
                if (body == Body.MOON) {
                    val phase = requireNotNull(ephemeris.phase) { "Moon @ $instant phase" }
                    assertTrue(phase in 0.0..1.0, "Moon @ $instant phase $phase")
                }
            }
            instant = instant.plus(Duration.ofDays(29))
        }
    }

    data class ReferenceCase(
        val label: String,
        val body: Body,
        val instant: Instant,
        val raDeg: Double,
        val decDeg: Double,
        val distanceAu: Double,
        val angleToleranceDeg: Double,
        val distanceToleranceAu: Double,
    ) {
        override fun toString(): String = "$label $body @ $instant"
    }

    companion object {
        private val SCHLYTER_TEST_DATE: Instant = Instant.parse("1990-04-19T00:00:00Z")

        /**
         * Earth equatorial radius 6378.14 km (IAU 1976) over the IAU 2012 astronomical unit 149 597 870.7 km.
         * Only used to express Schlyter's lunar distance (published in Earth radii) in AU.
         */
        private const val EARTH_RADIUS_AU = 6378.14 / 149_597_870.7

        /**
         * Published values are given to 4 decimals (degrees); Schlyter's Moon perturbation sum adds twelve
         * terms each rounded to 0.0001°, and his oblecl formula (23.4393° - 3.563e-7° d) differs from the
         * model's J2000 polynomial by ~1e-5° at this date. 0.002° covers all of that; the old epoch is off by
         * 0.027° (Saturn) to 13° (Moon).
         */
        private const val SCHLYTER_ANGLE_TOL_DEG = 0.002

        /**
         * Schlyter 1990 worked example. Sun: tutorial §5 (RA 26.6580°, Decl +11.0084°, r 1.004323).
         * Moon: tutorial §7–8 after perturbations (RA 309.5011°, Decl -19.1032°, r 60.6779 Earth radii).
         * Jupiter/Saturn: tutorial §12 longitudes after perturbations (105.2423°, 289.3824°), Saturn
         * latitude after perturbations (+0.1845°), Jupiter latitude and both distances from the §11 table
         * (+0.1113°, 5.19508 AU, 10.06118 AU) — the model, like the tutorial, perturbs neither Jupiter's
         * latitude nor any distance.
         */
        private val SCHLYTER_1990: List<ReferenceCase> =
            run {
                val jupiter = schlyterGeocentric(105.2423, 0.1113, 5.19508)
                val saturn = schlyterGeocentric(289.3824, 0.1845, 10.06118)
                listOf(
                    ReferenceCase(
                        "Schlyter 1990",
                        Body.SUN,
                        SCHLYTER_TEST_DATE,
                        26.6580,
                        11.0084,
                        1.004323,
                        // Published to 6 decimals.
                        angleToleranceDeg = SCHLYTER_ANGLE_TOL_DEG,
                        distanceToleranceAu = 1e-5,
                    ),
                    ReferenceCase(
                        "Schlyter 1990",
                        Body.MOON,
                        SCHLYTER_TEST_DATE,
                        309.5011,
                        -19.1032,
                        60.6779 * EARTH_RADIUS_AU,
                        // 0.002 Earth radii: published to 4 decimals plus two rounded distance terms.
                        angleToleranceDeg = SCHLYTER_ANGLE_TOL_DEG,
                        distanceToleranceAu = 0.002 * EARTH_RADIUS_AU,
                    ),
                    ReferenceCase(
                        "Schlyter 1990",
                        Body.JUPITER,
                        SCHLYTER_TEST_DATE,
                        jupiter[0],
                        jupiter[1],
                        jupiter[2],
                        // Heliocentric distance published to 5 decimals.
                        angleToleranceDeg = SCHLYTER_ANGLE_TOL_DEG,
                        distanceToleranceAu = 5e-5,
                    ),
                    ReferenceCase(
                        "Schlyter 1990",
                        Body.SATURN,
                        SCHLYTER_TEST_DATE,
                        saturn[0],
                        saturn[1],
                        saturn[2],
                        angleToleranceDeg = SCHLYTER_ANGLE_TOL_DEG,
                        distanceToleranceAu = 5e-5,
                    ),
                )
            }

        /**
         * Sun: Schlyter's stated aim is 1' (0.0167°); plus ΔT ≈ 69 s of solar motion (0.0008°) → 0.02°.
         * One day of solar motion is ~1°.
         */
        private const val HORIZONS_SUN_TOL_DEG = 0.02

        /** Sun distance: Schlyter quotes an error of ~1/3 Earth radius (1.4e-5 AU); 1e-4 AU is a sanity bound. */
        private const val HORIZONS_SUN_DIST_TOL_AU = 1e-4

        /**
         * Moon: Schlyter quotes 1–2' with all perturbation terms, but omitted terms below 0.01° add up, and
         * ΔT ≈ 69 s is 0.01° of lunar motion → 0.1°. One day of lunar motion is ~13°.
         */
        private const val HORIZONS_MOON_TOL_DEG = 0.1

        /** Moon distance: largest omitted distance terms are ~0.05 Earth radii each; 1e-5 AU ≈ 0.23 R⊕. */
        private const val HORIZONS_MOON_DIST_TOL_AU = 1e-5

        /**
         * Jupiter/Saturn: Schlyter's stated accuracy with the Jupiter–Saturn perturbations is 1–2' → 2'.
         * One day of geocentric motion at these dates is 0.07°–0.22°.
         */
        private const val HORIZONS_PLANET_TOL_DEG = 2.0 / 60.0

        /**
         * The model ignores perturbations in planetary distance (as does the tutorial); Saturn's residual is
         * ~0.023 AU at these dates. 0.05 AU is a sanity bound only — the angular check carries the epoch guard.
         */
        private const val HORIZONS_PLANET_DIST_TOL_AU = 0.05

        private val HORIZONS_MODERN: List<ReferenceCase> =
            listOf(
                horizons(
                    "2025-01-01T00:00:00Z",
                    Body.SUN,
                    1.786972174739682E-01,
                    -8.872073469436281E-01,
                    -3.845957709064275E-01,
                    HORIZONS_SUN_TOL_DEG,
                    HORIZONS_SUN_DIST_TOL_AU,
                ),
                horizons(
                    "2025-01-01T00:00:00Z",
                    Body.MOON,
                    1.016838540377818E-03,
                    -2.057491450962352E-03,
                    -1.115424714693417E-03,
                    HORIZONS_MOON_TOL_DEG,
                    HORIZONS_MOON_DIST_TOL_AU,
                ),
                horizons(
                    "2025-01-01T00:00:00Z",
                    Body.JUPITER,
                    1.234724776500846E+00,
                    3.691624999537721E+00,
                    1.552310731662031E+00,
                    HORIZONS_PLANET_TOL_DEG,
                    HORIZONS_PLANET_DIST_TOL_AU,
                ),
                horizons(
                    "2025-01-01T00:00:00Z",
                    Body.SATURN,
                    9.639765057003311E+00,
                    -2.368623907306988E+00,
                    -1.403852935584453E+00,
                    HORIZONS_PLANET_TOL_DEG,
                    HORIZONS_PLANET_DIST_TOL_AU,
                ),
                horizons(
                    "2025-06-01T00:00:00Z",
                    Body.SUN,
                    3.397796721376937E-01,
                    8.765323851043787E-01,
                    3.799611860864006E-01,
                    HORIZONS_SUN_TOL_DEG,
                    HORIZONS_SUN_DIST_TOL_AU,
                ),
                horizons(
                    "2025-06-01T00:00:00Z",
                    Body.MOON,
                    -1.815755758960384E-03,
                    1.601946114112616E-03,
                    8.479410413759879E-04,
                    HORIZONS_MOON_TOL_DEG,
                    HORIZONS_MOON_DIST_TOL_AU,
                ),
                horizons(
                    "2025-06-01T00:00:00Z",
                    Body.JUPITER,
                    2.496793955464665E-01,
                    5.594137595135181E+00,
                    2.404248059850872E+00,
                    HORIZONS_PLANET_TOL_DEG,
                    HORIZONS_PLANET_DIST_TOL_AU,
                ),
                horizons(
                    "2025-06-01T00:00:00Z",
                    Body.SATURN,
                    9.871873067693031E+00,
                    1.656906526162244E-01,
                    -3.240894471042294E-01,
                    HORIZONS_PLANET_TOL_DEG,
                    HORIZONS_PLANET_DIST_TOL_AU,
                ),
            )

        @JvmStatic
        fun referenceCases(): List<ReferenceCase> = SCHLYTER_1990 + HORIZONS_MODERN

        /**
         * Tutorial §14: geocentric = heliocentric planet + Sun (both ecliptic rectangular, epoch of date),
         * then rotate about X by oblecl. Inputs are Schlyter's published 1990-04-19 values.
         * Returns [RA deg, Dec deg, distance AU].
         */
        private fun schlyterGeocentric(
            lonDeg: Double,
            latDeg: Double,
            radiusAu: Double,
        ): DoubleArray {
            val lon = degToRad(lonDeg)
            val lat = degToRad(latDeg)
            val x = radiusAu * cos(lon) * cos(lat) + 0.881048
            val y = radiusAu * sin(lon) * cos(lat) + 0.482098
            val z = radiusAu * sin(lat)
            val obliquity = degToRad(23.4406)
            val yEq = y * cos(obliquity) - z * sin(obliquity)
            val zEq = y * sin(obliquity) + z * cos(obliquity)
            return doubleArrayOf(
                wrapDeg0To360(radToDeg(atan2(yEq, x))),
                radToDeg(atan2(zEq, sqrt(x * x + yEq * yEq))),
                sqrt(x * x + y * y + z * z),
            )
        }

        private fun horizons(
            utc: String,
            body: Body,
            x: Double,
            y: Double,
            z: Double,
            angleToleranceDeg: Double,
            distanceToleranceAu: Double,
        ): ReferenceCase {
            val instant = Instant.parse(utc)
            val julianDay = 2_440_587.5 + instant.epochSecond / 86_400.0
            val ofDate = precessIcrfToMeanOfDate(x, y, z, julianDay)
            return ReferenceCase(
                "Horizons geometric, precessed to mean of date",
                body,
                instant,
                ofDate[0],
                ofDate[1],
                ofDate[2],
                angleToleranceDeg,
                distanceToleranceAu,
            )
        }

        /**
         * IAU 1976 precession (Lieske 1977) from J2000.0 to the mean equator/equinox of [julianDay];
         * Meeus, Astronomical Algorithms, eq. 21.2 (ζ, z, θ) and 21.4. Returns [RA deg, Dec deg, distance].
         */
        private fun precessIcrfToMeanOfDate(
            x: Double,
            y: Double,
            z: Double,
            julianDay: Double,
        ): DoubleArray {
            val t = (julianDay - 2_451_545.0) / 36_525.0
            val zeta = degToRad((2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0)
            val zed = degToRad((2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0)
            val theta = degToRad((2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0)
            val r = sqrt(x * x + y * y + z * z)
            val ra0 = atan2(y, x)
            val dec0 = asin(z / r)
            val a = cos(dec0) * sin(ra0 + zeta)
            val b = cos(theta) * cos(dec0) * cos(ra0 + zeta) - sin(theta) * sin(dec0)
            val c = sin(theta) * cos(dec0) * cos(ra0 + zeta) + cos(theta) * sin(dec0)
            return doubleArrayOf(
                wrapDeg0To360(radToDeg(atan2(a, b) + zed)),
                radToDeg(asin(c)),
                r,
            )
        }
    }
}
