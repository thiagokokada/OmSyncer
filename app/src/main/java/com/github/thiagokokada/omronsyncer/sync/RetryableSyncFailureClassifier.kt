package com.github.thiagokokada.omronsyncer.sync

import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient

internal object RetryableSyncFailureClassifier {

    fun isRetryable(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }
            .any { it is OmronSyncClient.RetryableSyncFailure }
    }
}
