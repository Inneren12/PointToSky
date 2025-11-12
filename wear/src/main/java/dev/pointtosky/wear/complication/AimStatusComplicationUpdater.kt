package dev.pointtosky.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlin.math.max

class AimStatusComplicationUpdater(
    context: Context,
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val componentName = ComponentName(context, AimStatusDataSourceService::class.java)
    private val requester = ComplicationDataSourceUpdateRequester.create(context, componentName)
    private var lastUpdateMs: Long = 0L

    fun requestUpdate(force: Boolean = false) {
        val now = clock()
        if (!force && now - lastUpdateMs < minIntervalMs) return
        lastUpdateMs = max(lastUpdateMs, now)
        requester.requestUpdateAll()
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS = 60_000L
    }
}

