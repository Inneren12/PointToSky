package dev.pointtosky.mobile.tile.tonight

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ShortItem(val id: String, val title: String, val subtitle: String?, val icon: String?)
data class ShortModel(val updatedAt: Long, val items: List<ShortItem>)

object TonightMirrorStore {
    private val _model = MutableStateFlow<ShortModel?>(null)
    val model: StateFlow<ShortModel?> = _model

    fun applyJson(json: String) {
        runCatching {
            val root = JSONObject(json)
            val updatedAt = root.optLong("updatedAt")
            val itemsJson = root.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (i in 0 until itemsJson.length()) {
                    val it = itemsJson.optJSONObject(i) ?: continue
                    add(
                        ShortItem(
                            id = it.optString("id"),
                            title = it.optString("title"),
                            subtitle = it.optString("subtitle").takeIf { s -> s.isNotBlank() },
                            icon = it.optString("icon"),
                        )
                    )
                }
            }
            _model.value = ShortModel(updatedAt, items)
        }
    }
}
