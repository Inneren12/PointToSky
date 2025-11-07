package dev.pointtosky.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModelFactory
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.mobile.card.CardRoute
import dev.pointtosky.mobile.card.parseCardIdFromIntent
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.catalog.CatalogDebugRoute
import dev.pointtosky.mobile.catalog.CatalogRepositoryProvider
import dev.pointtosky.mobile.datalayer.AimTargetOption
import dev.pointtosky.mobile.datalayer.DemoAimTargets
import dev.pointtosky.mobile.datalayer.MobileBridge
import dev.pointtosky.mobile.ar.ArRoute
import dev.pointtosky.mobile.location.LocationSetupScreen
import dev.pointtosky.mobile.location.share.PhoneLocationBridge
import dev.pointtosky.mobile.skymap.SkyMapRoute
import dev.pointtosky.mobile.time.TimeDebugScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

    private val navigationState = MutableStateFlow<MobileDestination>(MobileDestination.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val bridgeState by phoneLocationBridge.state.collectAsStateWithLifecycle()
            val destination by navigationState.collectAsStateWithLifecycle()
            val latestCardId by CardRepository.latestCardIdFlow().collectAsStateWithLifecycle(initialValue = null)
            val coroutineScope = rememberCoroutineScope()
            val aimTargets = remember { DemoAimTargets.list() }
            val appContext = this@MainActivity.applicationContext
            val dataLayerBridge: dev.pointtosky.mobile.datalayer.MobileBridge.Sender = remember { MobileBridge.get(appContext) }
            val openLatestCard: () -> Unit = {
                val id = latestCardId
                if (id != null) {
                    navigationState.value = MobileDestination.Card(id)
                } else {
                    showNotEnoughDataToast()
                }
            }
            PointToSkyMobileApp(
                destination = destination,
                onNavigate = { navigationState.value = it },
                latestCardAvailable = latestCardId != null,
                onOpenLatestCard = openLatestCard,
                onOpenAr = { navigationState.value = MobileDestination.Ar },
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
                onShareCard = { shareText -> shareCard(shareText) },
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val cardId = parseCardIdFromIntent(intent)
        if (cardId != null) {
            navigationState.value = MobileDestination.Card(cardId)
        }
    }

    private fun shareCard(text: String) {
        if (text.isBlank()) {
            showNotEnoughDataToast()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, getString(R.string.card_share_chooser_title))
        try {
            startActivity(chooser)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.card_share_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNotEnoughDataToast() {
        Toast.makeText(this, getString(R.string.card_error_not_enough_data), Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PointToSkyMobileApp(
    destination: MobileDestination,
    onNavigate: (MobileDestination) -> Unit,
    latestCardAvailable: Boolean,
    onOpenLatestCard: () -> Unit,
    onOpenAr: () -> Unit,
    locationPrefs: LocationPrefs,
    shareState: PhoneLocationBridge.PhoneLocationBridgeState,
    onShareToggle: (Boolean) -> Unit,
    catalogRepository: CatalogRepository,
    aimTargets: List<AimTargetOption>,
    onSendAimTarget: (AimTargetOption) -> Unit,
    onShareCard: (String) -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val current = destination) {
                MobileDestination.Home -> MobileHome(
                    onOpenCard = onOpenLatestCard,
                    onSkyMap = { onNavigate(MobileDestination.SkyMap) },
                    onAr = onOpenAr,
                    onLocationSetup = { onNavigate(MobileDestination.LocationSetup) },
                    onTimeDebug = { onNavigate(MobileDestination.TimeDebug) },
                    onCatalogDebug = { onNavigate(MobileDestination.CatalogDebug) },
                    aimTargets = aimTargets,
                    onSendAimTarget = onSendAimTarget,
                    cardAvailable = latestCardAvailable,
                )

                MobileDestination.LocationSetup -> LocationSetupScreen(
                    locationPrefs = locationPrefs,
                    shareState = shareState,
                    onShareToggle = onShareToggle,
                    onBack = { onNavigate(MobileDestination.Home) }
                )

                MobileDestination.TimeDebug -> TimeDebugScreen(
                    onBack = { onNavigate(MobileDestination.Home) }
                )

                MobileDestination.CatalogDebug -> CatalogDebugRoute(
                    factory = CatalogDebugViewModelFactory(catalogRepository),
                    modifier = Modifier.fillMaxSize(),
                    onBack = { onNavigate(MobileDestination.Home) },
                )

                MobileDestination.Ar -> ArRoute(
                    catalogRepository = catalogRepository,
                    locationPrefs = locationPrefs,
                    onBack = { onNavigate(MobileDestination.Home) },
                    onSendAimTarget = onSendAimTarget,
                    modifier = Modifier.fillMaxSize(),
                )

                MobileDestination.SkyMap -> SkyMapRoute(
                    catalogRepository = catalogRepository,
                    locationPrefs = locationPrefs,
                    onBack = { onNavigate(MobileDestination.Home) },
                    onOpenCard = onOpenLatestCard,
                    modifier = Modifier.fillMaxSize(),
                )

                is MobileDestination.Card -> CardRoute(
                    cardId = current.cardId,
                    locationPrefs = locationPrefs,
                    onBack = { onNavigate(MobileDestination.Home) },
                    onSendAimTarget = onSendAimTarget,
                    onShare = onShareCard,
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
    onAr: () -> Unit,
    onLocationSetup: () -> Unit,
    onTimeDebug: () -> Unit,
    onCatalogDebug: () -> Unit,
    aimTargets: List<AimTargetOption>,
    onSendAimTarget: (AimTargetOption) -> Unit,
    cardAvailable: Boolean,
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
            enabled = cardAvailable,
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
            onClick = onAr,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(text = stringResource(id = R.string.ar_mode))
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

private sealed interface MobileDestination {
    object Home : MobileDestination
    object SkyMap : MobileDestination
    object LocationSetup : MobileDestination
    object TimeDebug : MobileDestination
    object CatalogDebug : MobileDestination
    object Ar : MobileDestination
    data class Card(val cardId: String) : MobileDestination
}

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
        destination = MobileDestination.Home,
        onNavigate = {},
        latestCardAvailable = true,
        onOpenLatestCard = {},
        onOpenAr = {},
        locationPrefs = PreviewLocationPrefs(),
        shareState = PhoneLocationBridge.PhoneLocationBridgeState.Empty,
        onShareToggle = {},
        catalogRepository = CatalogRepository.create(context),
        aimTargets = emptyList(),
        onSendAimTarget = {},
        onShareCard = {},
    )
}
