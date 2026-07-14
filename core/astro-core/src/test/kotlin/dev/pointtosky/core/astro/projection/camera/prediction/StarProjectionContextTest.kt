package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StarProjectionContextTest {
    private val eps = 1e-9

    // --- of(): the only construction path; every stored longitude is canonical [-π, π) --------------

    @Test
    fun `non-finite latitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = Double.NaN, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `non-finite longitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = Double.POSITIVE_INFINITY, utcEpochMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = Double.NEGATIVE_INFINITY, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `latitude outside -π,2,π,2 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = PI / 2.0 + 0.001, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = -PI / 2.0 - 0.001, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `latitude exactly at the poles is accepted`() {
        StarProjectionContext.of(latitudeRad = PI / 2.0, longitudeRad = 0.0, utcEpochMillis = 0L)
        StarProjectionContext.of(latitudeRad = -PI / 2.0, longitudeRad = 0.0, utcEpochMillis = 0L)
    }

    @Test
    fun `any long is accepted for utcEpochMillis including negative`() {
        StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = Long.MIN_VALUE)
        StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = Long.MAX_VALUE)
        StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = -1L)
    }

    // --- of(): normalizes longitude into [-π, π) ------------------------------------------------------

    @Test
    fun `of wraps positive π to negative π`() {
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = PI, utcEpochMillis = 0L)
        assertTrue(abs(context.longitudeRad - (-PI)) < eps)
    }

    @Test
    fun `of wraps a longitude slightly beyond π back near minus π`() {
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = PI + 0.3, utcEpochMillis = 0L)
        assertTrue(abs(context.longitudeRad - (-PI + 0.3)) < eps)
    }

    @Test
    fun `of leaves an already-canonical longitude unchanged`() {
        val context = StarProjectionContext.of(latitudeRad = 0.1, longitudeRad = -1.5, utcEpochMillis = 12345L)
        assertTrue(abs(context.longitudeRad - (-1.5)) < eps)
    }

    @Test
    fun `of rejects non-finite longitude before wrapping`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = Double.NaN, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `of preserves latitude and utcEpochMillis`() {
        val context = StarProjectionContext.of(latitudeRad = 0.5, longitudeRad = 0.1, utcEpochMillis = 999L)
        assertEquals(0.5, context.latitudeRad)
        assertEquals(999L, context.utcEpochMillis)
    }

    @Test
    fun `of handles many-turn longitudes without precision loss beyond floating tolerance`() {
        val manyTurns = 1000.0 * 2.0 * PI + 0.4
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = manyTurns, utcEpochMillis = 0L)
        assertTrue(abs(context.longitudeRad - 0.4) < 1e-6)
    }

    @Test
    fun `of applied to equivalent canonical longitude values (many turns apart) produces equal instances`() {
        val a = StarProjectionContext.of(latitudeRad = 0.2, longitudeRad = -0.9, utcEpochMillis = 42L)
        val b = StarProjectionContext.of(latitudeRad = 0.2, longitudeRad = -0.9 + 4.0 * 2.0 * PI, utcEpochMillis = 42L)
        assertTrue(abs(a.longitudeRad - b.longitudeRad) < 1e-9)
        assertEquals(a.latitudeRad, b.latitudeRad)
        assertEquals(a.utcEpochMillis, b.utcEpochMillis)
    }

    @Test
    fun `copy is not part of the public API`() {
        // StarProjectionContext is @ConsistentCopyVisibility + `private constructor`, so both the
        // constructor and the generated copy() are private to this file — there is no public path
        // (direct construction or copy()) that can produce a noncanonical longitude. This test exists
        // to document that guarantee; the actual enforcement is compile-time (see the class KDoc).
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.5, utcEpochMillis = 0L)
        assertTrue(abs(context.longitudeRad - 0.5) < eps)
    }

    // --- magneticDeclinationRad: defaults, validation, and canonical wrap -----------------------------

    @Test
    fun `magneticDeclinationRad defaults to 0,0 - the explicit uncorrected mode`() {
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L)
        assertEquals(0.0, context.magneticDeclinationRad)
    }

    @Test
    fun `non-finite magneticDeclinationRad is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L, magneticDeclinationRad = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(
                latitudeRad = 0.0,
                longitudeRad = 0.0,
                utcEpochMillis = 0L,
                magneticDeclinationRad = Double.POSITIVE_INFINITY,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext.of(
                latitudeRad = 0.0,
                longitudeRad = 0.0,
                utcEpochMillis = 0L,
                magneticDeclinationRad = Double.NEGATIVE_INFINITY,
            )
        }
    }

    @Test
    fun `an already-canonical magneticDeclinationRad is preserved`() {
        val declination = Math.toRadians(13.0)
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L, magneticDeclinationRad = declination)
        assertTrue(abs(context.magneticDeclinationRad - declination) < eps)
    }

    @Test
    fun `a negative magneticDeclinationRad is preserved`() {
        val declination = Math.toRadians(-13.0)
        val context = StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L, magneticDeclinationRad = declination)
        assertTrue(abs(context.magneticDeclinationRad - declination) < eps)
    }

    @Test
    fun `of wraps a magneticDeclinationRad slightly beyond π back near minus π`() {
        val context =
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L, magneticDeclinationRad = PI + 0.3)
        assertTrue(abs(context.magneticDeclinationRad - (-PI + 0.3)) < eps)
    }

    @Test
    fun `of handles many-turn magneticDeclinationRad values without precision loss beyond floating tolerance`() {
        val manyTurns = 500.0 * 2.0 * PI + 0.2
        val context =
            StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = 0L, magneticDeclinationRad = manyTurns)
        assertTrue(abs(context.magneticDeclinationRad - 0.2) < 1e-6)
    }

    @Test
    fun `magneticDeclinationRad does not affect latitude, longitude, or utcEpochMillis`() {
        val withoutDeclination = StarProjectionContext.of(latitudeRad = 0.3, longitudeRad = 0.4, utcEpochMillis = 555L)
        val withDeclination =
            StarProjectionContext.of(latitudeRad = 0.3, longitudeRad = 0.4, utcEpochMillis = 555L, magneticDeclinationRad = Math.toRadians(10.0))
        assertEquals(withoutDeclination.latitudeRad, withDeclination.latitudeRad)
        assertEquals(withoutDeclination.longitudeRad, withDeclination.longitudeRad)
        assertEquals(withoutDeclination.utcEpochMillis, withDeclination.utcEpochMillis)
    }
}
