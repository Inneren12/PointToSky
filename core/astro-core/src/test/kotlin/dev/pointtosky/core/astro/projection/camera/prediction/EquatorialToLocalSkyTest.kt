package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.units.wrapDeg0To360
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Section A anchors: local-sky astronomy. These reuse the project's existing, already-tested
 * [lstAt]/[dev.pointtosky.core.astro.transform.raDecToAltAz] only to *construct* deterministic
 * scenarios (e.g. "what RA puts this star on the meridian right now") — the asserted expectations
 * themselves are independent, hand-derivable geometric facts (zenith is `(0,0,1)`; a pole-adjacent
 * star's altitude equals the observer's latitude), never a value read back from
 * [equatorialToLocalSky]/[localSkyDirectionFromHorizontal] itself.
 */
class EquatorialToLocalSkyTest {
    private val eps = 1e-6

    private fun assertVectorEquals(
        expectedX: Double,
        expectedY: Double,
        expectedZ: Double,
        actual: LocalSkyDirection,
        message: String,
        tolerance: Double = eps,
    ) {
        assertTrue(abs(actual.x - expectedX) < tolerance, "$message: x expected $expectedX but was ${actual.x}")
        assertTrue(abs(actual.y - expectedY) < tolerance, "$message: y expected $expectedY but was ${actual.y}")
        assertTrue(abs(actual.z - expectedZ) < tolerance, "$message: z expected $expectedZ but was ${actual.z}")
    }

    // --- localSkyDirectionFromHorizontal: cardinal anchors -------------------------------------------

    @Test
    fun `cardinal directions pin the ENU embedding`() {
        assertVectorEquals(0.0, 1.0, 0.0, localSkyDirectionFromHorizontal(0.0, 0.0), "north horizon")
        assertVectorEquals(1.0, 0.0, 0.0, localSkyDirectionFromHorizontal(PI / 2.0, 0.0), "east horizon")
        assertVectorEquals(0.0, -1.0, 0.0, localSkyDirectionFromHorizontal(PI, 0.0), "south horizon")
        assertVectorEquals(-1.0, 0.0, 0.0, localSkyDirectionFromHorizontal(3.0 * PI / 2.0, 0.0), "west horizon")
        assertVectorEquals(0.0, 0.0, 1.0, localSkyDirectionFromHorizontal(0.0, PI / 2.0), "zenith at az=0")
        assertVectorEquals(0.0, 0.0, 1.0, localSkyDirectionFromHorizontal(2.0, PI / 2.0), "zenith is az-independent")
    }

    // --- equatorialToLocalSky: zenith when LST == RA and dec == latitude -----------------------------

    @Test
    fun `star at LST equal to RA and dec equal to latitude is at zenith`() {
        val instant = Instant.parse("2024-06-21T00:00:00Z")
        val lonDeg = 30.0
        val latDeg = 40.0
        val lstDeg = lstAt(instant, lonDeg).lstDeg

        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Math.toRadians(lstDeg), declinationRad = Math.toRadians(latDeg))
        val context = StarProjectionContext(latitudeRad = Math.toRadians(latDeg), longitudeRad = Math.toRadians(lonDeg), utcEpochMillis = instant.toEpochMilli())

        val sky = equatorialToLocalSky(star, context)
        assertVectorEquals(0.0, 0.0, 1.0, sky, "zenith when LST == RA and dec == lat")
    }

    // --- equatorialToLocalSky: declination-zero star on the meridian at the equator ------------------

    @Test
    fun `declination-zero star on the meridian at the equator reaches zenith`() {
        val instant = Instant.parse("2024-03-20T12:00:00Z")
        val lonDeg = 10.0
        val lstDeg = lstAt(instant, lonDeg).lstDeg

        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Math.toRadians(lstDeg), declinationRad = 0.0)
        val context = StarProjectionContext(latitudeRad = 0.0, longitudeRad = Math.toRadians(lonDeg), utcEpochMillis = instant.toEpochMilli())

        val sky = equatorialToLocalSky(star, context)
        assertVectorEquals(0.0, 0.0, 1.0, sky, "equatorial dec=0 star transiting the meridian at the equator")
    }

    // --- equatorialToLocalSky: east/west hour-angle sign ---------------------------------------------

    @Test
    fun `negative hour angle (star east of meridian) puts it on the eastern horizon`() {
        val instant = Instant.parse("2024-01-01T06:00:00Z")
        val lstDeg = lstAt(instant, 0.0).lstDeg
        // tau = LST - RA = -90 deg => RA = LST + 90 deg.
        val raDeg = wrapDeg0To360(lstDeg + 90.0)

        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Math.toRadians(raDeg), declinationRad = 0.0)
        val context = StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = instant.toEpochMilli())

        val sky = equatorialToLocalSky(star, context)
        assertVectorEquals(1.0, 0.0, 0.0, sky, "tau=-90 deg at the equator must be due east, on the horizon")
    }

    @Test
    fun `positive hour angle (star west of meridian) puts it on the western horizon`() {
        val instant = Instant.parse("2024-01-01T06:00:00Z")
        val lstDeg = lstAt(instant, 0.0).lstDeg
        // tau = LST - RA = +90 deg => RA = LST - 90 deg.
        val raDeg = wrapDeg0To360(lstDeg - 90.0)

        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Math.toRadians(raDeg), declinationRad = 0.0)
        val context = StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = instant.toEpochMilli())

        val sky = equatorialToLocalSky(star, context)
        assertVectorEquals(-1.0, 0.0, 0.0, sky, "tau=+90 deg at the equator must be due west, on the horizon")
    }

    // --- equatorialToLocalSky: longitude is east-positive --------------------------------------------

    @Test
    fun `increasing StarProjectionContext longitude (further east) advances local sidereal time`() {
        val instant = Instant.parse("2024-05-05T05:00:00Z")
        val lst0Deg = lstAt(instant, 0.0).lstDeg
        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Math.toRadians(lst0Deg), declinationRad = 0.0)

        val greenwich = StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = instant.toEpochMilli())
        val fifteenEast = StarProjectionContext(latitudeRad = 0.0, longitudeRad = Math.toRadians(15.0), utcEpochMillis = instant.toEpochMilli())

        val skyGreenwich = equatorialToLocalSky(star, greenwich)
        assertVectorEquals(0.0, 0.0, 1.0, skyGreenwich, "reference star is at zenith at longitude 0")

        // At the equator with dec=0, hour angle tau=+15 deg puts the star exactly due west at
        // altitude 90-15=75 deg (the celestial equator is the east-west great circle through the
        // zenith there, so altitude, not azimuth, absorbs a small hour-angle offset).
        val skyEast = equatorialToLocalSky(star, fifteenEast)
        val expectedAlt = Math.toRadians(75.0)
        assertVectorEquals(
            expectedX = -cos(expectedAlt),
            expectedY = 0.0,
            expectedZ = sin(expectedAlt),
            actual = skyEast,
            message = "15 deg further east must advance LST by 15 deg (east-positive), shifting the star west",
        )
    }

    // --- EquatorialStarDirection.of: RA wraparound produces continuous geometry ----------------------

    @Test
    fun `RA wraparound around a multiple of 2π yields the same local sky direction`() {
        val context = StarProjectionContext(latitudeRad = Math.toRadians(20.0), longitudeRad = Math.toRadians(-40.0), utcEpochMillis = 1_700_000_000_000L)

        val starA = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.3, declinationRad = 0.2)
        val starB = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.3 + 3.0 * 2.0 * PI, declinationRad = 0.2)

        val skyA = equatorialToLocalSky(starA, context)
        val skyB = equatorialToLocalSky(starB, context)
        assertVectorEquals(skyA.x, skyA.y, skyA.z, skyB, "RA wrapped by multiple full turns must project identically", tolerance = 1e-9)
    }

    @Test
    fun `RA values straddling the 0,2π seam project to nearly the same local sky direction`() {
        val context = StarProjectionContext(latitudeRad = Math.toRadians(10.0), longitudeRad = 0.0, utcEpochMillis = 1_700_000_000_000L)

        val justBelowZero = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = -0.0005, declinationRad = 0.1)
        val justAboveZero = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0005, declinationRad = 0.1)

        val skyBelow = equatorialToLocalSky(justBelowZero, context)
        val skyAbove = equatorialToLocalSky(justAboveZero, context)

        assertTrue(abs(skyBelow.x - skyAbove.x) < 0.01, "x must be continuous across the RA wraparound seam")
        assertTrue(abs(skyBelow.y - skyAbove.y) < 0.01, "y must be continuous across the RA wraparound seam")
        assertTrue(abs(skyBelow.z - skyAbove.z) < 0.01, "z must be continuous across the RA wraparound seam")
    }

    // --- pole-adjacent declinations -------------------------------------------------------------------

    @Test
    fun `a star at the north celestial pole is always at altitude equal to latitude`() {
        val latDeg = 51.5
        val latRad = Math.toRadians(latDeg)
        val expectedZ = sin(latRad)

        val instantA = Instant.parse("2024-02-02T02:00:00Z")
        val instantB = Instant.parse("2024-09-09T18:30:00Z")
        val contextA = StarProjectionContext(latitudeRad = latRad, longitudeRad = 0.3, utcEpochMillis = instantA.toEpochMilli())
        val contextB = StarProjectionContext(latitudeRad = latRad, longitudeRad = -1.1, utcEpochMillis = instantB.toEpochMilli())

        val starRaA = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 1.234, declinationRad = PI / 2.0)
        val starRaB = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 4.321, declinationRad = PI / 2.0)

        val skyA = equatorialToLocalSky(starRaA, contextA)
        val skyB = equatorialToLocalSky(starRaB, contextB)

        assertTrue(abs(skyA.z - expectedZ) < eps, "NCP altitude must equal latitude regardless of RA/time: A z=${skyA.z}")
        assertTrue(abs(skyB.z - expectedZ) < eps, "NCP altitude must equal latitude regardless of RA/time: B z=${skyB.z}")
    }

    @Test
    fun `a star at the south celestial pole is always at altitude equal to minus latitude`() {
        val latDeg = 51.5
        val latRad = Math.toRadians(latDeg)
        val expectedZ = sin(-latRad)

        val instant = Instant.parse("2024-02-02T02:00:00Z")
        val context = StarProjectionContext(latitudeRad = latRad, longitudeRad = 0.3, utcEpochMillis = instant.toEpochMilli())
        val star = EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 1.234, declinationRad = -PI / 2.0)

        val sky = equatorialToLocalSky(star, context)
        assertTrue(abs(sky.z - expectedZ) < eps, "SCP altitude must equal minus latitude: z=${sky.z}")
    }

    // --- finiteness and unit length --------------------------------------------------------------------

    @Test
    fun `local sky vectors are always finite and unit length`() {
        val raValues = listOf(0.0, 0.5, 1.5, 3.0, 4.7, 6.28)
        val decValues = listOf(-1.5, -0.7, 0.0, 0.7, 1.5)
        val latValues = listOf(-1.5, -0.5, 0.0, 0.5, 1.5)
        val lonValues = listOf(-3.0, -0.5, 0.0, 1.2, 3.0)
        val epochValues = listOf(0L, 1_000_000_000L, 1_700_000_000_000L)

        for (ra in raValues) {
            for (dec in decValues) {
                for (lat in latValues) {
                    for (lon in lonValues) {
                        for (epoch in epochValues) {
                            val star = EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = ra, declinationRad = dec)
                            val context = StarProjectionContext.of(latitudeRad = lat, longitudeRad = lon, utcEpochMillis = epoch)
                            val sky = equatorialToLocalSky(star, context)
                            assertTrue(sky.x.isFinite() && sky.y.isFinite() && sky.z.isFinite(), "non-finite result for ra=$ra dec=$dec lat=$lat lon=$lon epoch=$epoch")
                            val norm = sqrt(sky.x * sky.x + sky.y * sky.y + sky.z * sky.z)
                            assertTrue(abs(norm - 1.0) < 1e-6, "non-unit-length result ($norm) for ra=$ra dec=$dec lat=$lat lon=$lon epoch=$epoch")
                        }
                    }
                }
            }
        }
    }
}
