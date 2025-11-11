package dev.pointtosky.core.logging

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileLogSinkTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory(prefix = "logs").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `rotates by size and gzips previous file`() = runBlocking {
        val config = LoggerConfig(
            logsDirectory = tempDir,
            diagnosticsEnabled = true,
            maxFileSizeBytes = 512,
            maxFiles = 5,
            flushIntervalMs = 0,
        )
        val manager = LogFileManager(config)
        val sink = FileLogSink(config, manager)

        repeat(10) { index ->
            sink.write(sampleEvent(index))
        }
        sink.flush()
        sink.sync()

        val files = tempDir.listFiles()?.toList().orEmpty()
        assertTrue(files.any { it.extension == "jsonl" })
        assertTrue(files.any { it.extension == "gz" })
    }

    @Test
    fun `creates new file after restart and keeps archived logs`() = runBlocking {
        val config = LoggerConfig(
            logsDirectory = tempDir,
            diagnosticsEnabled = true,
            maxFileSizeBytes = 1024,
            maxFiles = 5,
            flushIntervalMs = 0,
        )
        val manager = LogFileManager(config)
        val sink = FileLogSink(config, manager)
        sink.write(sampleEvent(0))
        sink.flush()
        sink.close()

        val secondManager = LogFileManager(config)
        val secondSink = FileLogSink(config, secondManager)
        secondSink.write(sampleEvent(1))
        secondSink.flush()
        secondSink.sync()

        val jsonlFiles = tempDir.listFiles { file -> file.extension == "jsonl" }?.toList().orEmpty()
        val gzFiles = tempDir.listFiles { file -> file.extension == "gz" }?.toList().orEmpty()
        assertEquals(1, jsonlFiles.size)
        assertTrue(gzFiles.isNotEmpty())
    }

    private fun sampleEvent(index: Int): LogEvent = LogEvent(
        level = LogLevel.INFO,
        tag = "FileTest",
        message = "message-$index-${"x".repeat(256)}",
        payload = mapOf("index" to index, "blob" to "y".repeat(128)),
        thread = ThreadSnapshot(name = "test", id = 1L, isMainThread = false),
        process = ProcessSnapshot(pid = 1, processName = "proc"),
        device = DeviceInfo(
            manufacturer = "Google",
            model = "Pixel",
            osVersion = "14",
            sdkInt = 34,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            packageName = "dev.pointtosky.test",
            isDebug = true,
            diagnosticsEnabled = true,
        ),
    )
}
