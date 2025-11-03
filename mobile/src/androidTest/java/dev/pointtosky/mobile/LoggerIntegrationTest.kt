package dev.pointtosky.mobile

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pointtosky.core.logging.DeviceInfo
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LoggerInitializer
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoggerIntegrationTest {

    private lateinit var appContext: Context
    private lateinit var logsDirectory: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        logsDirectory = File(appContext.filesDir, "logs")
        if (logsDirectory.exists()) {
            logsDirectory.deleteRecursively()
        }
        logsDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        LogBus.reset()
        if (logsDirectory.exists()) {
            logsDirectory.deleteRecursively()
        }
    }

    @Test
    fun fileLogsAreRotatedAndCompressed() = runBlocking {
        val deviceInfo = DeviceInfo.from(
            context = appContext,
            isDebug = false,
            diagnosticsEnabled = true
        )
        LoggerInitializer.init(
            context = appContext,
            isDebug = false,
            deviceInfo = deviceInfo,
            diagnosticsLogsEnabled = BuildConfig.DIAGNOSTICS_LOGS_ENABLED
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            val payloadBlob = "x".repeat(32 * 1024)
            coroutineScope {
                repeat(10) { worker ->
                    launch(Dispatchers.Default) {
                        repeat(100) { index ->
                            LogBus.i(
                                tag = "Integration",
                                msg = "event-$worker-$index",
                                payload = mapOf(
                                    "worker" to worker,
                                    "index" to index,
                                    "blob" to payloadBlob
                                )
                            )
                        }
                    }
                }
            }
            LogBus.flushAndSync()
        }

        val logFiles = logsDirectory.listFiles()?.toList().orEmpty()
        assertTrue(logFiles.isNotEmpty(), "Expected structured logs to be written to disk")

        val jsonlFiles = logFiles.filter { it.extension == "jsonl" }
        val gzFiles = logFiles.filter { it.extension == "gz" }

        assertTrue(jsonlFiles.isNotEmpty(), "Active log file (.jsonl) should exist after flushing")
        assertTrue(gzFiles.isNotEmpty(), "At least one archived log (.jsonl.gz) should exist after rotation")

        val gzFile = gzFiles.first()
        FileInputStream(gzFile).use { fileInput ->
            GZIPInputStream(fileInput).bufferedReader().use { reader ->
                val firstLine = reader.readLine()
                assertNotNull(firstLine, "Archived gzip file must contain log entries")
                assertTrue(
                    firstLine.contains("\"Integration\""),
                    "Compressed log should include the Integration tag"
                )
                assertTrue(
                    firstLine.contains("blob"),
                    "Compressed log should include payload data"
                )
            }
        }
    }
}
