package com.github.thiagokokada.omronsyncer.sync

internal object NearbySyncRetryPolicy {
    const val IMMEDIATE_ATTEMPT_COUNT = 2
    const val IMMEDIATE_RETRY_DELAY_MS = 5_000L
    const val WORK_REQUEST_MAX_ATTEMPTS = 4
    const val WORK_REQUEST_BACKOFF_DELAY_MS = 20_000L

    fun shouldRetryImmediately(
        error: Throwable,
        attemptNumber: Int,
    ): Boolean {
        return attemptNumber < IMMEDIATE_ATTEMPT_COUNT &&
            RetryableSyncFailureClassifier.isRetryable(error)
    }

    fun shouldRetryWithWorkManager(
        error: Throwable,
        runAttemptCount: Int,
    ): Boolean {
        return runAttemptCount + 1 < WORK_REQUEST_MAX_ATTEMPTS &&
            RetryableSyncFailureClassifier.isRetryable(error)
    }
}
