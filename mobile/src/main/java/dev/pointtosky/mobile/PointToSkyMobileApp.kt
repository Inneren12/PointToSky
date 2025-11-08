package dev.pointtosky.mobile

import android.app.Application
import dev.pointtosky.core.logging.CrashLogManager

class PointToSkyMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogManager.initialize(this)
        CrashLogManager.installDefaultHandler()
    }
}
