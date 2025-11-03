package dev.pointtosky.mobile

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import androidx.core.content.ContextCompat
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.EmptyCoroutineContext

class PointToSkyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val deviceInfo = DeviceInfo.from(
            context = this,
            isDebug = BuildConfig.DEBUG,
            flavor = BuildConfig.FLAVOR
        )
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = deviceInfo
        )
        LogBus.event(
            name = "app_start",
            payload = mapOf(
                "flavor" to BuildConfig.FLAVOR,
                "version" to BuildConfig.VERSION_NAME,
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "abis" to Build.SUPPORTED_ABIS?.toList().orEmpty()
            )
        )
        installDefaultCoroutineHandler()
        installDefaultUncaughtExceptionHandler()
        setupStrictMode(isDebug = BuildConfig.DEBUG)
    }

    private fun installDefaultUncaughtExceptionHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            defaultCoroutineExceptionHandler.handleException(EmptyCoroutineContext, throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun installDefaultCoroutineHandler() {
        defaultCoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            LogBridge.e(
                tag = TAG_CRASH,
                message = "Unhandled coroutine exception on thread ${Thread.currentThread().name}",
                throwable = throwable
            )
            CrashSafeFlush.flushAndSync()
        }
    }

    private fun setupStrictMode(isDebug: Boolean) {
        if (!isDebug) return
        val executor = ContextCompat.getMainExecutor(this)
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w(
                    tag = TAG_STRICT,
                    message = "Thread policy violation: ${violation.message}",
                    throwable = violation
                )
            }
        } else {
            threadPolicyBuilder.penaltyLog()
            LogBridge.w(
                tag = TAG_STRICT,
                message = "StrictMode penalty listener unavailable; falling back to Logcat"
            )
        }
        // TODO: ослабить правила при проблемах сыпучести.
        StrictMode.setThreadPolicy(threadPolicyBuilder.build())

        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            vmPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w(
                    tag = TAG_STRICT,
                    message = "VM policy violation: ${violation.message}",
                    throwable = violation
                )
            }
        } else {
            vmPolicyBuilder.penaltyLog()
            LogBridge.w(
                tag = TAG_STRICT,
                message = "StrictMode VM penalty listener unavailable; falling back to Logcat"
            )
        }
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
    }

    companion object {
        private const val TAG_CRASH = "CRASH"
        private const val TAG_STRICT = "STRICT"

        @JvmStatic
        lateinit var defaultCoroutineExceptionHandler: CoroutineExceptionHandler
            private set
    }
}
