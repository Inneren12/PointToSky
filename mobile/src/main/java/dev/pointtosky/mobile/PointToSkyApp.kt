package dev.pointtosky.mobile

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.StrictMode
import androidx.core.content.ContextCompat
import dev.pointtosky.core.logging.CrashSafeFlush
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer

private const val TAG = "PointToSkyApp"

class PointToSkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isDebug = BuildConfig.DEBUG
        val deviceInfo = createDeviceInfo(isDebug)
        LoggerInitializer.init(
            context = this,
            isDebug = isDebug,
            deviceInfo = deviceInfo
        )
        LogBridge.i(TAG, "Logger initialized", mapOf("flavor" to deviceInfo.extras["flavor"]))
        LogBus.event(
            name = "app_start",
            payload = mapOf(
                "flavor" to deviceInfo.extras["flavor"],
                "supportedAbis" to deviceInfo.extras["supportedAbis"],
                "sensors" to deviceInfo.extras["sensors"],
                "hasAnySensor" to deviceInfo.extras["hasAnySensor"]
            )
        )
        installCrashHandler()
        if (isDebug) {
            enableStrictMode()
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBus.e("CRASH", "uncaught", throwable)
            CrashSafeFlush.flushAndSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }
        LogBridge.i(TAG, "Crash handler registered")
    }

    private fun enableStrictMode() {
        val executor = ContextCompat.getMainExecutor(this)
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            threadPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w("STRICT", "Thread policy violation", violation)
            }
            vmPolicyBuilder.penaltyListener(executor) { violation ->
                LogBridge.w("STRICT", "VM policy violation", violation)
            }
        } else {
            LogBridge.w("STRICT", "StrictMode fallback to Logcat only on API < 28")
            threadPolicyBuilder.penaltyLog()
            vmPolicyBuilder.penaltyLog()
        }
        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
        LogBridge.i(TAG, "StrictMode enabled")
    }

    private fun createDeviceInfo(isDebug: Boolean): DeviceInfo {
        val flavor = BuildConfig.FLAVOR.ifBlank { "default" }
        val supportedAbis = Build.SUPPORTED_ABIS.toList().takeIf { it.isNotEmpty() }
            ?: listOfNotNull(
                Build.CPU_ABI.takeIf { it.isNotBlank() },
                Build.CPU_ABI2.takeIf { it.isNotBlank() }
            )
        val sensors = collectSensorInfo()
        val extras = buildMap<String, Any?> {
            put("flavor", flavor)
            put("supportedAbis", supportedAbis)
            if (sensors.isNotEmpty()) {
                put("sensors", sensors)
                put("hasAnySensor", sensors.values.any { it })
            } else {
                put("hasAnySensor", false)
            }
        }
        return DeviceInfo.from(
            context = this,
            isDebug = isDebug,
            extras = extras
        )
    }

    private fun collectSensorInfo(): Map<String, Boolean> {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyMap()
        val candidates = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
            Sensor.TYPE_ROTATION_VECTOR to "rotationVector"
        )
        return candidates.associate { (type, name) ->
            name to (sensorManager.getDefaultSensor(type) != null)
        }
    }
}
