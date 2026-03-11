package com.github.thiagokokada.omronsyncer.omron

import org.junit.Assert.assertEquals
import org.junit.Test

class OmronSyncClientRetryPolicyTest {

    @Test
    fun commandRetryDelayMs_usesExponentialBackoffWithCap() {
        assertEquals(750L, OmronRetryPolicy.commandRetryDelayMs(1))
        assertEquals(1_500L, OmronRetryPolicy.commandRetryDelayMs(2))
        assertEquals(3_000L, OmronRetryPolicy.commandRetryDelayMs(3))
        assertEquals(5_000L, OmronRetryPolicy.commandRetryDelayMs(4))
        assertEquals(5_000L, OmronRetryPolicy.commandRetryDelayMs(5))
    }
}
