package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import com.github.thiagokokada.omronsyncer.R

internal enum class SyncUserMessageVariant {
    NO_NEW,
    NO_NEW_HEALTH_CONNECT,
    SAVED_NEW,
    SAVED_NEW_HEALTH_CONNECT,
}

object SyncUserMessageFormatter {

    fun nearbySummary(
        context: Context,
        inserted: Int,
        exportedToHealthConnect: Boolean,
    ): String {
        return nearbySummary(
            inserted = inserted,
            exportedToHealthConnect = exportedToHealthConnect,
            strings = AndroidSyncUserMessageStrings(context),
        )
    }

    fun successNotificationBody(
        context: Context,
        timestampText: String,
        inserted: Int,
        exportedToHealthConnect: Boolean,
    ): String {
        return successNotificationBody(
            timestampText = timestampText,
            inserted = inserted,
            exportedToHealthConnect = exportedToHealthConnect,
            strings = AndroidSyncUserMessageStrings(context),
        )
    }

    internal fun nearbySummary(
        inserted: Int,
        exportedToHealthConnect: Boolean,
        strings: SyncUserMessageStrings,
    ): String {
        return when (messageVariant(inserted, exportedToHealthConnect)) {
            SyncUserMessageVariant.NO_NEW ->
                strings.nearbyNoNew()

            SyncUserMessageVariant.NO_NEW_HEALTH_CONNECT ->
                strings.nearbyNoNewHealthConnect()

            SyncUserMessageVariant.SAVED_NEW ->
                strings.nearbySavedNew(inserted)

            SyncUserMessageVariant.SAVED_NEW_HEALTH_CONNECT ->
                strings.nearbySavedNewHealthConnect(inserted)
        }
    }

    internal fun successNotificationBody(
        timestampText: String,
        inserted: Int,
        exportedToHealthConnect: Boolean,
        strings: SyncUserMessageStrings,
    ): String {
        return when (messageVariant(inserted, exportedToHealthConnect)) {
            SyncUserMessageVariant.NO_NEW ->
                strings.notificationNoNew(timestampText)

            SyncUserMessageVariant.NO_NEW_HEALTH_CONNECT ->
                strings.notificationNoNewHealthConnect(timestampText)

            SyncUserMessageVariant.SAVED_NEW ->
                strings.notificationSavedNew(timestampText, inserted)

            SyncUserMessageVariant.SAVED_NEW_HEALTH_CONNECT ->
                strings.notificationSavedNewHealthConnect(timestampText, inserted)
        }
    }

    internal fun messageVariant(
        inserted: Int,
        exportedToHealthConnect: Boolean,
    ): SyncUserMessageVariant {
        return when {
            inserted <= 0 && exportedToHealthConnect -> SyncUserMessageVariant.NO_NEW_HEALTH_CONNECT
            inserted <= 0 -> SyncUserMessageVariant.NO_NEW
            exportedToHealthConnect -> SyncUserMessageVariant.SAVED_NEW_HEALTH_CONNECT
            else -> SyncUserMessageVariant.SAVED_NEW
        }
    }

    internal interface SyncUserMessageStrings {
        fun nearbyNoNew(): String
        fun nearbyNoNewHealthConnect(): String
        fun nearbySavedNew(inserted: Int): String
        fun nearbySavedNewHealthConnect(inserted: Int): String
        fun notificationNoNew(timestampText: String): String
        fun notificationNoNewHealthConnect(timestampText: String): String
        fun notificationSavedNew(timestampText: String, inserted: Int): String
        fun notificationSavedNewHealthConnect(timestampText: String, inserted: Int): String
    }

    private class AndroidSyncUserMessageStrings(
        private val context: Context,
    ) : SyncUserMessageStrings {
        override fun nearbyNoNew(): String {
            return context.getString(R.string.nearby_sync_summary_no_new)
        }

        override fun nearbyNoNewHealthConnect(): String {
            return context.getString(R.string.nearby_sync_summary_no_new_health_connect)
        }

        override fun nearbySavedNew(inserted: Int): String {
            return context.resources.getQuantityString(
                R.plurals.nearby_sync_summary_saved_new,
                inserted,
                inserted,
            )
        }

        override fun nearbySavedNewHealthConnect(inserted: Int): String {
            return context.resources.getQuantityString(
                R.plurals.nearby_sync_summary_saved_new_health_connect,
                inserted,
                inserted,
            )
        }

        override fun notificationNoNew(timestampText: String): String {
            return context.getString(
                R.string.sync_success_notification_body_no_new,
                timestampText,
            )
        }

        override fun notificationNoNewHealthConnect(timestampText: String): String {
            return context.getString(
                R.string.sync_success_notification_body_no_new_health_connect,
                timestampText,
            )
        }

        override fun notificationSavedNew(timestampText: String, inserted: Int): String {
            return context.resources.getQuantityString(
                R.plurals.sync_success_notification_body_saved_new,
                inserted,
                timestampText,
                inserted,
            )
        }

        override fun notificationSavedNewHealthConnect(
            timestampText: String,
            inserted: Int,
        ): String {
            return context.resources.getQuantityString(
                R.plurals.sync_success_notification_body_saved_new_health_connect,
                inserted,
                timestampText,
                inserted,
            )
        }
    }
}
