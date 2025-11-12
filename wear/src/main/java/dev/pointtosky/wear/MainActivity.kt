package dev.pointtosky.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.tooling.preview.devices.WearDevices
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModelFactory
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AppOpenMessage
import dev.pointtosky.core.datalayer.AppOpenScreen
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.SensorHeadingMessage
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.orchestrator.DefaultLocationOrchestrator
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.aim.ui.AimRoute
import dev.pointtosky.wear.astro.AstroDebugRoute
import dev.pointtosky.wear.astro.AstroDebugViewModelFactory
import dev.pointtosky.wear.catalog.CatalogRepositoryProvider
import dev.pointtosky.wear.catalogdebug.CatalogDebugRoute
import dev.pointtosky.wear.crash.CrashLogRoute
import dev.pointtosky.wear.datalayer.AimLaunchRequest
import dev.pointtosky.wear.datalayer.AppOpenRequest
import dev.pointtosky.wear.datalayer.PhoneHeadingBridge
import dev.pointtosky.wear.datalayer.WearBridge
import dev.pointtosky.wear.datalayer.v1.DlIntents
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.ACTION_OPEN_AIM_LEGACY
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY_LEGACY
import dev.pointtosky.wear.EXTRA_AIM_BODY
import dev.pointtosky.wear.EXTRA_AIM_DEC_DEG
import dev.pointtosky.wear.EXTRA_AIM_RA_DEG
import dev.pointtosky.wear.EXTRA_AIM_STAR_ID
import dev.pointtosky.wear.EXTRA_AIM_TARGET_KIND
import dev.pointtosky.wear.MAX_DEEP_LINK_PAYLOAD_BYTES
import dev.pointtosky.wear.identify.IdentifyRoute
import dev.pointtosky.wear.identify.IdentifyViewModelFactory
import dev.pointtosky.wear.identify.buildCardRouteFrom
import dev.pointtosky.wear.identify.cardDestination
import dev.pointtosky.wear.location.LocationSetupScreen
import dev.pointtosky.wear.location.remote.PhoneLocationRepository
import dev.pointtosky.wear.onboarding.WearOnboardingPrefs
import dev.pointtosky.wear.onboarding.WearOnboardingScreen
import dev.pointtosky.wear.onboarding.from
import dev.pointtosky.wear.sensors.SensorsCalibrateScreen
import dev.pointtosky.wear.sensors.SensorsDebugScreen
import dev.pointtosky.wear.sensors.SensorsViewModel
import dev.pointtosky.wear.sensors.SensorsViewModelFactory
import dev.pointtosky.wear.sensors.orientation.OrientationFrameDefaults
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationRepositoryConfig
import dev.pointtosky.wear.sensors.orientation.PhoneHeadingOverrideRepository
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore
import dev.pointtosky.wear.settings.SettingsRoute
import dev.pointtosky.wear.time.TimeDebugScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var dlReceiver: BroadcastReceiver? = null

    /** Для защиты от повторной обработки того же самого интента. */
    private var lastIntentSignature: Int? = null
    private val hasMagnetometer: Boolean by lazy {
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
    }

    private val orientationRepository: OrientationRepository by lazy {
        val base = OrientationRepository.create(applicationContext)
        if (hasMagnetometer) base else PhoneHeadingOverrideRepository(base)
    }

    private val locationPrefs: LocationPrefs by lazy {
        LocationPrefs.fromContext(applicationContext)
    }

    private val phoneLocationRepository: PhoneLocationRepository by lazy {
        PhoneLocationRepository(applicationContext)
    }

    private val locationOrchestrator: DefaultLocationOrchestrator by lazy {
        DefaultLocationOrchestrator(
            fused = null,
            manualPrefs = locationPrefs,
            remotePhone = phoneLocationRepository,
        )
    }

    private val onboardingPrefs: WearOnboardingPrefs by lazy {
        WearOnboardingPrefs.from(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleBridgeIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                orientationRepository.start(OrientationRepositoryConfig())
                try {
                    awaitCancellation()
                } finally {
                    orientationRepository.stop()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationOrchestrator.start(LocationConfig())
                try {
                    awaitCancellation()
                } finally {
                    locationOrchestrator.stop()
                }
            }
        }

        setContent {
            PointToSkyWearApp(
                orientationRepository = orientationRepository,
                locationPrefs = locationPrefs,
                phoneLocationRepository = phoneLocationRepository,
                locationRepository = locationOrchestrator,
                aimLaunches = WearBridge.aimLaunches(),
                appOpens = WearBridge.appOpens(),
                onboardingPrefs = onboardingPrefs,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // важно: чтобы Activity.intent тоже обновился
        setIntent(intent)
        handleBridgeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (dlReceiver != null) return
        dlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val payload = intent.getByteArrayExtra(DlIntents.EXTRA_PAYLOAD) ?: return

                // 1) SensorHeading: обновляем мост и выходим
                runCatching { JsonCodec.decode<SensorHeadingMessage>(payload) }
                    .onSuccess { msg ->
                        if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                            PhoneHeadingBridge.updateHeading(msg.azDeg, msg.ts)
                            return
                        }
                    }

                // 2) AppOpen → мост сам разрулит, если нужно
                runCatching { JsonCodec.decode<AppOpenMessage>(payload) }
                    .onSuccess {
                        WearBridge.handleAppOpenMessage(applicationContext, it)
                        return
                    }

                // 3) AimSetTarget
                runCatching { JsonCodec.decode<AimSetTargetMessage>(payload) }
                    .onSuccess { WearBridge.handleAimSetTargetMessage(applicationContext, it) }
            }
        }
        ContextCompat.registerReceiver(
            /* context = */
            this,
            /* receiver = */
            dlReceiver,
            /* filter = */
            DlIntents.filter(),
            /* flags = */
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        dlReceiver?.let { unregisterReceiver(it) }
        dlReceiver = null
    }

    private fun handleBridgeIntent(intent: Intent?) {
        if (intent == null) return
        // Отсекаем дубликаты (например, singleTop + повторная доставка того же Intent)
        val sig = buildIntentSignature(intent)
        if (sig != null && sig == lastIntentSignature) return
        lastIntentSignature = sig
        when (intent.action) {
            ACTION_OPEN_AIM, ACTION_OPEN_AIM_LEGACY -> handleAimIntent(intent)
            ACTION_OPEN_IDENTIFY, ACTION_OPEN_IDENTIFY_LEGACY ->
                WearBridge.emitAppOpen(AppOpenScreen.IDENTIFY, null)
        }
    }

    /**
     * Формируем компактную подпись интента только по тем полям, которые
     * реально влияют на навигацию. Если поля не заданы — это всё равно валидная подпись.
     */
    private fun buildIntentSignature(intent: Intent?): Int? {
        intent ?: return null
        val sb = StringBuilder()
            .append(intent.action.orEmpty())
            .append('|')
            .append(intent.getStringExtra(EXTRA_AIM_TARGET_KIND).orEmpty())
            .append('|')
            .append(intent.getDoubleExtra(EXTRA_AIM_RA_DEG, Double.NaN))
            .append('|')
            .append(intent.getDoubleExtra(EXTRA_AIM_DEC_DEG, Double.NaN))
            .append('|')
            .append(intent.getStringExtra(EXTRA_AIM_BODY).orEmpty())
            .append('|')
            .append(intent.getIntExtra(EXTRA_AIM_STAR_ID, Int.MIN_VALUE))
        return sb.toString().hashCode()
    }

    private fun handleAimIntent(intent: Intent) {
        val target = intent.extractAimTarget()
        if (target != null) {
            WearBridge.emitAimTarget(target)
        } else {
            WearBridge.emitAppOpen(AppOpenScreen.AIM, null)
        }
    }

    private fun Intent.extractAimTarget(): AimTarget? {
        val extrasSize = extrasSizeBytes() ?: return null
        if (extrasSize > MAX_DEEP_LINK_PAYLOAD_BYTES) {
            LogBus.event(
                "deeplink_rejected",
                mapOf("reason" to "extras_too_large", "bytes" to extrasSize),
            )
            return null
        }

        val kindRaw = getStringExtra(EXTRA_AIM_TARGET_KIND)
        val kind = kindRaw?.lowercase()?.trim()
        return when (kind) {
            "equatorial" -> {
                val ra = getDoubleExtra(EXTRA_AIM_RA_DEG, Double.NaN)
                val dec = getDoubleExtra(EXTRA_AIM_DEC_DEG, Double.NaN)
                if (!ra.isFinite() || !dec.isFinite()) {
                    LogBus.event(
                        "deeplink_rejected",
                        mapOf("reason" to "invalid_equatorial", "ra" to ra, "dec" to dec),
                    )
                    null
                } else {
                    AimTarget.EquatorialTarget(Equatorial(raDeg = ra, decDeg = dec))
                }
            }

            "body" -> {
                val bodyName = getStringExtra(EXTRA_AIM_BODY)?.takeIf { it.isNotBlank() }
                val body = bodyName?.let { runCatching { Body.valueOf(it) }.getOrNull() }
                if (body == null) {
                    LogBus.event(
                        "deeplink_rejected",
                        mapOf("reason" to "invalid_body", "body" to (bodyName ?: "")),
                    )
                    null
                } else {
                    AimTarget.BodyTarget(body)
                }
            }

            "star" -> {
                val hasId = hasExtra(EXTRA_AIM_STAR_ID)
                val starId = if (hasId) getIntExtra(EXTRA_AIM_STAR_ID, Int.MIN_VALUE) else Int.MIN_VALUE
                if (starId == Int.MIN_VALUE) {
                    LogBus.event("deeplink_rejected", mapOf("reason" to "invalid_star_id"))
                    null
                } else {
                    val ra = getDoubleExtra(EXTRA_AIM_RA_DEG, Double.NaN)
                    val dec = getDoubleExtra(EXTRA_AIM_DEC_DEG, Double.NaN)
                    val eq = if (ra.isFinite() && dec.isFinite()) {
                        Equatorial(raDeg = ra, decDeg = dec)
                    } else {
                        null
                    }
                    AimTarget.StarTarget(starId, eq)
                }
            }

            null -> {
                LogBus.event("deeplink_rejected", mapOf("reason" to "missing_kind"))
                null
            }

            else -> {
                LogBus.event(
                    "deeplink_rejected",
                    mapOf("reason" to "unknown_kind", "kind" to kindRaw),
                )
                null
            }
        }
    }

    private fun Intent.extrasSizeBytes(): Int? {
        val extras = extras ?: return 0
        return try {
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(extras)
                parcel.dataSize()
            } finally {
                parcel.recycle()
            }
        } catch (t: Throwable) {
            LogBus.event(
                "deeplink_rejected",
                mapOf("reason" to "extras_serialize_fail", "err" to (t.message ?: t::class.java.simpleName)),
            )
            null
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_AIM = "aim"
private const val ROUTE_IDENTIFY = "identify"
private const val ROUTE_ASTRO_DEBUG = "astro_debug"
private const val ROUTE_SENSORS_DEBUG = "sensors_debug"
private const val ROUTE_SENSORS_CALIBRATE = "sensors_calibrate"
private const val ROUTE_LOCATION = "location"
private const val ROUTE_TIME_DEBUG = "time_debug"
private const val ROUTE_CATALOG_DEBUG = "catalog_debug"
private const val ROUTE_CRASH_LOGS = "crash_logs"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun PointToSkyWearApp(
    orientationRepository: OrientationRepository,
    locationPrefs: LocationPrefs,
    phoneLocationRepository: PhoneLocationRepository,
    locationRepository: DefaultLocationOrchestrator,
    aimLaunches: Flow<AimLaunchRequest>,
    appOpens: Flow<AppOpenRequest>,
    onboardingPrefs: WearOnboardingPrefs,
) {
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val catalogRepository = remember(appContext) { CatalogRepositoryProvider.get(appContext) }
    val settings = remember(appContext) { AimIdentifySettingsDataStore(appContext) }
    val coroutineScope = rememberCoroutineScope()
    val onboardingAccepted by onboardingPrefs.acceptedFlow.collectAsStateWithLifecycle(initialValue = false)

    val viewModelFactory = remember(orientationRepository, appContext) {
        SensorsViewModelFactory(
            appContext = appContext,
            orientationRepository = orientationRepository,
        )
    }
    val sensorsViewModel: SensorsViewModel = viewModel(factory = viewModelFactory)

    val latestAim = remember { mutableStateOf<AimLaunchRequest?>(null) }
    val latestAppOpen = remember { mutableStateOf<AppOpenRequest?>(null) }
    LaunchedEffect(aimLaunches) {
        aimLaunches.collect { latestAim.value = it }
    }
    LaunchedEffect(appOpens) {
        appOpens.collect { latestAppOpen.value = it }
    }

    val aimRequest = latestAim.value
    val appOpenRequest = latestAppOpen.value

    MaterialTheme {
        if (!onboardingAccepted) {
            WearOnboardingScreen(
                onComplete = {
                    coroutineScope.launch { onboardingPrefs.setAccepted(true) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = ROUTE_HOME,
            ) {
                composable(ROUTE_HOME) {
                    HomeScreen(
                        onAimClick = { navController.navigate(ROUTE_AIM) },
                        onIdentifyClick = { navController.navigate(ROUTE_IDENTIFY) },
                        onAstroDebugClick = { navController.navigate(ROUTE_ASTRO_DEBUG) },
                        onCatalogDebugClick = { navController.navigate(ROUTE_CATALOG_DEBUG) },
                        onSensorsDebugClick = { navController.navigate(ROUTE_SENSORS_DEBUG) },
                        onLocationClick = { navController.navigate(ROUTE_LOCATION) },
                        onTimeDebugClick = { navController.navigate(ROUTE_TIME_DEBUG) },
                        onCrashLogsClick = { navController.navigate(ROUTE_CRASH_LOGS) },
                        onSettingsClick = { navController.navigate(ROUTE_SETTINGS) },
                    )
                }
                composable(ROUTE_AIM) {
                    // Экран Aim с реальным контроллером (S6.B)
                    AimRoute(
                        orientationRepository = orientationRepository,
                        locationRepository = locationRepository,
                        externalAim = aimRequest,
                        initialTarget = appOpenRequest?.target,
                    )
                }
                composable(ROUTE_IDENTIFY) {
                    // Экран Identify (S6.C) с реальным каталогом и ориентацией
                    val factory = remember(orientationRepository, locationRepository, catalogRepository, settings) {
                        IdentifyViewModelFactory(
                            orientationRepository = orientationRepository,
                            locationRepository = locationRepository,
                            catalogRepository = catalogRepository,
                            settings = settings,
                        )
                    }
                    IdentifyRoute(
                        factory = factory,
                        onOpenCard = { state -> navController.navigate(buildCardRouteFrom(state)) },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsRoute(
                        settings = settings,
                        onBack = { navController.popBackStack() },
                    )
                }
                // Параметризованный экран карточки (S6.D)
                cardDestination(locationRepository = locationRepository)
                composable(ROUTE_ASTRO_DEBUG) {
                    val factory = remember(orientationRepository, locationRepository, catalogRepository) {
                        AstroDebugViewModelFactory(
                            orientationRepository = orientationRepository,
                            locationRepository = locationRepository,
                            catalogRepository = catalogRepository,
                        )
                    }
                    AstroDebugRoute(
                        factory = factory,
                    )
                }
                composable(ROUTE_CATALOG_DEBUG) {
                    val factory: CatalogDebugViewModelFactory = remember(catalogRepository) {
                        CatalogDebugViewModelFactory(catalogRepository)
                    }
                    CatalogDebugRoute(
                        factory = factory,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(ROUTE_SENSORS_DEBUG) {
                    val frame by sensorsViewModel.frames.collectAsStateWithLifecycle(
                        initialValue = OrientationFrameDefaults.EMPTY,
                    )
                    val zero by sensorsViewModel.zero.collectAsStateWithLifecycle()
                    val screenRotation by sensorsViewModel.screenRotation.collectAsStateWithLifecycle()
                    val frameTraceMode by sensorsViewModel.frameTraceMode.collectAsStateWithLifecycle()
                    val writerStats by sensorsViewModel.writerStats.collectAsStateWithLifecycle()
                    val source by sensorsViewModel.source.collectAsStateWithLifecycle()
                    val fps by sensorsViewModel.fps.collectAsStateWithLifecycle()
                    val isSensorActive by sensorsViewModel.isSensorActive.collectAsStateWithLifecycle()
                    SensorsDebugScreen(
                        frame = frame,
                        zero = zero,
                        screenRotation = screenRotation,
                        frameTraceMode = frameTraceMode,
                        fps = fps,
                        source = source,
                        writerStats = writerStats,
                        isSensorActive = isSensorActive,
                        onFrameTraceModeSelected = sensorsViewModel::selectFrameTraceMode,
                        onScreenRotationSelected = sensorsViewModel::selectScreenRotation,
                        onNavigateToCalibrate = { navController.navigate(ROUTE_SENSORS_CALIBRATE) },
                    )
                }
                composable(ROUTE_SENSORS_CALIBRATE) {
                    val frame by sensorsViewModel.frames.collectAsStateWithLifecycle(
                        initialValue = OrientationFrameDefaults.EMPTY,
                    )
                    SensorsCalibrateScreen(
                        azimuthDeg = frame.azimuthDeg,
                        accuracy = frame.accuracy,
                        onSetZero = sensorsViewModel::setZeroAzimuthOffset,
                        onResetZero = sensorsViewModel::resetZero,
                    )
                }
                composable(ROUTE_LOCATION) {
                    val phoneFix by phoneLocationRepository.lastKnownFix.collectAsStateWithLifecycle(
                        initialValue = null,
                    )
                    LocationSetupScreen(
                        locationPrefs = locationPrefs,
                        phoneFix = phoneFix,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_TIME_DEBUG) {
                    TimeDebugScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_CRASH_LOGS) {
                    CrashLogRoute(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        LaunchedEffect(onboardingAccepted, aimRequest?.seq) {
            if (!onboardingAccepted) return@LaunchedEffect
            aimRequest ?: return@LaunchedEffect
            navController.navigate(ROUTE_AIM) {
                launchSingleTop = true
            }
        }

        LaunchedEffect(onboardingAccepted, appOpenRequest?.seq) {
            if (!onboardingAccepted) return@LaunchedEffect
            val request = appOpenRequest ?: return@LaunchedEffect
            when (request.screen) {
                AppOpenScreen.AIM -> navController.navigate(ROUTE_AIM) { launchSingleTop = true }
                AppOpenScreen.IDENTIFY -> navController.navigate(ROUTE_IDENTIFY) { launchSingleTop = true }
                AppOpenScreen.TILE -> navController.navigate(ROUTE_HOME)
            }
        }
    }
}

@Composable
fun HomeScreen(
    onAimClick: () -> Unit,
    onIdentifyClick: () -> Unit,
    onAstroDebugClick: () -> Unit,
    onCatalogDebugClick: () -> Unit,
    onSensorsDebugClick: () -> Unit,
    onLocationClick: () -> Unit,
    onTimeDebugClick: () -> Unit,
    onCrashLogsClick: () -> Unit,
    modifier: Modifier = Modifier,
    // дефолт для старых вызовов
    onSettingsClick: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Button(
            onClick = onAimClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.find_label))
        }
        Button(
            onClick = onIdentifyClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.identify_label))
        }
        Button(
            onClick = onAstroDebugClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.astro_debug_label))
        }
        Button(
            onClick = onCatalogDebugClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.catalog_debug_label))
        }
        Button(
            onClick = onLocationClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.location_settings))
        }
        Button(
            onClick = onSensorsDebugClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.sensors_debug_label))
        }
        Button(
            onClick = onTimeDebugClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.time_debug_label))
        }
        Button(
            onClick = onCrashLogsClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.crash_logs_button))
        }
        Button(
            onClick = onSettingsClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.settings_label))
        }
    }
}

@Composable
fun AimScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Aim screen placeholder")
    }
}

@Composable
fun IdentifyScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "Identify screen placeholder")
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(
            onAimClick = {},
            onIdentifyClick = {},
            onAstroDebugClick = {},
            onCatalogDebugClick = {},
            onSensorsDebugClick = {},
            onLocationClick = {},
            onTimeDebugClick = {},
            onCrashLogsClick = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun AimScreenPreview() {
    MaterialTheme {
        AimScreen()
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun IdentifyScreenPreview() {
    MaterialTheme {
        IdentifyScreen()
    }
}
