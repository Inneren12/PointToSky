package dev.pointtosky.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pointtosky.mobile.logs.LogsRoute
import dev.pointtosky.mobile.logs.LogsViewModel

class MainActivity : ComponentActivity() {
    private val logsViewModel: LogsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val shareTarget by logsViewModel.shareTargets.collectAsStateWithLifecycle()

            LaunchedEffect(shareTarget) {
                val target = shareTarget ?: return@LaunchedEffect
                val uri = Uri.parse(target.uriString)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = target.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, target.title))
                logsViewModel.onShareConsumed()
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LogsRoute(viewModel = logsViewModel)
                }
            }
        }
    }
}
