package dev.pointtosky.mobile.logging

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import dev.pointtosky.core.logging.LoggerInitializer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsLoggingInstrumentedTest {

    @After
    fun tearDown() {
        LogBus.reset()
    }

    @Test
    fun diagnosticsLogsArePersistedWithRotation() {
        ActivityScenario.launch(LoggingTestActivity::class.java).use { scenario ->
            lateinit var logger: Logger
            lateinit var logsDir: File

            scenario.onActivity { activity ->
                val appContext = activity.applicationContext as Application
                logsDir = File(appContext.filesDir, "logs").apply { deleteRecursively(); mkdirs() }
                val deviceInfo = DeviceInfo.from(
                    context = appContext,
                    isDebug = false,
                    diagnosticsEnabled = true
                )
                logger = LoggerInitializer.init(
                    context = appContext,
                    isDebug = false,
                    diagnosticsLogsEnabled = true,
                    deviceInfo = deviceInfo
                )
            }

            val payload = mapOf("blob" to "x".repeat(16_384))
            val totalEvents = 1_000
            val workers = 8
            val basePerWorker = totalEvents / workers
            val remainder = totalEvents % workers

            runBlocking {
                coroutineScope {
                    repeat(workers) { workerIndex ->
                        val eventsForWorker = basePerWorker + if (workerIndex < remainder) 1 else 0
                        launch(Dispatchers.Default) {
                            repeat(eventsForWorker) { eventIndex ->
                                logger.i(
                                    tag = "DiagnosticsTest",
                                    msg = "event-${workerIndex}-${eventIndex}",
                                    payload = payload
                                )
                            }
                        }
                    }
                }
            }

            LogBus.flushAndSyncBlocking()

            val files = logsDir.listFiles()?.toList().orEmpty()
            assertTrue(files.isNotEmpty(), "Expected log files to be created")

            val gzipFiles = files.filter { it.name.endsWith(".jsonl.gz") }
            assertTrue(gzipFiles.isNotEmpty(), "Expected rotated gzip log files to exist")

            val currentFile = files.firstOrNull { it.name.endsWith(".jsonl") && !it.name.endsWith(".jsonl.gz") }
            assertNotNull(currentFile, "Expected an active JSONL log file to remain")

            val gzFile = gzipFiles.first()
            GZIPInputStream(FileInputStream(gzFile)).bufferedReader().use { reader ->
                val firstLine = reader.readLine()
                assertNotNull(firstLine, "Rotated gzip file should contain log entries")
                assertTrue(firstLine.trim().startsWith("{"), "Log entry must be JSON")
            }
        }
    }

    class LoggingTestActivity : ComponentActivity()
}
