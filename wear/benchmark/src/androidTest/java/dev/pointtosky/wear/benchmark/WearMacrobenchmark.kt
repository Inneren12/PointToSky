package dev.pointtosky.wear.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.PowerMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.perfetto.PowerRail
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupAim() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupThresholdMetric(maxInitialDisplayMs = 700.0)),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                ensureOnboardingCompleted()
                device.pressHome()
            }
        ) {
            launchMain(action = ACTION_OPEN_AIM)
            device.waitForIdle()
            device.pressHome()
        }
    }

    @Test
    fun coldStartupIdentify() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupThresholdMetric(maxInitialDisplayMs = 700.0)),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                ensureOnboardingCompleted()
                device.pressHome()
            }
        ) {
            launchMain(action = ACTION_OPEN_IDENTIFY)
            device.waitForIdle()
            device.pressHome()
        }
    }

    @Test
    fun identifyScrollJank() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingThresholdMetric(maxJankPercent = 3.0, minFps = 50.0)),
            iterations = 3,
            setupBlock = {
                ensureOnboardingCompleted()
                launchMain()
                waitForHome()
                openIdentify()
                device.waitForIdle()
            }
        ) {
            val device = device
            val (centerX, topY, bottomY) = device.scrollBounds()
            val stopAt = SystemClock.elapsedRealtime() + SCROLL_DURATION_MS
            while (SystemClock.elapsedRealtime() < stopAt) {
                device.swipe(centerX, bottomY, centerX, topY, SWIPE_STEPS)
                device.swipe(centerX, topY, centerX, bottomY, SWIPE_STEPS)
            }
            device.waitForIdle()
            device.pressHome()
        }
    }

    @Test
    fun sensorsSamplingRate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        launchHomeAndOpenSensors(instrumentation, device)
        val fpsText = device.wait(Until.findObject(By.textStartsWith("FPS")), 5_000)
            ?: throw AssertionError("Failed to locate FPS label on Sensors screen")
        val parsedFps = fpsText.parseFpsValue()
            ?: throw AssertionError("Unable to parse FPS value from '${fpsText.text}'")
        check(parsedFps <= SENSOR_FPS_LIMIT) {
            "Sensor sampling FPS %.1f exceeds limit %.1f".format(parsedFps, SENSOR_FPS_LIMIT)
        }
        device.pressHome()
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    fun aimEnergyProfile() {
        // JUnit4: assumeTrue(message, boolean) — если false, тест будет пропущен
        assumeTrue(
            "Power rails not available on device",
            PowerRail.hasMetrics()
        )

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(PowerMetric(PowerMetric.Power())),
            iterations = 1,
            setupBlock = {
                ensureOnboardingCompleted()
                launchMain(action = ACTION_OPEN_AIM)
                device.waitForIdle()
            }
        ) {
            SystemClock.sleep(ENERGY_SAMPLE_DURATION_MS)
            device.pressHome()
        }
    }

    private fun MacrobenchmarkScope.launchMain(action: String? = null) {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (action != null) {
                setAction(action)
            } else {
                setAction(Intent.ACTION_MAIN)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        }
        startActivityAndWait(intent)
    }

    private fun MacrobenchmarkScope.waitForHome() {
        val home = device.wait(
            Until.findObject(By.textStartsWith(HOME_LABEL_FIND_PREFIX)),
            5_000
        ) ?: device.wait(
            Until.findObject(By.textStartsWith(HOME_LABEL_FIND_PREFIX_EN)),
            5_000
        )
        check(home != null) { "Failed to load Home screen" }
    }

    private fun MacrobenchmarkScope.openIdentify() {
        val identify = device.wait(
            Until.findObject(By.text(IDENTIFY_LABEL)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.text(IDENTIFY_LABEL_EN)),
            3_000
        )
        checkNotNull(identify) { "Identify shortcut not found" }
        identify.click()
    }

    private fun MacrobenchmarkScope.ensureOnboardingCompleted() {
        if (ONBOARDING_COMPLETED.get()) return
        launchMain()
        val acknowledge = device.wait(
            Until.findObject(By.text(ONBOARDING_ACK_LABEL)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.text(ONBOARDING_ACK_LABEL_EN)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.textContains(ONBOARDING_ACK_FALLBACK)),
            3_000
        )
        if (acknowledge != null) {
            acknowledge.click()
            val continueButton = device.wait(
                Until.findObject(By.text(ONBOARDING_CONTINUE_LABEL)),
                1_000
            ) ?: device.wait(
                Until.findObject(By.text(ONBOARDING_CONTINUE_LABEL_EN)),
                1_000
            )
            continueButton?.click()
            device.wait(Until.gone(By.text(ONBOARDING_CONTINUE_LABEL)), 3_000)
            device.wait(Until.gone(By.text(ONBOARDING_CONTINUE_LABEL_EN)), 3_000)
        }
        device.pressHome()
        ONBOARDING_COMPLETED.set(true)
    }

    private fun UiDevice.scrollBounds(): Triple<Int, Int, Int> {
        val centerX = displayWidth / 2
        val topY = (displayHeight * 0.3f).toInt()
        val bottomY = (displayHeight * 0.7f).toInt()
        return Triple(centerX, topY, bottomY)
    }

    private fun launchHomeAndOpenSensors(
        instrumentation: Instrumentation,
        device: UiDevice,
    ) {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instrumentation.context.startActivity(intent)
        completeOnboardingIfVisible(device)
        val home = device.wait(
            Until.findObject(By.textStartsWith(HOME_LABEL_FIND_PREFIX)),
            5_000
        ) ?: device.wait(
            Until.findObject(By.textStartsWith(HOME_LABEL_FIND_PREFIX_EN)),
            5_000
        ) ?: throw AssertionError("Home screen not visible")
        val sensorsChip = device.wait(
            Until.findObject(By.text(SENSORS_LABEL)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.text(SENSORS_LABEL_EN)),
            3_000
        )
        checkNotNull(sensorsChip) { "Sensors entry not found" }
        sensorsChip.click()
        device.wait(Until.findObject(By.textStartsWith("FPS")), 3_000)
            ?: throw AssertionError("Sensors screen did not show FPS label")
    }

    private fun completeOnboardingIfVisible(device: UiDevice) {
        val acknowledge = device.wait(
            Until.findObject(By.text(ONBOARDING_ACK_LABEL)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.text(ONBOARDING_ACK_LABEL_EN)),
            3_000
        ) ?: device.wait(
            Until.findObject(By.textContains(ONBOARDING_ACK_FALLBACK)),
            3_000
        )
        if (acknowledge != null) {
            acknowledge.click()
            val continueButton = device.wait(
                Until.findObject(By.text(ONBOARDING_CONTINUE_LABEL)),
                3_000
            ) ?: device.wait(
                Until.findObject(By.text(ONBOARDING_CONTINUE_LABEL_EN)),
                3_000
            )
            continueButton?.click()
            device.wait(Until.gone(By.text(ONBOARDING_CONTINUE_LABEL)), 3_000)
            device.wait(Until.gone(By.text(ONBOARDING_CONTINUE_LABEL_EN)), 3_000)
            ONBOARDING_COMPLETED.set(true)
        } else {
            ONBOARDING_COMPLETED.set(true)
        }
    }

    private fun UiObject2.parseFpsValue(): Double? {
        val raw = text?.substringAfter("FPS:")?.trim() ?: return null
        val normalized = raw.replace(',', '.')
        val number = FPS_REGEX.find(normalized)?.value ?: return null
        return number.toDoubleOrNull()
    }

    companion object {
        private const val TARGET_PACKAGE = "dev.pointtosky.wear"
        private const val MAIN_ACTIVITY = "dev.pointtosky.wear.MainActivity"
        private const val ACTION_OPEN_AIM = "dev.pointtosky.action.OPEN_AIM"
        private const val ACTION_OPEN_IDENTIFY = "dev.pointtosky.ACTION_OPEN_IDENTIFY"
        private const val HOME_LABEL_FIND_PREFIX = "Найти"
        private const val HOME_LABEL_FIND_PREFIX_EN = "Aim"
        private const val IDENTIFY_LABEL = "Что там?"
        private const val IDENTIFY_LABEL_EN = "Identify"
        private const val SENSORS_LABEL = "Датчики"
        private const val SENSORS_LABEL_EN = "Sensors"
        private const val ONBOARDING_ACK_LABEL = "Я понимаю, что приложение не для навигации."
        private const val ONBOARDING_ACK_LABEL_EN = "I understand this app is not for navigation."
        private const val ONBOARDING_ACK_FALLBACK = "navigation"
        private const val ONBOARDING_CONTINUE_LABEL = "Продолжить"
        private const val ONBOARDING_CONTINUE_LABEL_EN = "Continue"
        private const val SENSOR_FPS_LIMIT = 15.0
        private const val SCROLL_DURATION_MS = 10_000L
        private const val ENERGY_SAMPLE_DURATION_MS = 10_000L
        private const val SWIPE_STEPS = 20
        private val FPS_REGEX = Regex("-?\\d+(?:[.,]\\d+)?")
        private val ONBOARDING_COMPLETED = AtomicBoolean(false)
    }
}

private class StartupThresholdMetric(
    private val maxInitialDisplayMs: Double
) : androidx.benchmark.macro.Metric() {
    private val delegate = StartupTimingMetric()

    override fun configure(packageName: String) = delegate.configure(packageName)

    override fun start() = delegate.start()

    override fun stop() = delegate.stop()

    override fun getResult(
        captureInfo: CaptureInfo,
        traceSession: androidx.benchmark.perfetto.PerfettoTraceProcessor.Session,
    ): List<Measurement> {
        val measurements = delegate.getResult(captureInfo, traceSession)
        val startup = measurements.firstOrNull { it.name == "timeToInitialDisplayMs" }
            ?: throw AssertionError("Startup metric missing timeToInitialDisplayMs")
        val value = startup.data.firstOrNull()
            ?: throw AssertionError("Startup metric missing data")
        check(value <= maxInitialDisplayMs) {
            "Cold startup %.1f ms exceeds threshold %.1f ms".format(value, maxInitialDisplayMs)
        }
        return measurements
    }
}

private class FrameTimingThresholdMetric(
    private val maxJankPercent: Double,
    private val minFps: Double,
) : androidx.benchmark.macro.Metric() {
    private val delegate = FrameTimingMetric()

    override fun configure(packageName: String) = delegate.configure(packageName)

    override fun start() = delegate.start()

    override fun stop() = delegate.stop()

    override fun getResult(
        captureInfo: CaptureInfo,
        traceSession: androidx.benchmark.perfetto.PerfettoTraceProcessor.Session,
    ): List<Measurement> {
        val results = delegate.getResult(captureInfo, traceSession)
        val frameDuration = results.firstOrNull { it.name == "frameDurationCpuMs" }?.data
            ?: emptyList()
        val frameOverrun = results.firstOrNull { it.name == "frameOverrunMs" }?.data
            ?: emptyList()
        val totalFrames = frameDuration.size
        check(totalFrames > 0) { "No frameDurationCpuMs samples captured" }
        val jankFrames = frameOverrun.count { it > 0.0 }
        val jankPercent = if (totalFrames == 0) 0.0 else jankFrames * 100.0 / totalFrames
        check(jankPercent <= maxJankPercent) {
            "Jank %.2f%% exceeds threshold %.2f%%".format(jankPercent, maxJankPercent)
        }
        val meanFrameMs = frameDuration.average()
        val fps = if (meanFrameMs == 0.0) Double.POSITIVE_INFINITY else 1000.0 / meanFrameMs
        check(fps >= minFps) {
            "Average FPS %.1f below threshold %.1f".format(fps, minFps)
        }
        return results
    }
}
