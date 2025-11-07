package dev.pointtosky.wear.tile.tonight

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.pointtosky.wear.MainActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.pointtosky.wear.datalayer.WearMessageBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
/**
 * Заглушка для открытия приложения по тапу из тайла.
 * Пока просто запускает MainActivity и завершает себя.
 */
class TileEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // S7.E: извлечь ID клика и отправить "/tile/tonight/open" {id} на телефон
        val clickId = intent?.getStringExtra("androidx.wear.tiles.extra.TILE_CLICK_ID")
            ?: intent?.getStringExtra("androidx.wear.tiles.extra.CLICKABLE_ID")
            ?: intent?.action
        val targetId = clickId?.substringAfter(':', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
        if (targetId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    WearMessageBridge.sendToPhone(
                        applicationContext,
                        "/tile/tonight/open",
                        """{"id":"$targetId"}""".toByteArray(Charsets.UTF_8)
                    )
                }
            }
        }

        val forward = Intent(this, MainActivity::class.java).apply {
            // сохраняем action/extras — при желании позже их обработаем в MainActivity
            action = intent?.action
            putExtras(intent ?: Intent())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(forward)
        finish()
    }
}
