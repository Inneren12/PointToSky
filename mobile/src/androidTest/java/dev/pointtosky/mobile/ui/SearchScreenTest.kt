package dev.pointtosky.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.mobile.catalog.CatalogRepositoryProvider
import dev.pointtosky.mobile.search.SearchRoute
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val openedCardId = AtomicReference<String?>()

    @BeforeTest
    fun reset() {
        openedCardId.set(null)
    }

    @Test
    fun typingVegaShowsResults() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = CatalogRepositoryProvider.get(context)

        composeTestRule.setContent {
            SearchRoute(
                catalogRepository = repository,
                onBack = {},
                onOpenCard = { openedCardId.set(it) },
            )
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("Vega")

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodes(hasText("Vega")).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Vega").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { openedCardId.get() != null }
        assertNotNull(openedCardId.get())
    }
}
