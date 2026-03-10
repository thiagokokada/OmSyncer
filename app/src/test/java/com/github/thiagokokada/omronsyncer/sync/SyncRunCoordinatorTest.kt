package com.github.thiagokokada.omronsyncer.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncRunCoordinatorTest {

    @Test
    fun runSingleFlight_rejectsSecondSyncWhileFirstIsActive() = runBlocking {
        val coordinator = SyncRunCoordinator(
            store = FakeStore(),
            nowMillis = { 1_000L },
            staleTimeoutMillis = 60_000L,
        )
        var secondSyncRejected = false

        coordinator.runSingleFlight("manual") {
            val error = runCatching {
                coordinator.runSingleFlight("nearby") {
                    fail("Expected the second sync to be rejected.")
                }
            }.exceptionOrNull()

            secondSyncRejected = error is SyncAlreadyInProgressException &&
                error.activeSource == "manual"
        }

        assertTrue(secondSyncRejected)
        assertFalse(coordinator.isSyncActive())
    }

    @Test
    fun isSyncActive_clearsStaleLease() {
        val store = FakeStore().apply {
            setActive(
                source = "nearby",
                token = "stale-token",
                acquiredAtMillis = 1_000L,
            )
        }
        val coordinator = SyncRunCoordinator(
            store = store,
            nowMillis = { 70_000L },
            staleTimeoutMillis = 60_000L,
        )

        assertFalse(coordinator.isSyncActive())
        assertTrue(store.activeSource() == null)
        assertTrue(store.activeToken() == null)
    }

    @Test
    fun hasSuccessfulSyncSince_returnsTrueOnlyAfterSuccessfulRun() = runBlocking {
        var nowMillis = 1_000L
        val coordinator = SyncRunCoordinator(
            store = FakeStore(),
            nowMillis = { nowMillis },
            staleTimeoutMillis = 60_000L,
        )

        coordinator.runSingleFlight("manual") {
            nowMillis = 2_000L
        }

        assertFalse(coordinator.hasSuccessfulSyncSince(2_001L))
        assertTrue(coordinator.hasSuccessfulSyncSince(2_000L))
        assertTrue(coordinator.hasSuccessfulSyncSince(1_500L))
    }

    @Test
    fun failedRun_releasesLeaseWithoutMarkingSuccessfulCompletion() = runBlocking {
        var nowMillis = 1_000L
        val coordinator = SyncRunCoordinator(
            store = FakeStore(),
            nowMillis = { nowMillis },
            staleTimeoutMillis = 60_000L,
        )

        runCatching {
            coordinator.runSingleFlight("manual") {
                nowMillis = 2_000L
                throw IllegalStateException("boom")
            }
        }

        assertFalse(coordinator.isSyncActive())
        assertFalse(coordinator.hasSuccessfulSyncSince(1_000L))
    }

    private class FakeStore : SyncRunCoordinator.Store {
        private var activeSource: String? = null
        private var activeToken: String? = null
        private var activeAcquiredAtMillis: Long = -1L
        private var lastSuccessfulCompletionAtMillis: Long? = null

        override fun activeSource(): String? = activeSource

        override fun activeToken(): String? = activeToken

        override fun activeAcquiredAtMillis(): Long = activeAcquiredAtMillis

        override fun lastSuccessfulCompletionAtMillis(): Long? = lastSuccessfulCompletionAtMillis

        override fun setActive(source: String, token: String, acquiredAtMillis: Long) {
            activeSource = source
            activeToken = token
            activeAcquiredAtMillis = acquiredAtMillis
        }

        override fun clearActive() {
            activeSource = null
            activeToken = null
            activeAcquiredAtMillis = -1L
        }

        override fun clearActive(token: String) {
            if (activeToken != token) {
                return
            }
            clearActive()
        }

        override fun setLastSuccessfulCompletionAtMillis(timestampMillis: Long) {
            lastSuccessfulCompletionAtMillis = timestampMillis
        }
    }
}
