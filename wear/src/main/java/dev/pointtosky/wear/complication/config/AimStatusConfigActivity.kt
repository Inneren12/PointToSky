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

class AimStatusConfigActivity : ComponentActivity() {

    private val prefsStore by lazy { ComplicationPrefsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val aimPrefs by prefsStore.aimFlow.collectAsState(
                initial = AimPrefs(showDelta = true, showPhase = true),
            )

            MaterialTheme {
                AimConfigScreen(
                    prefs = aimPrefs,
                    onSave = { newPrefs ->
                        this@AimStatusConfigActivity.saveAimAndFinish(newPrefs)
                    },
                )
            }
        }
    }

    private fun saveAimAndFinish(prefs: AimPrefs) {
        lifecycleScope.launch {
            prefsStore.saveAim(prefs)
            setResult(RESULT_OK)
            finish()
        }
    }
}

@Composable
private fun AimConfigScreen(
    prefs: AimPrefs,
    onSave: (AimPrefs) -> Unit,
) {
    var showDelta by remember { mutableStateOf(prefs.showDelta) }
    var showPhase by remember { mutableStateOf(prefs.showPhase) }

    LaunchedEffect(prefs) {
        showDelta = prefs.showDelta
        showPhase = prefs.showPhase
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "Настройки Aim complication", style = MaterialTheme.typography.titleMedium)

        PreferenceSwitch(
            title = "Показывать делту",
            checked = showDelta,
            onCheckedChange = { showDelta = it },
        )

        PreferenceSwitch(
            title = "Показывать фазу",
            checked = showPhase,
            onCheckedChange = { showPhase = it },
        )

        Spacer(modifier = Modifier.weight(1f, fill = true))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onSave(AimPrefs(showDelta = showDelta, showPhase = showPhase)) },
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
