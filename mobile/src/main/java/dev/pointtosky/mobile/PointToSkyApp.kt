package dev.pointtosky.mobile

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.core.os.HandlerCompat
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer
import kotlin.collections.buildMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import java.util.concurrent.Executor

class PointToSkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val deviceInfo = buildDeviceInfo()
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = deviceInfo
        )
        LogBridge.i(TAG_APP, "Logger initialized")
        logStartup(deviceInfo)
        installCrashHandlers()
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
    }

    private fun buildDeviceInfo(): DeviceInfo {
        val extras = buildMap<String, Any?> {
            put("flavor", BuildConfig.FLAVOR.ifBlank { "default" })
            put("supportedAbis", Build.SUPPORTED_ABIS.toList())
            putAll(collectSensorInfo())
        }
        return DeviceInfo.from(
            context = this,
            isDebug = BuildConfig.DEBUG,
            extras = extras
        )
    }

    private fun collectSensorInfo(): Map<String, Any?> {
        val sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            ?: return mapOf("hasSensors" to false)
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        if (sensors.isEmpty()) {
            return mapOf("hasSensors" to false)
        }
        val sensorTypes = sensors.mapNotNull { sensor ->
            sensor.stringType?.takeIf { it.isNotBlank() } ?: sensor.name.takeIf { it.isNotBlank() }
        }.distinct().sorted()
        val hasAccelerometer = sensors.any { it.type == Sensor.TYPE_ACCELEROMETER }
        val hasGyroscope = sensors.any { it.type == Sensor.TYPE_GYROSCOPE }
        val hasMagnetometer = sensors.any { it.type == Sensor.TYPE_MAGNETIC_FIELD }
        return buildMap {
            put("hasSensors", true)
            put("availableSensors", sensorTypes)
            put("hasAccelerometer", hasAccelerometer)
            put("hasGyroscope", hasGyroscope)
            put("hasMagnetometer", hasMagnetometer)
        }
    }

    private fun logStartup(deviceInfo: DeviceInfo) {
        val payload = buildMap<String, Any?> {
            put("model", deviceInfo.model)
            put("sdk", deviceInfo.sdkInt)
            put("appVersion", deviceInfo.appVersionName)
            putAll(deviceInfo.extras)
        }
        LogBus.event("app_start", payload)
        LogBridge.i(TAG_APP, "App start logged")
    }

    private fun installCrashHandlers() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBridge.e(TAG_CRASH, "Uncaught exception in ${thread.name}", throwable)
            CrashSafeFlush.flushAndSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
        defaultCoroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
            val coroutineName = context[CoroutineName]?.name ?: "unnamed"
            LogBridge.e(TAG_CRASH, "Coroutine exception in $coroutineName", throwable)
            CrashSafeFlush.flushAndSync()
        }
    }

    private fun enableStrictMode() {
        LogBridge.i(TAG_STRICT, "Enabling StrictMode")
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = HandlerCompat.createAsync(Looper.getMainLooper()).asExecutor()
            threadPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w(TAG_STRICT, "Thread violation: ${violation.javaClass.simpleName}", violation)
            }
            vmPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w(TAG_STRICT, "VM violation: ${violation.javaClass.simpleName}", violation)
            }
        } else {
            threadPolicyBuilder.penaltyLog()
            vmPolicyBuilder.penaltyLog()
            LogBridge.w(TAG_STRICT, "StrictMode listener unsupported on API ${Build.VERSION.SDK_INT}")
        }
        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
    }

    private fun Handler.asExecutor(): Executor = Executor { command ->
        if (!post(command)) {
            command.run()
        }
    }

    companion object {
        private const val TAG_APP = "PointToSkyApp"
        private const val TAG_CRASH = "CRASH"
        private const val TAG_STRICT = "STRICT"

        lateinit var defaultCoroutineExceptionHandler: CoroutineExceptionHandler
            private set
    }
}
