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
        source: TriggerSource = TriggerSource.BLE_APPEARED,
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
        val withinCooldown = lastTriggerAt != null && now - lastTriggerAt < TRIGGER_COOLDOWN_MS
        if (withinCooldown && !source.bypassesCooldown) {
            Log.d(TAG, "Ignoring nearby trigger during cooldown window.")
            return
        }
        if (withinCooldown && source.bypassesCooldown) {
            Log.d(TAG, "Allowing nearby trigger during cooldown because source=$source.")
        }

        preferences.setLastNearbySyncTriggerAtMillis(now)
        Log.d(TAG, "Nearby monitor detected from $source, enqueueing one-time sync worker.")

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

    enum class TriggerSource(val bypassesCooldown: Boolean) {
        BLE_APPEARED(false),
        BT_CONNECTED(true),
    }
}
