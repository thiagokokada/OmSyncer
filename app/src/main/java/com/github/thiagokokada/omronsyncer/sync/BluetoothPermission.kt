package com.github.thiagokokada.omronsyncer.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

fun Context.hasBluetoothConnectPermission(): Boolean {
    return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

class MissingBluetoothPermissionException :
    IllegalStateException("Bluetooth permission not granted.")
