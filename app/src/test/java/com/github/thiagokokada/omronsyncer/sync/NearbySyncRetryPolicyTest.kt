package com.github.thiagokokada.omronsyncer.sync

import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.RequestFailedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySyncRetryPolicyTest {

    @Test
    fun retryableFailure_retriesImmediatelyBeforeImmediateLimit() {
        assertTrue(
            NearbySyncRetryPolicy.shouldRetryImmediately(
                error = retryableFailure(),
                attemptNumber = 1,
            ),
        )
    }

    @Test
    fun retryableFailure_doesNotRetryImmediatelyAtImmediateLimit() {
        assertFalse(
            NearbySyncRetryPolicy.shouldRetryImmediately(
                error = retryableFailure(),
                attemptNumber = NearbySyncRetryPolicy.IMMEDIATE_ATTEMPT_COUNT,
            ),
        )
    }

    @Test
    fun retryableFailure_retriesWithWorkManagerBeforeRunLimit() {
        assertTrue(
            NearbySyncRetryPolicy.shouldRetryWithWorkManager(
                error = retryableFailure(),
                runAttemptCount = NearbySyncRetryPolicy.WORK_REQUEST_MAX_ATTEMPTS - 2,
            ),
        )
    }

    @Test
    fun retryableFailure_doesNotRetryWithWorkManagerAtRunLimit() {
        assertFalse(
            NearbySyncRetryPolicy.shouldRetryWithWorkManager(
                error = retryableFailure(),
                runAttemptCount = NearbySyncRetryPolicy.WORK_REQUEST_MAX_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun nonRetryableFailure_isNotRetried() {
        val error = IllegalStateException("Device configuration is invalid.")

        assertFalse(NearbySyncRetryPolicy.shouldRetryImmediately(error, attemptNumber = 1))
        assertFalse(NearbySyncRetryPolicy.shouldRetryWithWorkManager(error, runAttemptCount = 0))
    }

    private fun retryableFailure(): Throwable {
        return RequestFailedException(
            null,
            FailCallback.REASON_TIMEOUT,
        )
    }
}
