package com.github.thiagokokada.omronsyncer.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncWorkerNotificationsTest {

    @Test
    fun shouldShowSuccessfulSyncNotification_returnsFalseWhenNothingWasInserted() {
        assertFalse(shouldShowSuccessfulSyncNotification(insertedMeasurementCount = 0))
    }

    @Test
    fun shouldShowSuccessfulSyncNotification_returnsTrueWhenNewMeasurementsWereInserted() {
        assertTrue(shouldShowSuccessfulSyncNotification(insertedMeasurementCount = 1))
    }
}
