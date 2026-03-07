package com.github.thiagokokada.omronsyncer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.thiagokokada.omronsyncer.R
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient

class BackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val preferences = SyncPreferences(applicationContext)
        val orchestrator = SyncOrchestrator(
            context = applicationContext,
            syncClient = OmronSyncClient(applicationContext),
            syncPreferences = preferences,
        )

        if (preferences.selectedDeviceAddress() == null) {
            preferences.setLastBackgroundSyncAtMillis(System.currentTimeMillis())
            preferences.setLastBackgroundSyncSummary(
                applicationContext.getString(R.string.background_sync_skipped_no_device),
            )
            return Result.success()
        }

        return runCatching {
            val result = orchestrator.syncSelectedDevice()
            val summary = result.healthConnectExportSummary?.let { export ->
                applicationContext.getString(
                    R.string.background_sync_summary_success_health_connect,
                    result.imported,
                    result.inserted,
                    result.duplicates,
                    export.bloodPressureExported,
                    export.heartRateExported,
                )
            } ?: applicationContext.getString(
                R.string.background_sync_summary_success,
                result.imported,
                result.inserted,
                result.duplicates,
            )
            preferences.setLastBackgroundSyncAtMillis(System.currentTimeMillis())
            preferences.setLastBackgroundSyncSummary(summary)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                preferences.setLastBackgroundSyncAtMillis(System.currentTimeMillis())
                preferences.setLastBackgroundSyncSummary(
                    applicationContext.getString(
                        R.string.background_sync_failed,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                Result.retry()
            },
        )
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.background_sync_notification_title))
            .setContentText(applicationContext.getString(R.string.background_sync_notification_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existingChannel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existingChannel != null) {
            return
        }

        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.background_sync_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "background_sync"
        private const val NOTIFICATION_CHANNEL_ID = "background_sync"
        private const val NOTIFICATION_ID = 1001
    }
}
