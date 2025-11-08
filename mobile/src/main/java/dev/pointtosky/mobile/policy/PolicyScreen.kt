package dev.pointtosky.mobile.policy

import android.os.Build
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pointtosky.mobile.R
import java.util.Locale

enum class PolicyDocument(@StringRes val titleRes: Int, private val baseName: String) {
    PrivacyPolicy(R.string.policy_document_privacy_title, "policy/privacy_policy"),
    Disclaimer(R.string.policy_document_disclaimer_title, "policy/disclaimer");

    fun assetPath(locale: Locale): String {
        return if (locale.language.equals("ru", ignoreCase = true)) {
            "${baseName}_ru.html"
        } else {
            "${baseName}_en.html"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyScreen(
    onOpenDocument: (PolicyDocument) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.policy_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(id = R.string.policy_screen_description),
                style = MaterialTheme.typography.bodyMedium
            )

            Surface(
                onClick = { onOpenDocument(PolicyDocument.PrivacyPolicy) },
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.policy_screen_privacy)) },
                    supportingContent = { Text(text = stringResource(id = R.string.policy_screen_privacy_summary)) },
                )
            }

            Surface(
                onClick = { onOpenDocument(PolicyDocument.Disclaimer) },
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.policy_screen_disclaimer)) },
                    supportingContent = { Text(text = stringResource(id = R.string.policy_screen_disclaimer_summary)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyDocumentScreen(
    document: PolicyDocument,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = remember {
        val configuration = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
    }
    val assetPath = remember(document, locale) { document.assetPath(locale) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = document.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = false
                            builtInZoomControls = true
                            displayZoomControls = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                        }
                    }
                },
                update = { webView ->
                    val url = "file:///android_asset/$assetPath"
                    if (webView.url != url) {
                        webView.loadUrl(url)
                    }
                }
            )
        }
    }
}
