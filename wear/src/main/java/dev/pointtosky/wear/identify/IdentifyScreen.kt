package dev.pointtosky.wear.identify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun IdentifyRoute(factory: IdentifyViewModelFactory, onOpenCard: ((IdentifyUiState) -> Unit)?, modifier: Modifier = Modifier) {
    val viewModel: IdentifyViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IdentifyScreen(
        state = state,
        onOpenCard = { onOpenCard?.invoke(state) },
        modifier = modifier,
    )
}

@Composable
fun IdentifyScreen(state: IdentifyUiState, onOpenCard: ((IdentifyUiState) -> Unit)?, modifier: Modifier = Modifier) {
    val title = state.title
    val typeLabel = when (state.type) {
        IdentifyType.STAR -> "STAR"
        IdentifyType.CONST -> "CONST"
        IdentifyType.PLANET -> "PLANET"
        IdentifyType.MOON -> "MOON"
    }
    val magText = state.magnitude?.let { java.lang.String.format(java.util.Locale.US, "m = %.1f", it) } ?: "—"
    val sepText = state.separationDeg?.let { java.lang.String.format(java.util.Locale.US, "Δ = %.1f°", it) } ?: "—"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.lowAccuracy) {
                LowAccuracyBadge()
            }
            Text(
                text = title,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "$typeLabel   $magText",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = sepText,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = { onOpenCard?.invoke(state) },
            enabled = onOpenCard != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = "Карточка")
        }
    }
}

@Composable
private fun LowAccuracyBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33FFAA00))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = "низкая точность",
            style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.Medium),
            color = Color(0xFFFFAA00),
        )
    }
}
