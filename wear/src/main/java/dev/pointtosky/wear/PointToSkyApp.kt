package dev.pointtosky.wear

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.StrictMode
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer
import java.util.concurrent.Executor

class PointToSkyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val deviceInfo = createDeviceInfo()
        LoggerInitializer.init(
            context = this,
            isDebug = BuildConfig.DEBUG,
            deviceInfo = deviceInfo
        )
        installCrashHandler()
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
        LogBridge.i(APP_TAG, "PointToSkyApp initialized")
    }

    private fun createDeviceInfo(): DeviceInfo {
        val sensorAvailability = collectSensorAvailability()
        val extras = buildMap<String, Any?> {
            put("flavor", BuildConfig.FLAVOR.ifEmpty { DEFAULT_FLAVOR })
            put("supportedAbis", Build.SUPPORTED_ABIS.toList())
            put("sensors", sensorAvailability)
        }
        return DeviceInfo.from(
            context = this,
            isDebug = BuildConfig.DEBUG,
            extras = extras
        )
    }

    private fun collectSensorAvailability(): Map<String, Boolean> {
        val sensorManager = getSystemService(SensorManager::class.java)
        val requiredSensors = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD
        )
        return requiredSensors.mapValues { (_, type) ->
            sensorManager?.getDefaultSensor(type) != null
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBus.e(
                tag = "CRASH",
                msg = "Uncaught exception in ${thread.name}",
                err = throwable,
                payload = mapOf("thread" to thread.name)
            )
            runCatching { CrashSafeFlush.flushAndSync() }
            defaultHandler?.uncaughtException(thread, throwable)
                ?: thread.threadGroup?.uncaughtException(thread, throwable)
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(createThreadPolicy())
        StrictMode.setVmPolicy(createVmPolicy())
        LogBridge.i(APP_TAG, "StrictMode enabled")
    }

    private fun createThreadPolicy(): StrictMode.ThreadPolicy {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
        return builder.withPenaltyHandler { violation ->
            LogBridge.w("STRICT", "Thread policy violation", violation)
        }.build()
    }

    private fun createVmPolicy(): StrictMode.VmPolicy {
        val builder = StrictMode.VmPolicy.Builder()
            .detectAll()
        return builder.withPenaltyHandler { violation ->
            LogBridge.w("STRICT", "VM policy violation", violation)
        }.build()
    }

    private fun StrictMode.ThreadPolicy.Builder.withPenaltyHandler(
        onViolation: (Throwable) -> Unit
    ): StrictMode.ThreadPolicy.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            penaltyListener(MAIN_EXECUTOR) { violation ->
                onViolation(violation)
            }
        } else {
            LogBridge.w(APP_TAG, "StrictMode listener not supported on API < 28; falling back to Logcat only")
            penaltyLog()
        }
    }

    private fun StrictMode.VmPolicy.Builder.withPenaltyHandler(
        onViolation: (Throwable) -> Unit
    ): StrictMode.VmPolicy.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            penaltyListener(MAIN_EXECUTOR) { violation ->
                onViolation(violation)
            }
        } else {
            LogBridge.w(APP_TAG, "StrictMode listener not supported on API < 28; falling back to Logcat only")
            penaltyLog()
        }
    }

    companion object {
        private const val APP_TAG = "PointToSkyApp"
        private const val DEFAULT_FLAVOR = "default"
        private val MAIN_EXECUTOR = Executor { command -> command.run() }
    }
}
