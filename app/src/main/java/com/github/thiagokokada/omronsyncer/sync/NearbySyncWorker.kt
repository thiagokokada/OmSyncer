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
        Log.d(TAG, "Nearby sync worker started. runAttemptCount=$runAttemptCount")
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
        val syncRunCoordinator = SyncRunCoordinator(applicationContext)
        val orchestrator = SyncOrchestrator(
            context = applicationContext,
            syncClient = OmronSyncClient(applicationContext),
            syncPreferences = preferences,
            syncRunCoordinator = syncRunCoordinator,
        )
        val triggeredAtMillis = inputData.getLong(KEY_TRIGGERED_AT_MILLIS, -1L)
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

        return try {
            delay(INITIAL_SYNC_DELAY_MS)
            if (syncRunCoordinator.hasSuccessfulSyncSince(triggeredAtMillis)) {
                return skipNearbySync(
                    preferences = preferences,
                    summary = applicationContext.getString(R.string.nearby_sync_skipped_recent_sync),
                )
            }
            val result = syncWithImmediateRetries(orchestrator)
            logSyncDiagnostics(source = "nearby", syncLog = result.syncLog)
            val summary = SyncUserMessageFormatter.nearbySummary(
                context = applicationContext,
                inserted = result.inserted,
                exportedToHealthConnect = result.healthConnectExportSummary != null,
            )
            preferences.persistLastNearbySyncStatus(
                timestampMillis = System.currentTimeMillis(),
                summary = summary,
            )
            SyncWorkerNotifications.showSuccessfulSync(
                context = applicationContext,
                notificationId = SUCCESS_NOTIFICATION_ID,
                inserted = result.inserted,
                exportedToHealthConnect = result.healthConnectExportSummary != null,
            )
            Log.d(TAG, "Nearby sync completed: $summary")
            SyncWorkerNotifications.dismiss(applicationContext, NOTIFICATION_ID)
            Result.success()
        } catch (error: Throwable) {
            if (error is OmronSyncClient.SyncException) {
                logSyncDiagnostics(source = "nearby-failure", syncLog = error.diagnostics.asText())
            }
            if (error is SyncAlreadyInProgressException) {
                return skipNearbySync(
                    preferences = preferences,
                    summary = applicationContext.getString(R.string.nearby_sync_skipped_in_progress),
                )
            }

            val summary = if (error is MissingBluetoothPermissionException) {
                applicationContext.getString(R.string.nearby_sync_skipped_permission)
            } else if (NearbySyncRetryPolicy.shouldRetryWithWorkManager(error, runAttemptCount)) {
                applicationContext.getString(
                    R.string.nearby_sync_retry_scheduled,
                    error.message ?: error.javaClass.simpleName,
                )
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
            if (NearbySyncRetryPolicy.shouldRetryWithWorkManager(error, runAttemptCount)) {
                Log.w(TAG, "Nearby sync failed with a retryable error; handing off retry to WorkManager.", error)
                return Result.retry()
            }
            preferences.clearLastNearbySyncTriggerAtMillis()
            Log.e(TAG, "Nearby sync failed: $summary", error)
            Result.failure()
        }
    }

    private suspend fun syncWithImmediateRetries(orchestrator: SyncOrchestrator): SyncExecutionResult {
        var lastError: Throwable? = null

        repeat(NearbySyncRetryPolicy.IMMEDIATE_ATTEMPT_COUNT) { attemptIndex ->
            try {
                return orchestrator.syncSelectedDevice(syncSource = "nearby")
            } catch (error: Throwable) {
                lastError = error
                val attemptNumber = attemptIndex + 1
                if (!NearbySyncRetryPolicy.shouldRetryImmediately(error, attemptNumber)) {
                    throw error
                }
                val retryDelayMillis = NearbySyncRetryPolicy.IMMEDIATE_RETRY_DELAY_MS * attemptNumber
                Log.w(
                    TAG,
                    "Nearby sync attempt $attemptNumber failed, retrying in " +
                        "${retryDelayMillis}ms: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
                delay(retryDelayMillis)
            }
        }

        throw IllegalStateException("Nearby sync failed after immediate retries.", lastError)
    }

    private fun logSyncDiagnostics(source: String, syncLog: String) {
        if (syncLog.isBlank()) {
            return
        }
        Log.d(SYNC_LOG_TAG, "[$source] diagnostics begin")
        syncLog.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                Log.d(SYNC_LOG_TAG, "[$source] $line")
        }
        Log.d(SYNC_LOG_TAG, "[$source] diagnostics end")
    }

    private fun skipNearbySync(
        preferences: SyncPreferences,
        summary: String,
    ): Result {
        preferences.persistLastNearbySyncStatus(
            timestampMillis = System.currentTimeMillis(),
            summary = summary,
        )
        SyncWorkerNotifications.dismiss(applicationContext, NOTIFICATION_ID)
        Log.d(TAG, summary)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nearby_sync"
        const val KEY_TRIGGERED_AT_MILLIS = "triggered_at_millis"
        private const val TAG = "OmSyncerNearby"
        private const val SYNC_LOG_TAG = "OmSyncerSync"
        private const val NOTIFICATION_ID = 1002
        private const val SUCCESS_NOTIFICATION_ID = 1003
        private const val INITIAL_SYNC_DELAY_MS = 6_000L
    }
}
