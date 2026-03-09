package com.github.thiagokokada.omronsyncer.sync

import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryableSyncFailureClassifierTest {

    @Test
    fun bluetoothConnectFailure_isRetryable() {
        assertTrue(
            RetryableSyncFailureClassifier.isRetryable(
                OmronSyncClient.BluetoothConnectFailedException(
                    status = 147,
                    description = "UNKNOWN",
                ),
            ),
        )
    }

    @Test
    fun wrappedRetryableFailure_isRetryable() {
        val error = IllegalStateException(
            "Top-level sync failure",
            OmronSyncClient.CommandFailedException(
                attempts = 3,
                cause = OmronSyncClient.BluetoothDeviceDisconnectedException(),
            ),
        )

        assertTrue(RetryableSyncFailureClassifier.isRetryable(error))
    }

    @Test
    fun nonRetryableFailure_isNotRetryable() {
        assertFalse(
            RetryableSyncFailureClassifier.isRetryable(
                IllegalStateException("Notification descriptor not found."),
            ),
        )
    }
}
