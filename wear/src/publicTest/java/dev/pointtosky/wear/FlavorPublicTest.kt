package dev.pointtosky.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class FlavorPublicTest {
    @Test fun flavor_is_public() {
        assertEquals("public", BuildConfig.FLAVOR)
    }
}
