package dev.pointtosky.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.mobile.location.LocationSetupScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    private val locationPrefs: LocationPrefs by lazy {
        LocationPrefs.fromContext(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PointToSkyMobileApp(
                onOpenCard = {
                    Toast.makeText(
                        this,
                        getString(R.string.open_card),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                locationPrefs = locationPrefs
            )
        }
    }
}

@Composable
fun PointToSkyMobileApp(
    onOpenCard: () -> Unit,
    locationPrefs: LocationPrefs,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var destination by remember { mutableStateOf(MobileDestination.Home) }
            when (destination) {
                MobileDestination.Home -> MobileHome(
                    onOpenCard = onOpenCard,
                    onLocationSetup = { destination = MobileDestination.LocationSetup },
                )

                MobileDestination.LocationSetup -> LocationSetupScreen(
                    locationPrefs = locationPrefs,
                    onBack = { destination = MobileDestination.Home }
                )
            }
        }
    }
}

@Composable
fun MobileHome(
    onOpenCard: () -> Unit,
    onLocationSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Point-to-Sky Mobile", style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = onOpenCard,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(text = stringResource(id = R.string.open_card))
        }
        Button(
            onClick = onLocationSetup,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.location_settings))
        }
    }
}

private enum class MobileDestination { Home, LocationSetup }

private class PreviewLocationPrefs : LocationPrefs {
    override val manualPointFlow: Flow<GeoPoint?> = flowOf(null)
    override val usePhoneFallbackFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setManual(point: GeoPoint?) = Unit
    override suspend fun setUsePhoneFallback(usePhoneFallback: Boolean) = Unit
}

@Preview(showSystemUi = true)
@Composable
fun MobileHomePreview() {
    PointToSkyMobileApp(onOpenCard = {}, locationPrefs = PreviewLocationPrefs())
}
