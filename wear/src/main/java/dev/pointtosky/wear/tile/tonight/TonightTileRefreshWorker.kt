package dev.pointtosky.wear.tile.tonight

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.wear.tiles.TileService
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * WorkManager-воркер: дергает обновление тайла и всё.
 * Следующее расписание назначаем в onTileRequest (если нужно).
 */
class TonightTileRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // NB: API ожидает Class<? extends TileService>, а не ComponentName
        TileService.getUpdater(applicationContext).requestUpdate(TonightTileService::class.java)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "tonight_tile_refresh"

        fun schedule(context: Context, at: Instant) {
            val delayMs = (at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = OneTimeWorkRequestBuilder<TonightTileRefreshWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_WORK)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
