package dev.pointtosky.mobile.tile.tonight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class TonightOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = intent?.getStringExtra("payload") ?: ""
        setContent {
            MaterialTheme {
                Text(
                    text = "Payload: $payload",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
