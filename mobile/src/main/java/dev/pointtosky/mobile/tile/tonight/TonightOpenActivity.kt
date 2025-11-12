package dev.pointtosky.mobile.tile.tonight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pointtosky.mobile.logging.MobileLog
import org.json.JSONObject

class TonightOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = intent?.getStringExtra("payload").orEmpty()
        val parsed = runCatching { JSONObject(payload) }.getOrNull()
        val cardId = parsed?.optString("id")?.takeIf { it.isNotBlank() }
        val cardType = parsed?.optString("type")?.takeIf { it.isNotBlank() }
        MobileLog.cardOpen(source = "tile", id = cardId, type = cardType)
        setContent {
            MaterialTheme {
                Text(
                    text = "Payload: $payload",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
