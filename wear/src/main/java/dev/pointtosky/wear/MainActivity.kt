package dev.pointtosky.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.ambient.AmbientModeSupport
import dev.pointtosky.wear.sensors.SensorsCalibrateScreen
import dev.pointtosky.wear.sensors.SensorsDebugScreen
import dev.pointtosky.wear.sensors.SensorsViewModel
import dev.pointtosky.wear.sensors.SensorsViewModelFactory

class MainActivity : ComponentActivity(), AmbientModeSupport.AmbientCallbackProvider {

    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private val isAmbientMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        isAmbientMode.value = ambientController.isAmbient
        setContent {
            val isAmbient by isAmbientMode
            PointToSkyWearApp(isAmbient = isAmbient)
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback =
        object : AmbientModeSupport.AmbientCallback() {
            override fun onEnterAmbient(ambientDetails: AmbientModeSupport.AmbientDetails) {
                isAmbientMode.value = true
            }

            override fun onUpdateAmbient() = Unit

            override fun onExitAmbient() {
                isAmbientMode.value = false
            }
        }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_AIM = "aim"
private const val ROUTE_IDENTIFY = "identify"
private const val ROUTE_SENSORS_DEBUG = "sensors_debug"
private const val ROUTE_SENSORS_CALIBRATE = "sensors_calibrate"

@Composable
fun PointToSkyWearApp(isAmbient: Boolean = false) {
    LaunchedEffect(isAmbient) {
        // Hook for ambient mode specific behavior.
    }
    val navController = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val viewModelFactory = remember { SensorsViewModelFactory(context.applicationContext) }
    val sensorsViewModel: SensorsViewModel = viewModel(factory = viewModelFactory)

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = ROUTE_HOME
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    onAimClick = { navController.navigate(ROUTE_AIM) },
                    onIdentifyClick = { navController.navigate(ROUTE_IDENTIFY) },
                    onSensorsDebugClick = { navController.navigate(ROUTE_SENSORS_DEBUG) },
                )
            }
            composable(ROUTE_AIM) { AimScreen() }
            composable(ROUTE_IDENTIFY) { IdentifyScreen() }
            composable(ROUTE_SENSORS_DEBUG) {
                val frame by sensorsViewModel.frame.collectAsState()
                val zero by sensorsViewModel.zero.collectAsState()
                val screenRotation by sensorsViewModel.screenRotation.collectAsState()
                SensorsDebugScreen(
                    frame = frame,
                    zero = zero,
                    screenRotation = screenRotation,
                    onScreenRotationSelected = sensorsViewModel::selectScreenRotation,
                    onNavigateToCalibrate = { navController.navigate(ROUTE_SENSORS_CALIBRATE) },
                )
            }
            composable(ROUTE_SENSORS_CALIBRATE) {
                val frame by sensorsViewModel.frame.collectAsState()
                SensorsCalibrateScreen(
                    azimuthDeg = frame?.azimuthDeg,
                    accuracy = frame?.accuracy,
                    onSetZero = { deg -> sensorsViewModel.setZeroAzimuthOffset(deg) },
                    onResetZero = sensorsViewModel::resetZero,
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onAimClick: () -> Unit,
    onIdentifyClick: () -> Unit,
    onSensorsDebugClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Button(
            onClick = onAimClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors()
        ) {
            Text(text = stringResource(id = R.string.find_label))
        }
        Button(
            onClick = onIdentifyClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors()
        ) {
            Text(text = stringResource(id = R.string.identify_label))
        }
        Button(
            onClick = onSensorsDebugClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.sensors_debug_label))
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
        verticalArrangement = Arrangement.Center
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
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Identify screen placeholder")
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme {
        HomeScreen(onAimClick = {}, onIdentifyClick = {}, onSensorsDebugClick = {})
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun AimScreenPreview() {
    MaterialTheme {
        AimScreen()
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun IdentifyScreenPreview() {
    MaterialTheme {
        IdentifyScreen()
    }
}
