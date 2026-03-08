package com.github.thiagokokada.omronsyncer.sync

import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import android.util.Log

class OmSyncerCompanionDeviceService : CompanionDeviceService() {

    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= 36) {
            return
        }
        enqueueDetectedDevice(address)
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36 || event.event != DevicePresenceEvent.EVENT_BLE_APPEARED) {
            return
        }

        val deviceAddress = CompanionDeviceSyncManager(this).associationAddress(event.associationId)
        if (deviceAddress == null) {
            Log.d(TAG, "Ignoring companion presence event without a resolvable association.")
            return
        }

        enqueueDetectedDevice(deviceAddress)
    }

    private fun enqueueDetectedDevice(deviceAddress: String) {
        Log.d(TAG, "Companion device appeared, enqueueing nearby sync worker.")
        NearbySyncTrigger.enqueueIfAllowed(this, deviceAddress)
    }

    companion object {
        private const val TAG = "OmSyncerCompanion"
    }
}
