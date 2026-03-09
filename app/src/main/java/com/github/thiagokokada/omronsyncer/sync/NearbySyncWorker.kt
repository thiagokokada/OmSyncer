package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.thiagokokada.omronsyncer.R
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import kotlinx.coroutines.delay

class NearbySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Nearby sync worker started.")
        SyncWorkerNotifications.showRunningSync(
            context = applicationContext,
            notificationId = NOTIFICATION_ID,
            titleResId = R.string.nearby_sync_notification_title,
            bodyResId = R.string.nearby_sync_notification_body,
        )
        SyncWorkerNotifications.promoteToForegroundIfAllowed(
            worker = this,
            context = applicationContext,
            notificationId = NOTIFICATION_ID,
            titleResId = R.string.nearby_sync_notification_title,
            bodyResId = R.string.nearby_sync_notification_body,
            logTag = TAG,
        )

        val preferences = SyncPreferences(applicationContext)
        val orchestrator = SyncOrchestrator(
            context = applicationContext,
            syncClient = OmronSyncClient(applicationContext),
            syncPreferences = preferences,
        )
        preferences.persistLastNearbySyncStatus(
            timestampMillis = System.currentTimeMillis(),
            summary = applicationContext.getString(R.string.nearby_sync_summary_triggered),
        )

        if (preferences.selectedDeviceAddress() == null) {
            preferences.persistLastNearbySyncStatus(
                timestampMillis = System.currentTimeMillis(),
                summary = applicationContext.getString(R.string.nearby_sync_skipped_no_device),
            )
            SyncWorkerNotifications.dismiss(applicationContext, NOTIFICATION_ID)
            Log.d(TAG, "Nearby sync skipped because no device is selected.")
            return Result.success()
        }

        return runCatching {
            delay(INITIAL_SYNC_DELAY_MS)
            val result = syncWithRetries(orchestrator)
            val summary = result.healthConnectExportSummary?.let {
                applicationContext.getString(
                    R.string.nearby_sync_summary_success_health_connect,
                    result.imported,
                    result.inserted,
                    result.duplicates,
                )
            } ?: applicationContext.getString(
                R.string.nearby_sync_summary_success,
                result.imported,
                result.inserted,
                result.duplicates,
            )
            preferences.persistLastNearbySyncStatus(
                timestampMillis = System.currentTimeMillis(),
                summary = summary,
            )
            SyncWorkerNotifications.showSuccessfulSync(
                context = applicationContext,
                notificationId = SUCCESS_NOTIFICATION_ID,
                fetched = result.imported,
                inserted = result.inserted,
                duplicates = result.duplicates,
                exportedToHealthConnect = result.healthConnectExportSummary != null,
            )
            Log.d(TAG, "Nearby sync completed: $summary")
        }.fold(
            onSuccess = {
                SyncWorkerNotifications.dismiss(applicationContext, NOTIFICATION_ID)
                Result.success()
            },
            onFailure = { error ->
                val summary = if (error is MissingBluetoothPermissionException) {
                    applicationContext.getString(R.string.nearby_sync_skipped_permission)
                } else {
                    applicationContext.getString(
                        R.string.nearby_sync_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
                preferences.persistLastNearbySyncStatus(
                    timestampMillis = System.currentTimeMillis(),
                    summary = summary,
                )
                SyncWorkerNotifications.dismiss(applicationContext, NOTIFICATION_ID)
                Log.e(TAG, "Nearby sync failed: $summary", error)
                Result.failure()
            },
        )
    }

    private suspend fun syncWithRetries(orchestrator: SyncOrchestrator): SyncExecutionResult {
        var lastError: Throwable? = null

        repeat(MAX_SYNC_ATTEMPTS) { attemptIndex ->
            try {
                return orchestrator.syncSelectedDevice()
            } catch (error: Throwable) {
                lastError = error
                val hasRetryRemaining = attemptIndex + 1 < MAX_SYNC_ATTEMPTS
                if (!hasRetryRemaining || !isRetryableBackgroundFailure(error)) {
                    throw error
                }
                val retryDelayMillis = RETRY_DELAY_MS * (attemptIndex + 1L)
                Log.w(
                    TAG,
                    "Nearby sync attempt ${attemptIndex + 1} failed, retrying in " +
                        "${retryDelayMillis}ms: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
                delay(retryDelayMillis)
            }
        }

        throw IllegalStateException("Nearby sync failed after retries.", lastError)
    }

    private fun isRetryableBackgroundFailure(error: Throwable): Boolean {
        return RetryableSyncFailureClassifier.isRetryable(error)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nearby_sync"
        private const val TAG = "OmSyncerNearby"
        private const val NOTIFICATION_ID = 1002
        private const val SUCCESS_NOTIFICATION_ID = 1003
        private const val MAX_SYNC_ATTEMPTS = 5
        private const val INITIAL_SYNC_DELAY_MS = 6_000L
        private const val RETRY_DELAY_MS = 5_000L
    }
}
