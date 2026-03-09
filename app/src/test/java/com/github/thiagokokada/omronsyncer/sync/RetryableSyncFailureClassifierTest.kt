package com.github.thiagokokada.omronsyncer.sync

import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryableSyncFailureClassifierTest {

    @Test
    fun unknownGattStatus_isRetryable() {
        assertTrue(
            RetryableSyncFailureClassifier.isRetryable(
                OmronSyncClient.UnknownGattStatusException(
                    operation = "Bluetooth connect failed",
                    status = 147,
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
    fun knownConnectFailure_isNotRetryable() {
        assertFalse(
            RetryableSyncFailureClassifier.isRetryable(
                OmronSyncClient.BluetoothConnectFailedException(
                    status = 8,
                    description = "GATT_INSUFFICIENT_AUTHORIZATION",
                ),
            ),
        )
    }

    @Test
    fun unknownGattOperationFailure_isRetryable() {
        assertTrue(
            RetryableSyncFailureClassifier.isRetryable(
                OmronSyncClient.UnknownGattStatusException(
                    operation = "Descriptor write failed",
                    status = 133,
                ),
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
}
