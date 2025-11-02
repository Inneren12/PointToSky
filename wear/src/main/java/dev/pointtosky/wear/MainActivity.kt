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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PointToSkyWearApp()
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_AIM = "aim"
private const val ROUTE_IDENTIFY = "identify"

@Composable
fun PointToSkyWearApp() {
    val navController = rememberSwipeDismissableNavController()

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = ROUTE_HOME
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    onAimClick = { navController.navigate(ROUTE_AIM) },
                    onIdentifyClick = { navController.navigate(ROUTE_IDENTIFY) }
                )
            }
            composable(ROUTE_AIM) { AimScreen() }
            composable(ROUTE_IDENTIFY) { IdentifyScreen() }
        }
    }
}

@Composable
fun HomeScreen(
    onAimClick: () -> Unit,
    onIdentifyClick: () -> Unit,
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
        HomeScreen(onAimClick = {}, onIdentifyClick = {})
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
