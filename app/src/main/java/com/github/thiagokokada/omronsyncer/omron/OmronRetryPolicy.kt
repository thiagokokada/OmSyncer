package com.github.thiagokokada.omronsyncer.omron

internal object OmronRetryPolicy {
    const val COMMAND_RETRY_COUNT = 5
    private const val COMMAND_RETRY_BASE_DELAY_MS = 750L
    private const val COMMAND_RETRY_MAX_DELAY_MS = 5_000L

    fun commandRetryDelayMs(attempt: Int): Long {
        require(attempt >= 1) {
            "Attempt number must be at least 1."
        }
        val multiplier = 1L shl (attempt - 1)
        return (COMMAND_RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(COMMAND_RETRY_MAX_DELAY_MS)
    }
}
