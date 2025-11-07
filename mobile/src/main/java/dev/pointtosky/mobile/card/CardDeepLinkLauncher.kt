package dev.pointtosky.mobile.card

import android.content.Context
import android.content.Intent
import android.net.Uri

private const val CARD_SCHEME = "app"
private const val CARD_HOST = "pointtosky"
private const val CARD_PATH = "/card"

internal object CardDeepLinkLauncher {
    @Volatile
    private var overrideLauncher: ((Context, String) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    fun overrideForTests(block: ((Context, String) -> Unit)?) {
        overrideLauncher = block
    }

    fun buildUri(id: String): Uri {
        val encodedId = Uri.encode(id)
        return Uri.parse("$CARD_SCHEME://$CARD_HOST$CARD_PATH?id=$encodedId")
    }

    fun launch(context: Context, id: String) {
        val override = overrideLauncher
        if (override != null) {
            override(context, id)
            return
        }
        val uri = buildUri(id)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

internal fun parseCardIdFromIntent(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val data = intent.data ?: return null
    if (!data.scheme.equals(CARD_SCHEME, ignoreCase = true)) return null
    if (!data.host.equals(CARD_HOST, ignoreCase = true)) return null
    if (data.path != CARD_PATH) return null
    return data.getQueryParameter("id") ?: ""
}
