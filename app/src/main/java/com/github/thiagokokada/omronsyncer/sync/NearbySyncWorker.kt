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
            onSuccess = { Result.success() },
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
                Log.e(TAG, "Nearby sync failed: $summary", error)
                Result.failure()
            },
        )
    }

    private suspend fun syncWithRetries(orchestrator: SyncOrchestrator): SyncExecutionResult {
        var lastError: Throwable? = null

        repeat(MAX_SYNC_ATTEMPTS) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            try {
                if (attemptIndex == 0) {
                    Log.d(TAG, "Starting nearby sync attempt $attemptNumber/$MAX_SYNC_ATTEMPTS.")
                } else {
                    Log.d(TAG, "Retrying nearby sync attempt $attemptNumber/$MAX_SYNC_ATTEMPTS.")
                }
                return orchestrator.syncSelectedDevice()
            } catch (error: Throwable) {
                lastError = error
                val hasRetryRemaining = attemptNumber < MAX_SYNC_ATTEMPTS
                if (!hasRetryRemaining || !isTransientConnectFailure(error)) {
                    throw error
                }
                val retryDelayMs = retryDelayMillis(attemptIndex)
                Log.w(
                    TAG,
                    "Transient connect failure on attempt $attemptNumber/$MAX_SYNC_ATTEMPTS; " +
                        "retrying in ${retryDelayMs / 1000}s.",
                    error,
                )
                delay(retryDelayMs)
            }
        }

        throw IllegalStateException("Nearby sync failed after retries.", lastError)
    }

    private fun retryDelayMillis(attemptIndex: Int): Long {
        return RETRY_DELAYS_MS.getOrElse(attemptIndex) { RETRY_DELAYS_MS.last() }
    }

    private fun isTransientConnectFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                "status=133" in message ||
                "Bluetooth device disconnected." in message ||
                "status=8 (GATT_INSUFFICIENT_AUTHORIZATION)" in message
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nearby_sync"
        private const val TAG = "OmSyncerNearby"
        private const val NOTIFICATION_ID = 1002
        private const val SUCCESS_NOTIFICATION_ID = 1003
        private const val MAX_SYNC_ATTEMPTS = 4
        private const val INITIAL_SYNC_DELAY_MS = 6_000L
        private val RETRY_DELAYS_MS = listOf(8_000L, 15_000L, 25_000L)
    }
}
