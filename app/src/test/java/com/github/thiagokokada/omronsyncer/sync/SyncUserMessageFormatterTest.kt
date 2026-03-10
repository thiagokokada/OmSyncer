package com.github.thiagokokada.omronsyncer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncUserMessageFormatterTest {

    private val strings = FakeSyncUserMessageStrings()

    @Test
    fun nearbySummary_returnsNoNewMessageWhenNothingWasInserted() {
        val message = SyncUserMessageFormatter.nearbySummary(
            inserted = 0,
            exportedToHealthConnect = false,
            strings = strings,
        )

        assertEquals("nearby-no-new", message)
    }

    @Test
    fun nearbySummary_returnsSavedNewMessageWithInsertedCount() {
        val message = SyncUserMessageFormatter.nearbySummary(
            inserted = 2,
            exportedToHealthConnect = false,
            strings = strings,
        )

        assertEquals("nearby-saved-new:2", message)
    }

    @Test
    fun nearbySummary_returnsHealthConnectMessageWhenExportRanWithoutNewMeasurements() {
        val message = SyncUserMessageFormatter.nearbySummary(
            inserted = 0,
            exportedToHealthConnect = true,
            strings = strings,
        )

        assertEquals("nearby-no-new-health-connect", message)
    }

    @Test
    fun successNotificationBody_returnsSavedNewHealthConnectMessageWithTimestampAndCount() {
        val message = SyncUserMessageFormatter.successNotificationBody(
            timestampText = "2026-03-10 09:55",
            inserted = 3,
            exportedToHealthConnect = true,
            strings = strings,
        )

        assertEquals("notification-saved-new-health-connect:2026-03-10 09:55:3", message)
    }

    @Test
    fun successNotificationBody_returnsNoNewMessageWithTimestamp() {
        val message = SyncUserMessageFormatter.successNotificationBody(
            timestampText = "2026-03-10 09:55",
            inserted = 0,
            exportedToHealthConnect = false,
            strings = strings,
        )

        assertEquals("notification-no-new:2026-03-10 09:55", message)
    }

    private class FakeSyncUserMessageStrings : SyncUserMessageFormatter.SyncUserMessageStrings {
        override fun nearbyNoNew(): String = "nearby-no-new"

        override fun nearbyNoNewHealthConnect(): String = "nearby-no-new-health-connect"

        override fun nearbySavedNew(inserted: Int): String = "nearby-saved-new:$inserted"

        override fun nearbySavedNewHealthConnect(inserted: Int): String {
            return "nearby-saved-new-health-connect:$inserted"
        }

        override fun notificationNoNew(timestampText: String): String {
            return "notification-no-new:$timestampText"
        }

        override fun notificationNoNewHealthConnect(timestampText: String): String {
            return "notification-no-new-health-connect:$timestampText"
        }

        override fun notificationSavedNew(timestampText: String, inserted: Int): String {
            return "notification-saved-new:$timestampText:$inserted"
        }

        override fun notificationSavedNewHealthConnect(
            timestampText: String,
            inserted: Int,
        ): String {
            return "notification-saved-new-health-connect:$timestampText:$inserted"
        }
    }
}
