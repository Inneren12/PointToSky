package dev.pointtosky.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.activityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreProdSmokeWearTest {
    @get:Rule
    val scenarioRule = activityScenarioRule<MainActivity>()

    @Test
    fun launchMainActivity() {
        scenarioRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }
}
