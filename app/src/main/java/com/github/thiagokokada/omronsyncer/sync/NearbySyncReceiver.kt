package com.github.thiagokokada.omronsyncer.sync

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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

        val matchingAddress = results.firstOrNull()?.device?.address
        val selectedAddress = preferences.selectedDeviceAddress()
        if (selectedAddress != null && selectedAddress != matchingAddress) {
            Log.d(TAG, "Ignoring nearby scan callback for non-selected device.")
            return
        }

        NearbySyncTrigger.enqueueIfAllowed(context, matchingAddress)
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.github.thiagokokada.omronsyncer.action.NEARBY_SCAN_RESULT"
        private const val TAG = "OmSyncerNearby"
    }
}
