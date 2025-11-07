package dev.pointtosky.wear.tile.tonight

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.pointtosky.wear.MainActivity

/**
 * Заглушка для открытия приложения по тапу из тайла.
 * Пока просто запускает MainActivity и завершает себя.
 */
class TileEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
