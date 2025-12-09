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
    }

    @Test
    fun `normalizeRa should wrap correctly`() {
        assertEquals(0.0, ValidationConstants.normalizeRa(0.0), 0.0001)
        assertEquals(180.0, ValidationConstants.normalizeRa(180.0), 0.0001)
        assertEquals(0.0, ValidationConstants.normalizeRa(360.0), 0.0001)
        assertEquals(1.0, ValidationConstants.normalizeRa(361.0), 0.0001)
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
