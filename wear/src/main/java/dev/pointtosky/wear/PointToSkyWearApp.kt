package dev.pointtosky.wear

import android.app.Application
import dev.pointtosky.core.logging.CrashLogManager

class PointToSkyWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogManager.initialize(this)
        CrashLogManager.installDefaultHandler()
    }
}
