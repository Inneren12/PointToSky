package dev.pointtosky.mobile
import org.junit.Test
import org.junit.Assert.assertTrue

class BuildConfigTest {
    @Test fun appId_starts_with_expected_prefix() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("dev.pointtosky.mobile"))
    }
}
