package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

class SyncRunCoordinator internal constructor(
    private val store: Store,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val staleTimeoutMillis: Long = DEFAULT_STALE_TIMEOUT_MILLIS,
) {

    constructor(context: Context) : this(
        store = PreferencesStore(
            context.getSharedPreferences(SyncPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
        // Keep the lock's stale timeout above the configured sync timeout so a
        // legitimately slow sync is never mistaken for a dead one.
        staleTimeoutMillis = SyncPreferences(context).syncTimeoutMillis() + STALE_TIMEOUT_MARGIN_MILLIS,
    )

    suspend fun <T> runSingleFlight(
        syncSource: String,
        block: suspend () -> T,
    ): T {
        val lease = acquireOrThrow(syncSource)
        return try {
            val result = block()
            markSuccessfulCompletion()
            result
        } finally {
            release(lease)
        }
    }

    @Synchronized
    fun isSyncActive(): Boolean {
        val activeLease = activeLease() ?: return false
        if (!activeLease.isStale(nowMillis(), staleTimeoutMillis)) {
            return true
        }
        store.clearActive()
        return false
    }

    @Synchronized
    fun clearActiveSync() {
        store.clearActive()
    }

    @Synchronized
    fun hasSuccessfulSyncSince(timestampMillis: Long): Boolean {
        if (timestampMillis <= 0L) {
            return false
        }
        val completedAtMillis = store.lastSuccessfulCompletionAtMillis() ?: return false
        return completedAtMillis >= timestampMillis
    }

    @Synchronized
    internal fun acquireOrThrow(syncSource: String): Lease {
        val now = nowMillis()
        val activeLease = activeLease()
        if (activeLease != null && !activeLease.isStale(now, staleTimeoutMillis)) {
            throw SyncAlreadyInProgressException(activeLease.source)
        }
        if (activeLease != null) {
            store.clearActive()
        }

        val lease = Lease(
            source = syncSource,
            token = UUID.randomUUID().toString(),
            acquiredAtMillis = now,
        )
        store.setActive(
            source = lease.source,
            token = lease.token,
            acquiredAtMillis = lease.acquiredAtMillis,
        )
        return lease
    }

    @Synchronized
    internal fun markSuccessfulCompletion() {
        store.setLastSuccessfulCompletionAtMillis(nowMillis())
    }

    @Synchronized
    internal fun release(lease: Lease) {
        store.clearActive(lease.token)
    }

    private fun activeLease(): Lease? {
        val source = store.activeSource() ?: return null
        val token = store.activeToken() ?: return null
        val acquiredAtMillis = store.activeAcquiredAtMillis()
        if (acquiredAtMillis <= 0L) {
            store.clearActive()
            return null
        }
        return Lease(
            source = source,
            token = token,
            acquiredAtMillis = acquiredAtMillis,
        )
    }

    internal data class Lease(
        val source: String,
        val token: String,
        val acquiredAtMillis: Long,
    ) {
        fun isStale(nowMillis: Long, staleTimeoutMillis: Long): Boolean {
            return nowMillis < acquiredAtMillis || nowMillis - acquiredAtMillis >= staleTimeoutMillis
        }
    }

    internal interface Store {
        fun activeSource(): String?
        fun activeToken(): String?
        fun activeAcquiredAtMillis(): Long
        fun lastSuccessfulCompletionAtMillis(): Long?
        fun setActive(source: String, token: String, acquiredAtMillis: Long)
        fun clearActive()
        fun clearActive(token: String)
        fun setLastSuccessfulCompletionAtMillis(timestampMillis: Long)
    }

    private class PreferencesStore(
        private val preferences: SharedPreferences,
    ) : Store {
        override fun activeSource(): String? {
            return preferences.getString(PREF_ACTIVE_SOURCE, null)
        }

        override fun activeToken(): String? {
            return preferences.getString(PREF_ACTIVE_TOKEN, null)
        }

        override fun activeAcquiredAtMillis(): Long {
            return preferences.getLong(PREF_ACTIVE_ACQUIRED_AT_MILLIS, -1L)
        }

        override fun lastSuccessfulCompletionAtMillis(): Long? {
            val value = preferences.getLong(PREF_LAST_SUCCESSFUL_COMPLETION_AT_MILLIS, -1L)
            return value.takeIf { it > 0L }
        }

        override fun setActive(source: String, token: String, acquiredAtMillis: Long) {
            preferences.edit(commit = true) {
                putString(PREF_ACTIVE_SOURCE, source)
                putString(PREF_ACTIVE_TOKEN, token)
                putLong(PREF_ACTIVE_ACQUIRED_AT_MILLIS, acquiredAtMillis)
            }
        }

        override fun clearActive() {
            preferences.edit(commit = true) {
                remove(PREF_ACTIVE_SOURCE)
                remove(PREF_ACTIVE_TOKEN)
                remove(PREF_ACTIVE_ACQUIRED_AT_MILLIS)
            }
        }

        override fun clearActive(token: String) {
            if (preferences.getString(PREF_ACTIVE_TOKEN, null) != token) {
                return
            }
            clearActive()
        }

        override fun setLastSuccessfulCompletionAtMillis(timestampMillis: Long) {
            preferences.edit(commit = true) {
                putLong(PREF_LAST_SUCCESSFUL_COMPLETION_AT_MILLIS, timestampMillis)
            }
        }
    }

    companion object {
        internal const val DEFAULT_STALE_TIMEOUT_MILLIS = 2 * 60_000L
        internal const val STALE_TIMEOUT_MARGIN_MILLIS = 60_000L

        private const val PREF_ACTIVE_SOURCE = "sync_run_active_source"
        private const val PREF_ACTIVE_TOKEN = "sync_run_active_token"
        private const val PREF_ACTIVE_ACQUIRED_AT_MILLIS = "sync_run_active_acquired_at_millis"
        private const val PREF_LAST_SUCCESSFUL_COMPLETION_AT_MILLIS =
            "sync_run_last_successful_completion_at_millis"
    }
}

class SyncAlreadyInProgressException(
    val activeSource: String,
) : IllegalStateException("Another sync is already in progress.")
