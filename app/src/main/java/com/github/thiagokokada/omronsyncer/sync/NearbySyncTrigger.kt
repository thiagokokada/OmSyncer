package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

object NearbySyncTrigger {
    fun enqueueIfAllowed(
        context: Context,
        deviceAddress: String?,
    ) {
        val preferences = SyncPreferences(context)
        if (!preferences.nearbySyncEnabled()) {
            return
        }

        val selectedAddress = preferences.selectedDeviceAddress()
        if (
            selectedAddress != null &&
            !selectedAddress.equals(deviceAddress, ignoreCase = true)
        ) {
            Log.d(TAG, "Ignoring nearby trigger for non-selected device.")
            return
        }

        val now = System.currentTimeMillis()
        val lastTriggerAt = preferences.lastNearbySyncTriggerAtMillis()
        if (lastTriggerAt != null && now - lastTriggerAt < TRIGGER_COOLDOWN_MS) {
            Log.d(TAG, "Ignoring nearby trigger during cooldown window.")
            return
        }

        preferences.setLastNearbySyncTriggerAtMillis(now)
        Log.d(TAG, "Nearby monitor detected, enqueueing one-time sync worker.")

        val request = OneTimeWorkRequestBuilder<NearbySyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            NearbySyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val TAG = "OmSyncerNearby"
    private const val TRIGGER_COOLDOWN_MS = 5 * 60_000L
}
