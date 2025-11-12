package dev.pointtosky.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class FlavorInternalTest {
    @Test fun flavor_is_internal() {
        assertEquals("internal", BuildConfig.FLAVOR)
    }
}
