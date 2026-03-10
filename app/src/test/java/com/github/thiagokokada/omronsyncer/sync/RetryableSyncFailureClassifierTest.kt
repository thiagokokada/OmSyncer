package com.github.thiagokokada.omronsyncer.sync

import android.bluetooth.BluetoothGatt
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import no.nordicsemi.android.ble.Request
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.error.GattError
import no.nordicsemi.android.ble.exception.DeviceDisconnectedException
import no.nordicsemi.android.ble.exception.RequestFailedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryableSyncFailureClassifierTest {

    @Test
    fun commandTimeout_isRetryable() {
        assertTrue(
            RetryableSyncFailureClassifier.isRetryable(
                OmronSyncClient.CommandTimeoutException(
                    requestFailed(FailCallback.REASON_TIMEOUT),
                ),
            ),
        )
    }

    @Test
    fun wrappedRetryableFailure_isRetryable() {
        val error = IllegalStateException(
            "Top-level sync failure",
            DeviceDisconnectedException(),
        )

        assertTrue(RetryableSyncFailureClassifier.isRetryable(error))
    }

    @Test
    fun knownAuthorizationFailure_isNotRetryable() {
        assertFalse(
            RetryableSyncFailureClassifier.isRetryable(
                requestFailed(BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION),
            ),
        )
    }

    @Test
    fun gattError133_isRetryable() {
        assertTrue(
            RetryableSyncFailureClassifier.isRetryable(
                requestFailed(GattError.GATT_ERROR),
            ),
        )
    }

    @Test
    fun nonRetryableFailure_isNotRetryable() {
        assertFalse(
            RetryableSyncFailureClassifier.isRetryable(
                IllegalStateException("Notification descriptor not found."),
            ),
        )
    }

    private fun requestFailed(status: Int): RequestFailedException {
        return RequestFailedException(
            Request.newEnableNotificationsRequest(null),
            status,
        )
    }
}
