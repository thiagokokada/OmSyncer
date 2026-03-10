package com.github.thiagokokada.omronsyncer.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOrchestratorHealthConnectPolicyTest {

    @Test
    fun nearbySyncWithoutNewMeasurements_skipsHealthConnectAutoExport() {
        val shouldSkip = shouldSkipBackgroundHealthConnectExport(
            syncSource = "nearby",
            insertedMeasurementCount = 0,
        )

        assertTrue(shouldSkip)
    }

    @Test
    fun nearbySyncWithNewMeasurements_keepsHealthConnectAutoExportEnabled() {
        val shouldSkip = shouldSkipBackgroundHealthConnectExport(
            syncSource = "nearby",
            insertedMeasurementCount = 1,
        )

        assertFalse(shouldSkip)
    }

    @Test
    fun manualSyncWithoutNewMeasurements_keepsHealthConnectAutoExportEnabled() {
        val shouldSkip = shouldSkipBackgroundHealthConnectExport(
            syncSource = "manual",
            insertedMeasurementCount = 0,
        )

        assertFalse(shouldSkip)
    }
}
