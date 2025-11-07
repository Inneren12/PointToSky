package dev.pointtosky.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.mobile.card.CardBestWindow
import dev.pointtosky.mobile.card.CardObjectType
import dev.pointtosky.mobile.card.CardUiState
import dev.pointtosky.mobile.card.CardScreen
import dev.pointtosky.mobile.datalayer.AimTargetOption
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val targetPayload = AimTargetOption(
        id = "vega",
        label = "Vega",
        buildMessage = { cid ->
            dev.pointtosky.core.datalayer.AimSetTargetMessage(
                cid = cid,
                kind = AimTargetKind.EQUATORIAL,
                payload = JsonCodec.encodeToElement(
                    AimTargetEquatorialPayload(
                        raDeg = 279.23473479,
                        decDeg = 38.78368896,
                    )
                ),
            )
        },
    )

    private var sentTargetId: String? = null

    @BeforeTest
    fun reset() {
        sentTargetId = null
    }

    @Test
    fun cardScreenRendersAndSendsTarget() {
        val state = CardUiState.Ready(
            id = "vega",
            title = "Vega",
            type = CardObjectType.STAR,
            magnitude = 0.03,
            constellation = "LYR",
            body = null,
            equatorial = dev.pointtosky.core.astro.coord.Equatorial(279.23473479, 38.78368896),
            horizontal = null,
            bestWindow = CardBestWindow(
                start = Instant.ofEpochSecond(1_700_000_000),
                end = Instant.ofEpochSecond(1_700_003_600),
            ),
            targetOption = targetPayload,
            shareText = "",
        )

        composeTestRule.setContent {
            CardScreen(
                state = state,
                onBack = {},
                onSendAimTarget = { option -> sentTargetId = option.id },
                onShare = {},
            )
        }

        composeTestRule.onNodeWithText("Vega").assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(dev.pointtosky.mobile.R.string.card_set_target_button))
            .performClick()

        assertEquals("vega", sentTargetId)
    }
}
