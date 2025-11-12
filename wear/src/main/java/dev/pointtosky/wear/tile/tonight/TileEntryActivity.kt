package dev.pointtosky.wear.tile.tonight

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.ACTION_OPEN_AIM_LEGACY
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY_LEGACY
import dev.pointtosky.wear.EXTRA_AIM_TARGET_KIND
import dev.pointtosky.wear.MainActivity
import dev.pointtosky.wear.datalayer.WearMessageBridge
import kotlinx.coroutines.CoroutineDispatcher
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
            LogBus.event("tile_tap_target", mapOf("id" to targetId))
            CoroutineScope(TileEntryDispatchers.io).launch {
                runCatching {
                    WearMessageBridge.sendToPhone(
                        applicationContext,
                        "/tile/tonight/open",
                        """{"id":"$targetId"}""".toByteArray(Charsets.UTF_8),
                    )
                }.onFailure { e ->
                    LogBus.event(
                        name = "tile_error",
                        payload = mapOf(
                            "err" to e.toLogMessage(),
                            "stage" to "tap_forward",
                        ),
                    )
                }
            }
        } else if (clickId == "open_tonight_list") {
            LogBus.event("tile_tap_more")
        }

        val incoming = intent
        val forward = Intent(this, MainActivity::class.java).apply {
            // сохраняем action/extras — при желании обработаем в MainActivity (onNewIntent)
            action = when (incoming?.action) {
                ACTION_OPEN_AIM_LEGACY -> ACTION_OPEN_AIM
                ACTION_OPEN_IDENTIFY_LEGACY -> ACTION_OPEN_IDENTIFY
                else -> incoming?.action ?: run {
                    if (incoming?.hasExtra(EXTRA_AIM_TARGET_KIND) == true) ACTION_OPEN_AIM else null
                }
            }
            replaceExtras(incoming ?: Intent())
        }
        startActivity(forward) // мы уже в Activity — флаги не нужны
        finish()
    }

    private fun Throwable.toLogMessage(): String = message ?: javaClass.simpleName
}

// Локальный провайдер диспетчера: избегаем прямого Dispatchers.IO для правила InjectDispatcher
private object TileEntryDispatchers {
    @Suppress("InjectDispatcher")
    val io: CoroutineDispatcher = Dispatchers.IO
}
