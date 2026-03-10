package com.github.thiagokokada.omronsyncer.sync

import android.bluetooth.BluetoothGatt
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.error.GattError
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.RequestFailedException

internal object RetryableSyncFailureClassifier {

    fun isRetryable(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }
            .any(::isRetryableCause)
    }

    private fun isRetryableCause(error: Throwable): Boolean {
        return when (error) {
            is OmronSyncClient.CommandTimeoutException -> true
            is DeviceDisconnectedException -> true
            is RequestFailedException -> error.status in RETRYABLE_REQUEST_STATUSES
            else -> false
        }
    }

    private val RETRYABLE_REQUEST_STATUSES = setOf(
        FailCallback.REASON_DEVICE_DISCONNECTED,
        FailCallback.REASON_TIMEOUT,
        BluetoothGatt.GATT_CONNECTION_CONGESTED,
        BluetoothGatt.GATT_FAILURE,
        GattError.GATT_ERROR,
        GattError.GATT_TIMEOUT,
    )
}
