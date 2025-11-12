package dev.pointtosky.wear.complication

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ServiceScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.google.common.truth.Truth.assertThat
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.complication.config.AimPrefs
import dev.pointtosky.wear.complication.config.ComplicationPrefsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AimStatusComplicationDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = AimStatusRepository(context)
    private val prefsStore = ComplicationPrefsStore(context)

    private val snapshot =
        AimStatusSnapshot(
            timestampMs = 42L,
            isActive = true,
            dAzDeg = 1.2,
            dAltDeg = 0.4,
            phase = AimPhase.IN_TOLERANCE,
            target =
                AimStatusTarget(
                    kind = AimStatusTargetKind.STAR,
                    label = "Vega",
                ),
        )

    @Before
    fun setUp() =
        runBlocking {
            repository.write(snapshot)
            prefsStore.saveAim(AimPrefs(showDelta = true, showPhase = true))
        }

    @After
    fun tearDown() =
        runBlocking {
            repository.write(AimStatusSnapshot.EMPTY)
        }

    @Test
    fun shortTextData_containsPhaseAndOffsets() {
        val data = requestData(ComplicationType.SHORT_TEXT) as ShortTextComplicationData

        val rendered = data.text.getTextAt(context.resources, Instant.EPOCH).toString()
        assertThat(rendered).contains("In tolerance")
        assertThat(rendered).contains("↔")
        assertThat(data.tapAction).isNotNull()
    }

    @Test
    fun monochromaticImageData_hasIconAndDescription() {
        val data = requestData(ComplicationType.MONOCHROMATIC_IMAGE) as MonochromaticImageComplicationData

        val description = data.contentDescription.getTextAt(context.resources, Instant.EPOCH).toString()
        assertThat(data.monochromaticImage.image).isNotNull()
        assertThat(description).contains("Vega")
        assertThat(data.tapAction).isNotNull()
    }

    @Test
    fun rangedValueData_reflectsClosenessAndTapAction() {
        val data = requestData(ComplicationType.RANGED_VALUE) as RangedValueComplicationData

        assertThat(data.value).isWithin(0.1f).of(60f)
        val text = data.text?.getTextAt(context.resources, Instant.EPOCH)?.toString()
        assertThat(text).contains("↔")
        assertThat(data.tapAction).isNotNull()
    }

    private fun requestData(type: ComplicationType): ComplicationData? {
        var result: ComplicationData? = null
        val scenario = ServiceScenario.launch(AimStatusDataSourceService::class.java)
        scenario.onService { service ->
            result =
                runBlocking {
                    service.onComplicationRequest(
                        ComplicationRequest(
                            // complicationInstanceId =
                            101,
                            type,
                        ),
                    )
                }
        }
        scenario.close()
        return result
    }
}
