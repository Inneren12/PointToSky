package dev.pointtosky.core.logging

import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel

/**
 * Безопасные (небросающие) флуш/синк-утилиты для краш-пути.
 * Перегрузки мостят к extension'ам из IoSync.kt.
 */
object CrashSafeFlush {

    /** Фаллбек без аргументов — когда нет прямого доступа к файлу/стриму. */
    @JvmStatic
    fun flushAndSyncBlocking() {
        // Минимум — прибираем stdout/stderr, не мешаем крэшу завершиться.
        try { System.out.flush() } catch (_: Throwable) { }
        try { System.err.flush() } catch (_: Throwable) { }
    }

    /** Предпочтительный вариант: конкретный файл краш-лога. */
    @JvmStatic
    fun flushAndSyncBlocking(target: File) = target.flushAndSyncBlocking()

    /** Когда открыт только поток. */
    @JvmStatic
    fun flushAndSyncBlocking(target: OutputStream) = target.flushAndSyncBlocking()

    /** Низкоуровневый случай: есть FileChannel. */
    @JvmStatic
    fun flushAndSyncBlocking(target: FileChannel) = target.flushAndSyncBlocking()
}
