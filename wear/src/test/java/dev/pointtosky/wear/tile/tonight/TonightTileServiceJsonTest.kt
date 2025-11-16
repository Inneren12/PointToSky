package dev.pointtosky.wear.tile.tonight

import org.json.JSONObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonightTileServiceJsonTest {
    private val service = object : TonightTileService() {}

    @Test
    fun `push model json mirrors tile targets`() {
        val model =
            TonightTileModel(
                updatedAt = Instant.parse("2024-07-01T06:00:00Z"),
                items = listOf(
                    TonightTarget(
                        id = "VEGA",
                        title = "Vega",
                        subtitle = "alt 68°",
                        icon = TonightIcon.STAR,
                        azDeg = 123.0,
                        altDeg = 68.0,
                    ),
                    TonightTarget(
                        id = "MOON",
                        title = "Moon",
                        subtitle = null,
                        icon = TonightIcon.MOON,
                        azDeg = 210.0,
                        altDeg = 32.0,
                    ),
                ),
            )

        val json = service.buildPushModelJson(model)
        println("DEBUG_JSON: $json")
        val parsed = JSONObject(json)
        println("DEBUG_MODEL_UPDATED_AT: ${model.updatedAt.epochSecond}")
        assertEquals(model.updatedAt.epochSecond, parsed.getLong("updatedAt"))

        val items = parsed.getJSONArray("items")
        assertEquals(2, items.length())

        val first = items.getJSONObject(0)
        assertEquals("VEGA", first.getString("id"))
        assertEquals("Vega", first.getString("title"))
        assertEquals("alt 68°", first.getString("subtitle"))
        assertEquals("STAR", first.getString("icon"))

        val second = items.getJSONObject(1)
        assertEquals("MOON", second.getString("id"))
        assertTrue(second.isNull("subtitle"))
        assertEquals("MOON", second.getString("icon"))
    }
}
