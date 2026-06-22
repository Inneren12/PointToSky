package dev.pointtosky.wear.complication

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pointtosky.wear.aim.core.AimPhase
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import android.os.Build
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O]) // нужна версия SDK, где есть Configuration.getLocales()
class AimStatusFormatterTest {
    private lateinit var context: Context
    private lateinit var formatter: AimStatusFormatter

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext()
        formatter = AimStatusFormatter(context)
    }

    @Test
    fun shortText_formatsNeutralAndPositiveOffsets() {
        val text = formatter.shortText(deltaAzDeg = 0.0, deltaAltDeg = 1.24)

        assertThat(text).isEqualTo("↔0° ↑1.2°")
    }

    @Test
    fun shortText_formatsNegativeOffsets() {
        val text = formatter.shortText(deltaAzDeg = -0.63, deltaAltDeg = -2.0)

        assertThat(text).isEqualTo("←0.6° ↓2°")
    }

    @Test
    fun phaseLabel_belowHorizonAndNoLocation_returnDistinctNonEmptyStrings() {
        // phaseLabel resolves app string resources. Unit tests in this module run without merged
        // app resources (testOptions.unitTests.isIncludeAndroidResources = false); skip cleanly if
        // they are unavailable. The mapping is also exercised on-device / in androidTest.
        try {
            val below = formatter.phaseLabel(AimPhase.BELOW_HORIZON)
            val noLocation = formatter.phaseLabel(AimPhase.NO_LOCATION)

            assertThat(below).isNotEmpty()
            assertThat(noLocation).isNotEmpty()
            assertThat(below).isNotEqualTo(noLocation)
            // Distinct from the guiding phases as well.
            assertThat(below).isNotEqualTo(formatter.phaseLabel(AimPhase.SEARCHING))
            assertThat(noLocation).isNotEqualTo(formatter.phaseLabel(AimPhase.LOCKED))
        } catch (e: Resources.NotFoundException) {
            Assume.assumeNoException("App string resources are unavailable in unit tests", e)
        }
    }
}
