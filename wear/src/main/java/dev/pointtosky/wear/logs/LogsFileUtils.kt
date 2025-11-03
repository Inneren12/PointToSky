package dev.pointtosky.wear.logs

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream

internal object LogsFileUtils {
    private val utf8: Charset = Charsets.UTF_8

    fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    fun enforceRetention(directory: File, maxFiles: Int, maxBytes: Long) {
        if (!directory.exists()) return
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return

        var sorted = files.sortedBy { it.lastModified() }
        var totalBytes = sorted.sumOf { it.length() }

        while (sorted.size > maxFiles) {
            val file = sorted.first()
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
            }
            sorted = sorted.drop(1)
        }

        var index = 0
        while (totalBytes > maxBytes && index < sorted.size) {
            val file = sorted[index]
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
            }
            index++
        }
    }

    fun readTail(file: File, maxLines: Int): List<String> {
        return if (file.extension.equals("gz", ignoreCase = true)) {
            readTailFromGzip(file, maxLines)
        } else {
            readTailFromPlain(file, maxLines)
        }
    }

    private fun readTailFromPlain(file: File, maxLines: Int): List<String> {
        if (!file.exists()) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val result = ArrayDeque<String>(maxLines)
            val buffer = StringBuilder()
            var pointer = raf.length() - 1
            var newlineCount = 0

            while (pointer >= 0 && newlineCount < maxLines) {
                raf.seek(pointer)
                val byte = raf.readByte()
                if (byte.toInt() == '\n'.code) {
                    val line = buffer.reverse().toString()
                    if (line.isNotEmpty()) {
                        result.addFirst(line)
                    }
                    buffer.clear()
                    newlineCount++
                } else {
                    buffer.append(byte.toInt().toChar())
                }
                pointer--
            }

            if (buffer.isNotEmpty()) {
                result.addFirst(buffer.reverse().toString())
            }

            return result.takeLast(maxLines)
        }
    }

    private fun readTailFromGzip(file: File, maxLines: Int): List<String> {
        if (!file.exists()) return emptyList()
        val deque = ArrayDeque<String>(maxLines)
        BufferedInputStream(FileInputStream(file)).use { fis ->
            GZIPInputStream(fis).use { gis ->
                BufferedReader(InputStreamReader(gis, utf8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (deque.size == maxLines) {
                            deque.removeFirst()
                        }
                        deque.addLast(line)
                    }
                }
            }
        }
        return deque.toList()
    }
}
