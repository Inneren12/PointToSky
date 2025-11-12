package dev.pointtosky.wear.complication

import android.content.Context

object ComplicationDebug {
    fun forceRefresh(context: Context) {
        val appContext = context.applicationContext
        AimStatusComplicationUpdater(appContext).requestUpdate(force = true)
        TonightTargetComplicationUpdater(appContext).requestImmediateUpdate()
    }
}
