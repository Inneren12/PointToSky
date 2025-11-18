package dev.pointtosky.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import dev.pointtosky.core.datalayer.AppOpenAimTarget
import dev.pointtosky.core.datalayer.AppOpenMessage
import dev.pointtosky.core.datalayer.AppOpenScreen
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.datalayer.PATH_APP_OPEN
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.mobile.ar.ArRoute
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.card.CardRoute
import dev.pointtosky.mobile.card.parseCardIdFromIntent
import dev.pointtosky.mobile.catalog.CatalogDebugRoute
import dev.pointtosky.mobile.catalog.CatalogRepositoryProvider
import dev.pointtosky.mobile.crash.CrashLogRoute
import dev.pointtosky.mobile.datalayer.AimTargetOption
import dev.pointtosky.mobile.datalayer.DemoAimTargets
import dev.pointtosky.mobile.datalayer.MobileBridge
import dev.pointtosky.mobile.location.LocationSetupScreen
import dev.pointtosky.mobile.location.share.PhoneLocationBridge
import dev.pointtosky.mobile.logging.MobileLog
import dev.pointtosky.mobile.onboarding.MobileOnboardingPrefs
import dev.pointtosky.mobile.onboarding.OnboardingScreen
import dev.pointtosky.mobile.onboarding.from
import dev.pointtosky.mobile.policy.PolicyDocument
import dev.pointtosky.mobile.policy.PolicyDocumentScreen
import dev.pointtosky.mobile.policy.PolicyScreen
import dev.pointtosky.mobile.search.SearchRoute
import dev.pointtosky.mobile.sensors.PhoneCompassBridge
import dev.pointtosky.mobile.settings.LocationMode
import dev.pointtosky.mobile.settings.MobileSettings
import dev.pointtosky.mobile.settings.MobileSettingsState
import dev.pointtosky.mobile.settings.SettingsScreen
import dev.pointtosky.mobile.settings.from
import dev.pointtosky.mobile.skymap.SkyMapRoute
import dev.pointtosky.mobile.tile.tonight.TonightPreviewActivity
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

    private val phoneCompassBridge: PhoneCompassBridge by lazy {
        PhoneCompassBridge(this)
    }

    private val catalogRepository: CatalogRepository by lazy {
        CatalogRepositoryProvider.get(applicationContext)
    }

    private val mobileSettings: MobileSettings by lazy {
        MobileSettings.from(applicationContext)
    }

    private val onboardingPrefs: MobileOnboardingPrefs by lazy {
        MobileOnboardingPrefs.from(applicationContext)
    }

    private val navigationState = MutableStateFlow<MobileDestination>(MobileDestination.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val bridgeState by phoneLocationBridge.state.collectAsStateWithLifecycle()
            val destination by navigationState.collectAsStateWithLifecycle()
            val latestCardId by CardRepository.latestCardIdFlow().collectAsStateWithLifecycle(initialValue = null)
            val phoneCompassEnabled by phoneCompassBridge.enabled.collectAsStateWithLifecycle()
            val settingsState by mobileSettings.state.collectAsStateWithLifecycle(initialValue = MobileSettingsState())
            val onboardingAccepted by onboardingPrefs.acceptedFlow.collectAsStateWithLifecycle(initialValue = false)
            val coroutineScope = rememberCoroutineScope()
            val aimTargets = remember { DemoAimTargets.list() }
            val appContext = this@MainActivity.applicationContext
            val dataLayerBridge: MobileBridge.Sender =
                remember {
                    MobileBridge.get(appContext)
                }
            LaunchedEffect(onboardingAccepted, destination) {
                if (!onboardingAccepted && destination != MobileDestination.Onboarding) {
                    navigationState.value = MobileDestination.Onboarding
                } else if (onboardingAccepted && destination == MobileDestination.Onboarding) {
                    navigationState.value = MobileDestination.Home
                }
            }
            val openLatestCard: () -> Unit = {
                val id = latestCardId
                if (id != null) {
                    navigationState.value = MobileDestination.Card(id)
                } else {
                    showNotEnoughDataToast()
                }
            }
            val sendAppOpen: (AppOpenScreen, AppOpenAimTarget?) -> Unit = { screen, target ->
                coroutineScope.launch {
                    dataLayerBridge.send(PATH_APP_OPEN) { cid ->
                        val sanitizedTarget = if (settingsState.redactPayloads) null else target
                        val message =
                            AppOpenMessage(
                                cid = cid,
                                screen = screen,
                                target = sanitizedTarget,
                            )
                        JsonCodec.encode(message)
                    }
                }
            }
            PointToSkyMobileApp(
                destination = destination,
                deps =
                    MobileAppDeps(
                        locationPrefs = locationPrefs,
                        shareState = bridgeState,
                        catalogRepository = catalogRepository,
                        aimTargets = aimTargets,
                        phoneCompassEnabled = phoneCompassEnabled,
                        settingsState = settingsState,
                        latestCardAvailable = (latestCardId != null),
                    ),
                actions =
                    MobileAppActions(
                        onNavigate = { navigationState.value = it },
                        onOpenLatestCard = openLatestCard,
                        onOpenSearch = { navigationState.value = MobileDestination.Search },
                        onOpenAr = {
                            if (settingsState.arEnabled) {
                                navigationState.value = MobileDestination.Ar
                            } else {
                                Toast
                                    .makeText(
                                        this@MainActivity,
                                        getString(R.string.settings_ar_disabled_toast),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                            MobileLog.arOpen()
                            navigationState.value = MobileDestination.Ar
                        },
                        onOpenWearMenu = { navigationState.value = MobileDestination.WearMenu },
                        onOpenLocationSetup = { navigationState.value = MobileDestination.LocationSetup },
                        onOpenTimeDebug = { navigationState.value = MobileDestination.TimeDebug },
                        onOpenCatalogDebug = { navigationState.value = MobileDestination.CatalogDebug },
                        onOpenCrashLogs = { navigationState.value = MobileDestination.CrashLogs },
                        onShareToggle = { enabled ->
                            coroutineScope.launch { phoneLocationBridge.setShareEnabled(enabled) }
                        },
                        onSendAimTarget = { target ->
                            coroutineScope.launch {
                                if (settingsState.redactPayloads) {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                this@MainActivity,
                                                getString(R.string.settings_redact_payloads_blocked),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    return@launch
                                }
                                MobileLog.setTargetRequest(target.id)
                                val startElapsed = SystemClock.elapsedRealtime()
                                val ack =
                                    dataLayerBridge.send(PATH_AIM_SET_TARGET) { cid ->
                                        val message = target.buildMessage(cid)
                                        JsonCodec.encode(message)
                                    }
                                val duration = SystemClock.elapsedRealtime() - startElapsed
                                MobileLog.setTargetAck(ack?.ok == true, duration)
                                val openTarget = target.buildMessage("app-open")
                                sendAppOpen(
                                    AppOpenScreen.AIM,
                                    AppOpenAimTarget(
                                        kind = openTarget.kind,
                                        payload = openTarget.payload,
                                    ),
                                )
                                val toastText =
                                    when {
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
                        onOpenAimOnWatch = { sendAppOpen(AppOpenScreen.AIM, null) },
                        onOpenIdentifyOnWatch = { sendAppOpen(AppOpenScreen.IDENTIFY, null) },
                        onTogglePhoneCompass = {
                            val desired = !phoneCompassEnabled
                            val actual = phoneCompassBridge.setEnabled(desired)
                            if (desired && !actual) {
                                Toast
                                    .makeText(
                                        this@MainActivity,
                                        getString(R.string.phone_compass_not_available),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        },
                        onShareCard = { shareText -> shareCard(shareText) },
                        onOpenSettings = { navigationState.value = MobileDestination.Settings },
                        onToggleMirror = { enabled ->
                            coroutineScope.launch { mobileSettings.setMirrorEnabled(enabled) }
                        },
                        onToggleArSetting = { enabled ->
                            coroutineScope.launch { mobileSettings.setArEnabled(enabled) }
                        },
                        onChangeLocationMode = { mode ->
                            coroutineScope.launch { mobileSettings.setLocationMode(mode) }
                        },
                        onToggleRedactPayloads = { enabled ->
                            coroutineScope.launch { mobileSettings.setRedactPayloads(enabled) }
                        },
                        onOpenPolicy = { navigationState.value = MobileDestination.Policy },
                        onOpenMirrorPreview = { openMirrorPreview() },
                        onCompleteOnboarding = {
                            coroutineScope.launch { onboardingPrefs.setAccepted(true) }
                        },
                        onOpenPolicyDocument = { document ->
                            navigationState.value = MobileDestination.PolicyDocument(document)
                        },
                    ),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        phoneLocationBridge.start()
        phoneCompassBridge.start()
    }

    override fun onStop() {
        super.onStop()
        phoneLocationBridge.stop()
        phoneCompassBridge.stop()
    }

    // onNewIntent принимает non-null Intent в базовом Activity
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        val chooser = Intent.createChooser(intent, getString(R.string.card_share_chooser_title))
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.card_share_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMirrorPreview() {
        val intent = Intent(this, TonightPreviewActivity::class.java)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.settings_mirror_preview_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNotEnoughDataToast() {
        Toast.makeText(this, getString(R.string.card_error_not_enough_data), Toast.LENGTH_SHORT).show()
    }
}

@Immutable
data class MobileAppDeps(
    val locationPrefs: LocationPrefs,
    val shareState: PhoneLocationBridge.PhoneLocationBridgeState,
    val catalogRepository: CatalogRepository,
    val aimTargets: List<AimTargetOption>,
    val phoneCompassEnabled: Boolean,
    val settingsState: MobileSettingsState,
    val latestCardAvailable: Boolean,
)

@Immutable
data class MobileAppActions(
    val onNavigate: (MobileDestination) -> Unit,
    val onOpenLatestCard: () -> Unit,
    val onOpenSearch: () -> Unit,
    val onOpenAr: () -> Unit,
    val onOpenWearMenu: () -> Unit,
    val onOpenLocationSetup: () -> Unit,
    val onOpenTimeDebug: () -> Unit,
    val onOpenCatalogDebug: () -> Unit,
    val onOpenCrashLogs: () -> Unit,
    val onShareToggle: (Boolean) -> Unit,
    val onSendAimTarget: (AimTargetOption) -> Unit,
    val onOpenAimOnWatch: () -> Unit,
    val onOpenIdentifyOnWatch: () -> Unit,
    val onTogglePhoneCompass: () -> Unit,
    val onShareCard: (String) -> Unit,
    val onOpenSettings: () -> Unit,
    val onToggleMirror: (Boolean) -> Unit,
    val onToggleArSetting: (Boolean) -> Unit,
    val onChangeLocationMode: (LocationMode) -> Unit,
    val onToggleRedactPayloads: (Boolean) -> Unit,
    val onOpenPolicy: () -> Unit,
    val onOpenMirrorPreview: () -> Unit,
    val onCompleteOnboarding: () -> Unit,
    val onOpenPolicyDocument: (PolicyDocument) -> Unit,
)

@Immutable
data class MobileHomeProps(
    val cardAvailable: Boolean,
    val arEnabled: Boolean,
)

@Immutable
data class MobileHomeActions(
    val onOpenCard: () -> Unit,
    val onSkyMap: () -> Unit,
    val onSearch: () -> Unit,
    val onAr: () -> Unit,
    val onOpenWearMenu: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun PointToSkyMobileApp(
    destination: MobileDestination,
    deps: MobileAppDeps,
    actions: MobileAppActions,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val current = destination) {
                MobileDestination.Onboarding ->
                    OnboardingScreen(
                        onComplete = actions.onCompleteOnboarding,
                        modifier = Modifier.fillMaxSize(),
                    )

                MobileDestination.Home ->
                    MobileHome(
                        props =
                            MobileHomeProps(
                                cardAvailable = deps.latestCardAvailable,
                                arEnabled = deps.settingsState.arEnabled,
                            ),
                        actions =
                            MobileHomeActions(
                                onOpenCard = actions.onOpenLatestCard,
                                onSkyMap = { actions.onNavigate(MobileDestination.SkyMap) },
                                onSearch = actions.onOpenSearch,
                                onAr = actions.onOpenAr,
                                onOpenWearMenu = actions.onOpenWearMenu,
                                onOpenSettings = actions.onOpenSettings,
                            ),
                    )

                MobileDestination.LocationSetup ->
                    LocationSetupScreen(
                        locationPrefs = deps.locationPrefs,
                        shareState = deps.shareState,
                        onShareToggle = actions.onShareToggle,
                        onBack = { actions.onNavigate(MobileDestination.Settings) },
                    )

                MobileDestination.TimeDebug ->
                    TimeDebugScreen(
                        onBack = { actions.onNavigate(MobileDestination.Settings) },
                    )

                MobileDestination.CatalogDebug ->
                    CatalogDebugRoute(
                        factory = CatalogDebugViewModelFactory(deps.catalogRepository),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { actions.onNavigate(MobileDestination.Settings) },
                    )

                MobileDestination.CrashLogs ->
                    CrashLogRoute(
                        onBack = { actions.onNavigate(MobileDestination.Settings) },
                    )

                MobileDestination.Ar ->
                    ArRoute(
                        identifySolver = deps.catalogRepository.identifySolver,
                        locationPrefs = deps.locationPrefs,
                        onBack = { actions.onNavigate(MobileDestination.Home) },
                        onSendAimTarget = actions.onSendAimTarget,
                    )

                MobileDestination.WearMenu ->
                    WearMenuScreen(
                        props = WearMenuProps(aimTargets = deps.aimTargets),
                        actions =
                            WearMenuActions(
                                onSendAimTarget = actions.onSendAimTarget,
                                onOpenAimOnWatch = actions.onOpenAimOnWatch,
                                onOpenIdentifyOnWatch = actions.onOpenIdentifyOnWatch,
                                onBack = { actions.onNavigate(MobileDestination.Home) },
                            ),
                        modifier = Modifier.fillMaxSize(),
                    )

                MobileDestination.SkyMap ->
                    SkyMapRoute(
                        catalogRepository = deps.catalogRepository,
                        locationPrefs = deps.locationPrefs,
                        onBack = { actions.onNavigate(MobileDestination.Home) },
                        onOpenCard = actions.onOpenLatestCard,
                        modifier = Modifier.fillMaxSize(),
                    )

                MobileDestination.Search ->
                    SearchRoute(
                        catalogRepository = deps.catalogRepository,
                        onBack = { actions.onNavigate(MobileDestination.Home) },
                        onOpenCard = { cardId -> actions.onNavigate(MobileDestination.Card(cardId)) },
                        modifier = Modifier.fillMaxSize(),
                    )

                MobileDestination.Settings ->
                    SettingsScreen(
                        state = deps.settingsState,
                        phoneCompassEnabled = deps.phoneCompassEnabled,
                        onMirrorChanged = actions.onToggleMirror,
                        onArChanged = actions.onToggleArSetting,
                        onLocationModeChanged = actions.onChangeLocationMode,
                        onRedactPayloadsChanged = actions.onToggleRedactPayloads,
                        onTogglePhoneCompass = actions.onTogglePhoneCompass,
                        onOpenLocationSetup = actions.onOpenLocationSetup,
                        onOpenTimeDebug = actions.onOpenTimeDebug,
                        onOpenCatalogDebug = actions.onOpenCatalogDebug,
                        onOpenCrashLogs = actions.onOpenCrashLogs,
                        onOpenMirrorPreview = actions.onOpenMirrorPreview,
                        onOpenPolicy = actions.onOpenPolicy,
                        onBack = { actions.onNavigate(MobileDestination.Home) },
                    )

                MobileDestination.Policy ->
                    PolicyScreen(
                        onOpenDocument = actions.onOpenPolicyDocument,
                        onBack = { actions.onNavigate(MobileDestination.Settings) },
                    )

                is MobileDestination.PolicyDocument ->
                    PolicyDocumentScreen(
                        document = current.document,
                        onBack = { actions.onNavigate(MobileDestination.Policy) },
                    )

                is MobileDestination.Card ->
                    CardRoute(
                        cardId = current.cardId,
                        locationPrefs = deps.locationPrefs,
                        onBack = { actions.onNavigate(MobileDestination.Home) },
                        onSendAimTarget = actions.onSendAimTarget,
                        onShare = actions.onShareCard,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
fun MobileHome(
    props: MobileHomeProps,
    actions: MobileHomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Point-to-Sky Mobile", style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = actions.onOpenCard,
            enabled = props.cardAvailable,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(text = stringResource(id = R.string.open_card))
        }
        Button(
            onClick = actions.onSearch,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.search_button))
        }
        Button(
            onClick = {
                MobileLog.mapOpen()
                actions.onSkyMap()
            },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.sky_map))
        }
        if (props.arEnabled) {
            Button(
                onClick = actions.onAr,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = stringResource(id = R.string.ar_mode))
            }
        }
        Button(
            onClick = actions.onOpenWearMenu,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.wear_menu_title))
        }
        Button(
            onClick = actions.onOpenSettings,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.settings_button))
        }
    }
}

@Immutable
data class WearMenuProps(
    val aimTargets: List<AimTargetOption>,
)

data class WearMenuActions(
    val onSendAimTarget: (AimTargetOption) -> Unit,
    val onOpenAimOnWatch: () -> Unit,
    val onOpenIdentifyOnWatch: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun WearMenuScreen(
    props: WearMenuProps,
    actions: WearMenuActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(id = R.string.wear_menu_title), style = MaterialTheme.typography.headlineSmall)
        props.aimTargets.forEach { target ->
            Button(
                onClick = { actions.onSendAimTarget(target) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = stringResource(id = R.string.aim_send_button, target.label))
            }
        }
        Button(
            onClick = actions.onOpenAimOnWatch,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.open_aim_on_watch))
        }
        Button(
            onClick = actions.onOpenIdentifyOnWatch,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.open_identify_on_watch))
        }
        Button(
            onClick = actions.onBack,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(text = stringResource(id = R.string.time_debug_back))
        }
    }
}

sealed interface MobileDestination {
    object Onboarding : MobileDestination

    object Home : MobileDestination

    object SkyMap : MobileDestination

    object Search : MobileDestination

    object LocationSetup : MobileDestination

    object TimeDebug : MobileDestination

    object CatalogDebug : MobileDestination

    object CrashLogs : MobileDestination

    object Ar : MobileDestination

    object WearMenu : MobileDestination

    object Settings : MobileDestination

    object Policy : MobileDestination

    data class PolicyDocument(
        val document: dev.pointtosky.mobile.policy.PolicyDocument,
    ) : MobileDestination

    data class Card(
        val cardId: String,
    ) : MobileDestination
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
        deps =
            MobileAppDeps(
                locationPrefs = PreviewLocationPrefs(),
                shareState = PhoneLocationBridge.PhoneLocationBridgeState.Empty,
                catalogRepository = CatalogRepository.create(context),
                aimTargets = emptyList(),
                phoneCompassEnabled = false,
                settingsState = MobileSettingsState(),
                latestCardAvailable = true,
            ),
        actions =
            MobileAppActions(
                onNavigate = { _ -> },
                onOpenLatestCard = {},
                onOpenSearch = {},
                onOpenAr = {},
                onOpenWearMenu = {},
                onOpenLocationSetup = {},
                onOpenTimeDebug = {},
                onOpenCatalogDebug = {},
                onOpenCrashLogs = {},
                onShareToggle = { _ -> },
                onSendAimTarget = { _ -> },
                onOpenAimOnWatch = {},
                onOpenIdentifyOnWatch = {},
                onTogglePhoneCompass = {},
                onShareCard = { _ -> },
                onOpenSettings = {},
                onToggleMirror = { _ -> },
                onToggleArSetting = { _ -> },
                onChangeLocationMode = { _ -> },
                onToggleRedactPayloads = { _ -> },
                onOpenPolicy = {},
                onOpenMirrorPreview = {},
                onCompleteOnboarding = {},
                onOpenPolicyDocument = { _ -> },
            ),
    )
}
