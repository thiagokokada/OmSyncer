package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.thiagokokada.omronsyncer.R
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient

class BackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        setForeground(
            SyncWorkerNotifications.createForegroundInfo(
                context = applicationContext,
                notificationId = NOTIFICATION_ID,
                titleResId = R.string.background_sync_notification_title,
                bodyResId = R.string.background_sync_notification_body,
            ),
        )

        val preferences = SyncPreferences(applicationContext)
        val orchestrator = SyncOrchestrator(
            context = applicationContext,
            syncClient = OmronSyncClient(applicationContext),
            syncPreferences = preferences,
        )

        if (preferences.selectedDeviceAddress() == null) {
            preferences.persistLastBackgroundSyncStatus(
                timestampMillis = System.currentTimeMillis(),
                summary = applicationContext.getString(R.string.background_sync_skipped_no_device),
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
            preferences.persistLastBackgroundSyncStatus(
                timestampMillis = System.currentTimeMillis(),
                summary = summary,
            )
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                preferences.persistLastBackgroundSyncStatus(
                    timestampMillis = System.currentTimeMillis(),
                    summary = if (error is MissingBluetoothPermissionException) {
                        applicationContext.getString(R.string.status_missing_permission)
                    } else {
                        applicationContext.getString(
                            R.string.background_sync_failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    },
                )
                if (error is MissingBluetoothPermissionException) Result.failure() else Result.retry()
            },
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "background_sync"
        private const val NOTIFICATION_ID = 1001
    }
}
