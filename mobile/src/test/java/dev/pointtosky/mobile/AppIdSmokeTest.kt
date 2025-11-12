package dev.pointtosky.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdSmokeTest {
    @Test fun appId_starts_with_expected_prefix() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("dev.pointtosky.mobile"))
    }
}
