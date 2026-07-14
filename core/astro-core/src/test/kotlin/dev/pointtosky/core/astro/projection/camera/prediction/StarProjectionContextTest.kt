package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StarProjectionContextTest {
    private val eps = 1e-9

    // --- Primary constructor -------------------------------------------------------------------------

    @Test
    fun `primary constructor accepts longitude outside -π,π without normalizing it`() {
        val context = StarProjectionContext(latitudeRad = 0.0, longitudeRad = 4.0, utcEpochMillis = 0L)
        assertEquals(4.0, context.longitudeRad)
    }

    @Test
    fun `non-finite latitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext(latitudeRad = Double.NaN, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `non-finite longitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext(latitudeRad = 0.0, longitudeRad = Double.POSITIVE_INFINITY, utcEpochMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext(latitudeRad = 0.0, longitudeRad = Double.NEGATIVE_INFINITY, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `latitude outside -π,2,π,2 is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext(latitudeRad = PI / 2.0 + 0.001, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            StarProjectionContext(latitudeRad = -PI / 2.0 - 0.001, longitudeRad = 0.0, utcEpochMillis = 0L)
        }
    }

    @Test
    fun `latitude exactly at the poles is accepted`() {
        StarProjectionContext(latitudeRad = PI / 2.0, longitudeRad = 0.0, utcEpochMillis = 0L)
        StarProjectionContext(latitudeRad = -PI / 2.0, longitudeRad = 0.0, utcEpochMillis = 0L)
    }

    @Test
    fun `any long is accepted for utcEpochMillis including negative`() {
        StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = Long.MIN_VALUE)
        StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = Long.MAX_VALUE)
        StarProjectionContext(latitudeRad = 0.0, longitudeRad = 0.0, utcEpochMillis = -1L)
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
}
