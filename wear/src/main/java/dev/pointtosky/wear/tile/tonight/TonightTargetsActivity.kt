package dev.pointtosky.wear.tile.tonight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.wear.R
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Простой экран-список целей (заглушка под DoD).
 * Заменено на реальный список из общего провайдера.
 */
class TonightTargetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val context = LocalContext.current

                // Провайдер Tonight (как в тайле): без timeSource — он его не принимает.
                val provider =
                    remember {
                        RealTonightProvider(
                            context = context,
                            zoneRepo = ZoneRepo(context),
                            // Локалка: берём вручную заданную точку, если есть
                            getLastKnownLocation = {
                                LocationPrefs.fromContext(context).manualPointFlow.first()
                            },
                        )
                    }

                // Состояние экрана: список (title/subtitle) или null, пока грузимся
                var entries by remember { mutableStateOf<List<Pair<String, String?>>?>(null) }

                LaunchedEffect(provider) {
                    // Берём модель тайла “на сейчас” и проецируем в плоский список строк
                    val model = provider.getModel(Instant.now())
                    entries = model.items.map { it.title to it.subtitle }
                }

                ScalingLazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            text = context.getString(R.string.tonight_targets_label),
                            style = MaterialTheme.typography.title3,
                        )
                    }

                    when (val list = entries) {
                        null -> {
                            item {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                )
                            }
                            item { Text(text = context.getString(R.string.tonight_targets_loading)) }
                        }

                        else ->
                            if (list.isEmpty()) {
                                item { Text(text = context.getString(R.string.tonight_targets_empty)) }
                            } else {
                                items(list.size) { idx ->
                                    val (title, subtitle) = list[idx]
                                    val line = if (subtitle.isNullOrBlank()) "• $title" else "• $title — $subtitle"
                                    Text(text = line)
                                }
                            }
                    }
                }
            }
        }
    }
}
