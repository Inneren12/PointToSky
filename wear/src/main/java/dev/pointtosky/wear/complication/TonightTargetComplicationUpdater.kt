package dev.pointtosky.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.pointtosky.core.logging.LogBus
import java.time.Instant
import java.util.concurrent.TimeUnit

class TonightTargetComplicationUpdater(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val componentName = ComponentName(appContext, TonightTargetDataSourceService::class.java)
    private val requester = ComplicationDataSourceUpdateRequester.create(appContext, componentName)

    fun requestImmediateUpdate() {
        requester.requestUpdateAll()
    }

    fun schedule(at: Instant) {
        TonightTargetComplicationRefreshWorker.schedule(appContext, at)
    }
}

class TonightTargetComplicationRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val componentName = ComponentName(applicationContext, TonightTargetDataSourceService::class.java)
        ComplicationDataSourceUpdateRequester.create(applicationContext, componentName).requestUpdateAll()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "tonight_complication_refresh"

        fun schedule(
            context: Context,
            at: Instant,
        ) {
            val nowMs = System.currentTimeMillis()
            val atMs = at.toEpochMilli()
            val delayMs = (atMs - nowMs).coerceAtLeast(0L)
            val request =
                OneTimeWorkRequestBuilder<TonightTargetComplicationRefreshWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .addTag(UNIQUE_WORK)
                    .build()
            LogBus.event(
                name = "comp_tonight_request_update_scheduled",
                payload =
                    mapOf(
                        "atEpochMs" to atMs,
                        "delayMs" to delayMs,
                    ),
            )
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
