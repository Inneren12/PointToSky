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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.mobile.BuildConfig

class MainActivity : ComponentActivity() {
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
                }
            )
        }
    }
}

@Composable
fun PointToSkyMobileApp(onOpenCard: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobileHome(onOpenCard = onOpenCard)
        }
    }
}

@Composable
fun MobileHome(onOpenCard: () -> Unit, modifier: Modifier = Modifier) {
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
        if (BuildConfig.DEBUG) {
            Button(
                onClick = {
                    LogBridge.e("CRASH_TEST", "Mobile crash test triggered by user")
                    throw RuntimeException("Crash test from mobile debug UI")
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(text = stringResource(id = R.string.crash_test))
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun MobileHomePreview() {
    PointToSkyMobileApp(onOpenCard = {})
}
