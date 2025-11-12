package dev.pointtosky.wear.complication

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
}
