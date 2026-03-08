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
        enqueueDetectedDevice(
            deviceAddress = address,
            source = NearbySyncTrigger.TriggerSource.BLE_APPEARED,
        )
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) {
            return
        }
        val source = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> NearbySyncTrigger.TriggerSource.BLE_APPEARED
            DevicePresenceEvent.EVENT_BT_CONNECTED -> NearbySyncTrigger.TriggerSource.BT_CONNECTED
            else -> return
        }

        val deviceAddress = CompanionDeviceSyncManager(this).associationAddress(event.associationId)
        if (deviceAddress == null) {
            Log.d(TAG, "Ignoring companion presence event without a resolvable association.")
            return
        }

        enqueueDetectedDevice(deviceAddress, source)
    }

    private fun enqueueDetectedDevice(
        deviceAddress: String,
        source: NearbySyncTrigger.TriggerSource,
    ) {
        Log.d(TAG, "Companion device event=$source, enqueueing nearby sync worker.")
        NearbySyncTrigger.enqueueIfAllowed(this, deviceAddress, source)
    }

    companion object {
        private const val TAG = "OmSyncerCompanion"
    }
}
