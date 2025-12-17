package dev.pointtosky.wear.tile.tonight

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TonightTileServiceTest {
    @Test
    fun `buildPushModelJson escapes strings`() {
        val model = TonightTileModel(
            updatedAt = Instant.ofEpochSecond(1234),
            items = listOf(
                TonightTarget(
                    id = "id\"with\nquotes",
                    title = "Vega \"Lyra\"",
                    subtitle = "Alt 45° & rising",
                    icon = TonightIcon.STAR,
                ),
            ),
        )

        val json = TonightTileService().buildPushModelJson(model)

        val parsed = JSONObject(json)
        assertEquals(1234, parsed.getLong("updatedAt"))

        val item = parsed.getJSONArray("items").singleJSONObject()
        assertEquals(model.items.first().id, item.getString("id"))
        assertEquals(model.items.first().title, item.getString("title"))
        assertEquals(model.items.first().subtitle, item.getString("subtitle"))
        assertEquals(model.items.first().icon.name, item.getString("icon"))
    }
}

private fun JSONArray.singleJSONObject(): JSONObject = getJSONObject(0)
