package dev.pointtosky.mobile

import android.app.Application
import android.os.Build
import android.os.StrictMode
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class PointToSkyApp : Application() {

    private val strictModeExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "StrictModeLogger").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val deviceInfo = DeviceInfo.from(
            context = this,
            isDebug = BuildConfig.DEBUG,
            diagnosticsEnabled = BuildConfig.DEBUG,
            flavor = BuildConfig.FLAVOR.ifBlank { "default" },
        )
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = deviceInfo,
        )
        LogBus.event(
            name = "app_start",
            payload = mapOf(
                "model" to deviceInfo.model,
                "sdk" to deviceInfo.sdkInt,
                "appVersionName" to deviceInfo.appVersionName,
                "appVersionCode" to deviceInfo.appVersionCode,
                "flavor" to deviceInfo.flavor,
                "supportedAbis" to deviceInfo.supportedAbis,
                "sensors" to deviceInfo.sensors,
            ),
        )
        installCrashHandler()
        configureStrictMode()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBus.e(tag = "CRASH", msg = "uncaught", err = throwable)
            CrashSafeFlush.flushAndSync()
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun configureStrictMode() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .penaltyListener(strictModeExecutor, StrictMode.OnThreadViolationListener { violation ->
                        LogBridge.w("STRICT", violation.javaClass.simpleName, violation)
                    })
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .penaltyListener(strictModeExecutor, StrictMode.OnVmViolationListener { violation ->
                        LogBridge.w("STRICT", violation.javaClass.simpleName, violation)
                    })
                    .build()
            )
        } else {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .build()
            )
        }
        LogBridge.i("STRICT", "StrictMode enabled")
    }
}
