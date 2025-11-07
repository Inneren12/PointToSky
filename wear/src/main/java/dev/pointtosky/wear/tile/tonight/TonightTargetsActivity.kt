package dev.pointtosky.wear.tile.tonight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import dev.pointtosky.wear.R
import androidx.compose.ui.unit.dp

/**
 * Простой экран-список целей (заглушка под DoD).
 * Дальше можно подменить контент на реальный список.
 */
class TonightTargetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(text = getString(R.string.tonight_targets_label), style = MaterialTheme.typography.title3)
                    }
                    // TODO: подставить реальные цели из общего провайдера
                    item { Text(text = "• Moon") }
                    item { Text(text = "• Jupiter") }
                    item { Text(text = "• Vega") }
                }
            }
        }
    }
}
