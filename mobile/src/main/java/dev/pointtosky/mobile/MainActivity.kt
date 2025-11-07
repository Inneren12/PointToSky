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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModelFactory
import dev.pointtosky.mobile.catalog.CatalogDebugRoute
import dev.pointtosky.mobile.catalog.CatalogRepositoryProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.mobile.datalayer.AimTargetOption
import dev.pointtosky.mobile.datalayer.DemoAimTargets
import dev.pointtosky.mobile.datalayer.MobileBridge
import dev.pointtosky.mobile.location.LocationSetupScreen
import dev.pointtosky.mobile.location.share.PhoneLocationBridge
import dev.pointtosky.mobile.skymap.SkyMapRoute
import dev.pointtosky.mobile.time.TimeDebugScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val locationPrefs: LocationPrefs by lazy {
        LocationPrefs.fromContext(applicationContext)
    }

    private val phoneLocationBridge: PhoneLocationBridge by lazy {
        PhoneLocationBridge(
            context = this,
            locationPrefs = locationPrefs,
        )
    }

    private val catalogRepository: CatalogRepository by lazy {
        CatalogRepositoryProvider.get(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val bridgeState by phoneLocationBridge.state.collectAsState()
            val coroutineScope = rememberCoroutineScope()
            val aimTargets = remember { DemoAimTargets.list() }
            val appContext = this@MainActivity.applicationContext
            val dataLayerBridge: dev.pointtosky.mobile.datalayer.MobileBridge.Sender = remember { MobileBridge.get(appContext) }
            PointToSkyMobileApp(
                onOpenCard = {
                    Toast.makeText(
                        this,
                        getString(R.string.open_card),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                locationPrefs = locationPrefs,
                shareState = bridgeState,
                onShareToggle = { enabled ->
                    coroutineScope.launch {
                        phoneLocationBridge.setShareEnabled(enabled)
                    }
                },
                catalogRepository = catalogRepository,
                aimTargets = aimTargets,
                onSendAimTarget = { target ->
                    coroutineScope.launch {
                        val ack = dataLayerBridge.send(PATH_AIM_SET_TARGET) { cid ->
                            val message = target.buildMessage(cid)
                            JsonCodec.encode(message)
                        }
                        val toastText = when {
                            ack == null -> getString(R.string.aim_send_queued, target.label)
                            ack.ok -> getString(R.string.aim_send_success, target.label)
                            else -> {
                                val reason = ack.err ?: getString(R.string.aim_send_failed_no_reason)
                                getString(R.string.aim_send_failed, target.label, reason)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        phoneLocationBridge.start()
    }

    override fun onStop() {
        super.onStop()
        phoneLocationBridge.stop()
    }
}

@Composable
fun PointToSkyMobileApp(
    onOpenCard: () -> Unit,
    locationPrefs: LocationPrefs,
    shareState: PhoneLocationBridge.PhoneLocationBridgeState,
    onShareToggle: (Boolean) -> Unit,
    catalogRepository: CatalogRepository,
    aimTargets: List<AimTargetOption>,
    onSendAimTarget: (AimTargetOption) -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            var destination by remember { mutableStateOf(MobileDestination.Home) }
            when (destination) {
                MobileDestination.Home -> MobileHome(
                    onOpenCard = onOpenCard,
                    onSkyMap = { destination = MobileDestination.SkyMap },
                    onLocationSetup = { destination = MobileDestination.LocationSetup },
                    onTimeDebug = { destination = MobileDestination.TimeDebug },
                    onCatalogDebug = { destination = MobileDestination.CatalogDebug },
                    aimTargets = aimTargets,
                    onSendAimTarget = onSendAimTarget,
                    )

                MobileDestination.LocationSetup -> LocationSetupScreen(
                    locationPrefs = locationPrefs,
                    shareState = shareState,
                    onShareToggle = onShareToggle,
                    onBack = { destination = MobileDestination.Home }
                )

                MobileDestination.TimeDebug -> TimeDebugScreen(
                    onBack = { destination = MobileDestination.Home }
                )

                MobileDestination.CatalogDebug -> CatalogDebugRoute(
                    factory = CatalogDebugViewModelFactory(catalogRepository),
                    modifier = Modifier.fillMaxSize(),
                    onBack = { destination = MobileDestination.Home },
                )

                MobileDestination.SkyMap -> SkyMapRoute(
                    catalogRepository = catalogRepository,
                    locationPrefs = locationPrefs,
                    onBack = { destination = MobileDestination.Home },
                    onOpenCard = onOpenCard,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun MobileHome(
    onOpenCard: () -> Unit,
    onSkyMap: () -> Unit,
    onLocationSetup: () -> Unit,
    onTimeDebug: () -> Unit,
    onCatalogDebug: () -> Unit,
    aimTargets: List<AimTargetOption>,
    onSendAimTarget: (AimTargetOption) -> Unit,
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
        aimTargets.forEach { target ->
            Button(
                onClick = { onSendAimTarget(target) },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = stringResource(id = R.string.aim_send_button, target.label))
            }
        }
        Button(
            onClick = onSkyMap,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.sky_map))
        }
        Button(
            onClick = onLocationSetup,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.location_settings))
        }
        Button(
            onClick = onTimeDebug,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.time_debug))
        }
        Button(
            onClick = onCatalogDebug,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.catalog_debug))
        }
    }
}

private enum class MobileDestination { Home, SkyMap, LocationSetup, TimeDebug, CatalogDebug }

private class PreviewLocationPrefs : LocationPrefs {
    override val manualPointFlow: Flow<GeoPoint?> = flowOf(null)
    override val usePhoneFallbackFlow: Flow<Boolean> = flowOf(false)
    override val shareLocationWithWatchFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setManual(point: GeoPoint?) = Unit
    override suspend fun setUsePhoneFallback(usePhoneFallback: Boolean) = Unit
    override suspend fun setShareLocationWithWatch(share: Boolean) = Unit
}

@Preview(showSystemUi = true)
@Composable
fun MobileHomePreview() {
    val context = LocalContext.current
    PointToSkyMobileApp(
        onOpenCard = {},
        locationPrefs = PreviewLocationPrefs(),
        shareState = PhoneLocationBridge.PhoneLocationBridgeState.Empty,
        onShareToggle = {},
        catalogRepository = CatalogRepository.create(context),
        aimTargets = emptyList(),
        onSendAimTarget = {},
    )
}
