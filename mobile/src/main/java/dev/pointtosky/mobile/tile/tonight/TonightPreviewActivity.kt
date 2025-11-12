package dev.pointtosky.mobile.tile.tonight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class TonightPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model by TonightMirrorStore.model.collectAsState(initial = null)
                val currentModel = model
                if (currentModel == null) {
                    Text(
                        text = "No data",
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(text = "Updated: ${currentModel.updatedAt}")
                        Spacer(Modifier.height(8.dp))
                        LazyColumn {
                            items(currentModel.items) { item ->
                                val subtitleSuffix = item.subtitle?.let { " — $it" }.orEmpty()
                                Text(text = "${item.title}$subtitleSuffix")
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
