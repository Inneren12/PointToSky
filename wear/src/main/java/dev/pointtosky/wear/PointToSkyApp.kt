package dev.pointtosky.wear

import android.app.Application
import android.os.Build
import android.os.StrictMode
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LoggerInitializer
import java.util.concurrent.Executor

class PointToSkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = DeviceInfo.from(
                context = this,
                isDebug = BuildConfig.DEBUG,
                diagnosticsEnabled = BuildConfig.DEBUG,
                flavor = BuildConfig.FLAVOR.ifBlank { "default" }
            )
        )
        installCrashHandler()
        configureStrictMode()
        LogBridge.i("App", "PointToSky wear app initialised")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBridge.e("CRASH", "Uncaught exception in thread ${thread.name}", throwable)
            CrashSafeFlush.flushAndSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun configureStrictMode() {
        if (!BuildConfig.DEBUG) return
        val directExecutor = Executor { it.run() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .penaltyListener(directExecutor) { violation ->
                        val detail = violation.message ?: violation.javaClass.simpleName
                        LogBridge.w(
                            tag = "STRICT",
                            message = "Thread policy violation: $detail",
                            throwable = violation
                        )
                    }
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    // TODO: ослабить правила при проблемах сыпучести.
                    .penaltyListener(directExecutor) { violation ->
                        val detail = violation.message ?: violation.javaClass.simpleName
                        LogBridge.w(
                            tag = "STRICT",
                            message = "VM policy violation: $detail",
                            throwable = violation
                        )
                    }
                    .build()
            )
        } else {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            LogBridge.w(
                tag = "STRICT",
                message = "StrictMode penalty listeners unavailable below API 28; falling back to Logcat"
            )
        }
    }
}
