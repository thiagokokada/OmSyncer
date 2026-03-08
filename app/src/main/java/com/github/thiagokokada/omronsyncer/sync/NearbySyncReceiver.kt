package com.github.thiagokokada.omronsyncer.sync

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.github.thiagokokada.omronsyncer.R

class NearbySyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_RESULT) {
            return
        }

        val preferences = SyncPreferences(context)
        if (!preferences.nearbySyncEnabled()) {
            return
        }

        val results = intent.getParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ScanResult::class.java,
        ).orEmpty()
        if (results.isEmpty()) {
            Log.d(TAG, "Received nearby scan callback with no results.")
            return
        }

        val selectedAddress = preferences.selectedDeviceAddress()
        val matchesSelectedDevice = selectedAddress == null || results.any { scanResult ->
            scanResult.device.address == selectedAddress
        }
        if (!matchesSelectedDevice) {
            Log.d(TAG, "Ignoring nearby scan callback for non-selected device.")
            return
        }

        val now = System.currentTimeMillis()
        val lastTriggerAt = preferences.lastNearbySyncTriggerAtMillis()
        if (lastTriggerAt != null && now - lastTriggerAt < preferences.nearbySyncCooldownMillis()) {
            Log.d(TAG, "Ignoring nearby scan callback during cooldown window.")
            return
        }

        val request = OneTimeWorkRequestBuilder<NearbySyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        preferences.setLastNearbySyncTriggerAtMillis(now)
        Log.d(TAG, "Nearby monitor detected, enqueueing one-time sync worker.")

        WorkManager.getInstance(context).enqueueUniqueWork(
            NearbySyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.github.thiagokokada.omronsyncer.action.NEARBY_SCAN_RESULT"
        private const val TAG = "OmSyncerNearby"
    }
}
