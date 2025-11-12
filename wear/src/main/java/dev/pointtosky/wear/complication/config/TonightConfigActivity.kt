package dev.pointtosky.wear.complication.config

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TonightConfigActivity : ComponentActivity() {

    private val prefsStore by lazy { ComplicationPrefsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val tonightPrefs by prefsStore.tonightFlow.collectAsState(
                initial = TonightPrefs(magLimit = 5.5f, preferPlanets = true),
            )

            MaterialTheme {
                TonightConfigScreen(
                    prefs = tonightPrefs,
                    onSave = { newPrefs ->
                        this@TonightConfigActivity.saveTonightAndFinish(newPrefs)
                    },
                )
            }
        }
    }

    private fun saveTonightAndFinish(prefs: TonightPrefs) {
        lifecycleScope.launch {
            prefsStore.saveTonight(prefs)
            setResult(RESULT_OK)
            finish()
        }
    }
}

@Composable
private fun TonightConfigScreen(
    prefs: TonightPrefs,
    onSave: (TonightPrefs) -> Unit,
) {
    var magLimit by remember { mutableStateOf(prefs.magLimit) }
    var preferPlanets by remember { mutableStateOf(prefs.preferPlanets) }

    LaunchedEffect(prefs) {
        magLimit = prefs.magLimit
        preferPlanets = prefs.preferPlanets
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "Настройки Tonight complication", style = MaterialTheme.typography.titleMedium)

        Text(text = "Порог яркости: %.1f".format(magLimit))
        Slider(
            value = magLimit,
            onValueChange = { magLimit = it },
            valueRange = 0f..7f,
        )

        PreferenceSwitch(
            title = "Предпочитать планеты",
            checked = preferPlanets,
            onCheckedChange = { preferPlanets = it },
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                onSave(
                    TonightPrefs(
                        magLimit = magLimit,
                        preferPlanets = preferPlanets,
                    ),
                )
            },
        ) {
            Text(text = "Сохранить")
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
