package dev.pointtosky.mobile

import android.content.Context
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.mobile.settings.MobileSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.BeforeClass

@RunWith(AndroidJUnit4::class)
class PreProdSmokeMobileTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun enableArMode() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = MobileSettings.from(context)
            runBlocking { settings.setArEnabled(true) }
        }
    }

    @Test
    fun launchAimFlowAndRequestPermission() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ar_mode)).assertExists().performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.ar_permission_title)).assertExists()
    }
}
