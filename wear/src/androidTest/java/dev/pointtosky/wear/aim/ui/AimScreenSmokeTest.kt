package dev.pointtosky.wear.aim.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.wear.compose.material.MaterialTheme
import dev.pointtosky.wear.aim.core.AimController
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.aim.core.AimState
import dev.pointtosky.wear.aim.core.AimTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class AimScreenSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aim_renders() {
        val fake =
            object : AimController {
                override val state: StateFlow<AimState> =
                    MutableStateFlow(
                        AimState(
                            current =
                                dev.pointtosky.core.astro.coord
                                    .Horizontal(0.0, 0.0),
                            target =
                                dev.pointtosky.core.astro.coord
                                    .Horizontal(0.0, 0.0),
                            dAzDeg = 10.0,
                            dAltDeg = -5.0,
                            phase = AimPhase.SEARCHING,
                            confidence = 0.7f,
                        ),
                    )

                override fun setTarget(target: AimTarget) = Unit

                override fun setTolerance(t: dev.pointtosky.wear.aim.core.AimTolerance) = Unit

                override fun setHoldToLockMs(ms: Long) = Unit

                override fun start() = Unit

                override fun stop() = Unit
            }

        compose.setContent {
            MaterialTheme {
                AimScreen(aimController = fake, initialTarget = null)
            }
        }
        compose.onNodeWithTag("AimRoot").assertIsDisplayed()
    }
}
