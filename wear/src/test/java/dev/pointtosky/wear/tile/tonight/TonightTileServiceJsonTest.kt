package dev.pointtosky.wear.tile.tonight

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
                items =
                    listOf(
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

        println("DEBUG_MODEL: updatedAt=${model.updatedAt.epochSecond}")
        model.items.forEachIndexed { idx, t ->
            println(
                "DEBUG_ITEM_$idx: id=${t.id} title=${t.title} subtitle=${t.subtitle} icon=${t.icon}"
            )
        }

        val json = service.buildPushModelJson(model)
        println("DEBUG_JSON: $json")

        // 1) updatedAt: та же epochSecond, что в модели
        val updatedAtRegex = Regex("\"updatedAt\"\\s*:\\s*(\\d+)")
        val match =
            updatedAtRegex.find(json)
                ?: error("updatedAt not found in json: $json")

        val updatedFromJson = match.groupValues[1].toLong()
        assertEquals(model.updatedAt.epochSecond, updatedFromJson, "updatedAt mismatch")

        // 2) items-массив вообще есть
        assertTrue(
            json.contains("\"items\":["),
            "items array is missing in json: $json",
        )

        // Подстрока массива items: [{"id":...},{...}]
        val itemsStart = json.indexOf("\"items\":[")
        val arrayOpen = json.indexOf('[', itemsStart)
        val arrayClose = json.indexOf(']', arrayOpen)
        require(arrayOpen != -1 && arrayClose != -1 && arrayClose > arrayOpen) {
            "Cannot find items array brackets in json: $json"
        }
        val itemsSlice = json.substring(arrayOpen + 1, arrayClose)
        val rawItems = itemsSlice.split("},{").map { it.trim().trimStart('{').trimEnd('}') }
        assertEquals(2, rawItems.size, "Expected 2 items in JSON, got ${rawItems.size}: $itemsSlice")

        val firstItem = rawItems[0]
        val secondItem = rawItems[1]

        // 3) Первый элемент: Vega
        assertTrue(firstItem.contains("\"id\":\"VEGA\""), "First item id mismatch: $firstItem")
        assertTrue(firstItem.contains("\"title\":\"Vega\""), "First item title mismatch: $firstItem")
        assertTrue(firstItem.contains("\"subtitle\":\"alt 68°\""), "First item subtitle mismatch: $firstItem")
        assertTrue(firstItem.contains("\"icon\":\"STAR\""), "First item icon mismatch: $firstItem")

        // 4) Второй элемент: Moon
        assertTrue(secondItem.contains("\"id\":\"MOON\""), "Second item id mismatch: $secondItem")
        assertTrue(secondItem.contains("\"title\":\"Moon\""), "Second item title mismatch: $secondItem")
        assertTrue(secondItem.contains("\"icon\":\"MOON\""), "Second item icon mismatch: $secondItem")
        // subtitle для MOON в модели = null → в JSON ключа быть не должно
        assertTrue(
            !secondItem.contains("\"subtitle\":\""),
            "Second item should not have non-null subtitle: $secondItem",
        )
    }
}
