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
        initializeLogger()
        installCrashHandler()
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
    }

    private fun initializeLogger() {
        val deviceInfo = DeviceInfo.from(
            context = this,
            isDebug = BuildConfig.DEBUG,
            flavor = BuildConfig.FLAVOR.ifBlank { "default" },
        )
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = deviceInfo,
        )
        LogBridge.i("APP", "PointToSky Wear app initialized")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBridge.e("CRASH", "Uncaught exception in thread ${thread.name}", throwable)
            CrashSafeFlush.flushAndSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun enableStrictMode() {
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder().detectAll()
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder().detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val directExecutor = Executor { it.run() }
            threadPolicyBuilder.penaltyListener(directExecutor) { violation ->
                LogBridge.w(
                    tag = "STRICT",
                    message = "Thread policy violation: ${violation.javaClass.simpleName}",
                    throwable = violation,
                )
            }
            vmPolicyBuilder.penaltyListener(directExecutor) { violation ->
                LogBridge.w(
                    tag = "STRICT",
                    message = "VM policy violation: ${violation.javaClass.simpleName}",
                    throwable = violation,
                )
            }
        } else {
            threadPolicyBuilder.penaltyLog()
            vmPolicyBuilder.penaltyLog()
        }
        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
        // TODO: ослабить правила при проблемах сыпучести.
    }
}
