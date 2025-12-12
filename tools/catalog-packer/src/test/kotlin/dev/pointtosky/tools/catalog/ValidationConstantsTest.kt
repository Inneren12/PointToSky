package dev.pointtosky.tools.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationConstantsTest {

    @Test
    fun `valid coordinates and magnitude should pass`() {
        val error = ValidationConstants.validateStarInput(
            raDeg = 180.0,
            decDeg = 45.0,
            mag = 5.0
        )
        assertNull("Valid input should have no error", error)
    }

    @Test
    fun `RA at boundaries should be valid`() {
        assertNull(ValidationConstants.validateStarInput(0.0, 0.0, 0.0))
        assertNull(ValidationConstants.validateStarInput(359.99, 0.0, 0.0))
    }

    @Test
    fun `Dec at boundaries should be valid`() {
        assertNull(ValidationConstants.validateStarInput(0.0, -90.0, 0.0))
        assertNull(ValidationConstants.validateStarInput(0.0, 90.0, 0.0))
    }

    @Test
    fun `magnitude at boundaries should be valid`() {
        assertNull(ValidationConstants.validateStarInput(0.0, 0.0, ValidationConstants.MAG_MIN))
        assertNull(ValidationConstants.validateStarInput(0.0, 0.0, ValidationConstants.MAG_MAX))
    }

    @Test
    fun `RA out of range should fail`() {
        var error = ValidationConstants.validateStarInput(-1.0, 0.0, 0.0)
        assertNotNull("RA < 0 should fail", error)

        error = ValidationConstants.validateStarInput(-10.0, 0.0, 0.0)
        assertNotNull("RA < 0 should fail", error)

        error = ValidationConstants.validateStarInput(360.0, 0.0, 0.0)
        assertNotNull("RA >= 360 should fail", error)

        error = ValidationConstants.validateStarInput(400.0, 0.0, 0.0)
        assertNotNull("RA > 360 should fail", error)
    }

    @Test
    fun `Dec out of range should fail`() {
        var error = ValidationConstants.validateStarInput(0.0, -91.0, 0.0)
        assertNotNull("Dec < -90 should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 91.0, 0.0)
        assertNotNull("Dec > 90 should fail", error)
    }

    @Test
    fun `magnitude out of range should fail`() {
        var error = ValidationConstants.validateStarInput(0.0, 0.0, ValidationConstants.MAG_MIN - 1.0)
        assertNotNull("Mag below minimum should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, ValidationConstants.MAG_MAX + 1.0)
        assertNotNull("Mag above maximum should fail", error)
    }

    @Test
    fun `extreme magnitude values should fail`() {
        // Far beyond reasonable physical values
        var error = ValidationConstants.validateStarInput(0.0, 0.0, 99.0)
        assertNotNull("Mag = 99.0 should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, -50.0)
        assertNotNull("Mag = -50.0 should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, 1000.0)
        assertNotNull("Mag = 1000.0 should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, -100.0)
        assertNotNull("Mag = -100.0 should fail", error)
    }

    @Test
    fun `magnitudes slightly within sanity range should pass`() {
        // Near boundaries but valid
        var error = ValidationConstants.validateStarInput(0.0, 0.0, -1.5)
        assertNull("Mag = -1.5 should pass", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, 14.9)
        assertNull("Mag = 14.9 should pass", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, -1.99)
        assertNull("Mag = -1.99 should pass", error)
    }

    @Test
    fun `NaN values should fail`() {
        var error = ValidationConstants.validateStarInput(Double.NaN, 0.0, 0.0)
        assertNotNull("NaN RA should fail", error)

        error = ValidationConstants.validateStarInput(0.0, Double.NaN, 0.0)
        assertNotNull("NaN Dec should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, Double.NaN)
        assertNotNull("NaN magnitude should fail", error)
    }

    @Test
    fun `infinite values should fail`() {
        var error = ValidationConstants.validateStarInput(Double.POSITIVE_INFINITY, 0.0, 0.0)
        assertNotNull("Infinite RA should fail", error)

        error = ValidationConstants.validateStarInput(0.0, Double.POSITIVE_INFINITY, 0.0)
        assertNotNull("Infinite Dec should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, Double.POSITIVE_INFINITY)
        assertNotNull("Infinite magnitude should fail", error)

        error = ValidationConstants.validateStarInput(0.0, 0.0, Double.NEGATIVE_INFINITY)
        assertNotNull("Negative infinite magnitude should fail", error)
    }

    @Test
    fun `normalizeRa should wrap correctly`() {
        // Within range
        assertEquals(0.0, ValidationConstants.normalizeRa(0.0), 0.0001)
        assertEquals(180.0, ValidationConstants.normalizeRa(180.0), 0.0001)

        // At and above 360
        assertEquals(0.0, ValidationConstants.normalizeRa(360.0), 0.0001)
        assertEquals(1.0, ValidationConstants.normalizeRa(361.0), 0.0001)
        assertEquals(40.0, ValidationConstants.normalizeRa(400.0), 0.0001)

        // Negative values should wrap to positive
        assertEquals(359.0, ValidationConstants.normalizeRa(-1.0), 0.0001)
        assertEquals(270.0, ValidationConstants.normalizeRa(-90.0), 0.0001)
    }

    @Test
    fun `bright stars should be valid`() {
        // Sirius is approximately -1.46
        val error = ValidationConstants.validateStarInput(101.3, -16.7, -1.46)
        assertNull("Bright stars like Sirius should be valid", error)
    }
}
