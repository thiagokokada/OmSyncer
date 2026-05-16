package com.github.thiagokokada.omronsyncer.sync

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition

class NearbySyncRegistrar(private val context: Context) {

    fun updateRegistration(enabled: Boolean, model: OmronDeviceDefinition) {
        if (!hasBluetoothScanPermission()) {
            return
        }
        val scanner = bluetoothLeScanner() ?: return
        val pendingIntent = scanPendingIntent()

        runCatching {
            if (!enabled) {
                scanner.stopScan(pendingIntent)
                return
            }

            // Filter on the UUID the monitor actually advertises. The HEM-6232T
            // advertises the standard Blood Pressure service (1810), not its
            // legacy GATT service UUID; advertisedServiceUuid carries that.
            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(
                        ParcelUuid(model.advertisedServiceUuid ?: model.serviceUuid),
                    )
                    .build(),
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .build()
            scanner.startScan(filters, settings, pendingIntent)
        }
    }

    private fun bluetoothLeScanner(): BluetoothLeScanner? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.bluetoothLeScanner
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scanPendingIntent(): PendingIntent {
        val intent = Intent(context, NearbySyncReceiver::class.java).apply {
            action = NearbySyncReceiver.ACTION_SCAN_RESULT
            `package` = context.packageName
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val REQUEST_CODE = 2001
    }
}
