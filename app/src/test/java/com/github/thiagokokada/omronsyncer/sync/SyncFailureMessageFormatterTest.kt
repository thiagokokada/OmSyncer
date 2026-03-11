package com.github.thiagokokada.omronsyncer.sync

import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import com.github.thiagokokada.omronsyncer.omron.SyncCapture
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureMessageFormatterTest {

    @Test
    fun userFacingMessage_mapsTypedSyncFailures() {
        val strings = FakeStrings()

        assertEquals(
            "missing permission",
            SyncFailureMessageFormatter.userFacingMessage(MissingBluetoothPermissionException(), strings),
        )
        assertEquals(
            "no adapter",
            SyncFailureMessageFormatter.userFacingMessage(NoBluetoothAdapterException(), strings),
        )
        assertEquals(
            "no bonded devices",
            SyncFailureMessageFormatter.userFacingMessage(NoBondedBluetoothDevicesException(), strings),
        )
        assertEquals(
            "select monitor",
            SyncFailureMessageFormatter.userFacingMessage(NoSelectedMonitorException(), strings),
        )
        assertEquals(
            "selected monitor not found",
            SyncFailureMessageFormatter.userFacingMessage(SelectedMonitorNotFoundException(), strings),
        )
        assertEquals(
            "monitor not bonded",
            SyncFailureMessageFormatter.userFacingMessage(MonitorNotBondedException(), strings),
        )
        assertEquals(
            "no measurements for user",
            SyncFailureMessageFormatter.userFacingMessage(NoMeasurementsForSelectedUserException(), strings),
        )
        assertEquals(
            "sync already in progress",
            SyncFailureMessageFormatter.userFacingMessage(SyncAlreadyInProgressException("manual"), strings),
        )
        assertEquals(
            "sync failed",
            SyncFailureMessageFormatter.userFacingMessage(syncException(), strings),
        )
        assertEquals(
            "pairing failed",
            SyncFailureMessageFormatter.userFacingMessage(pairingException(), strings),
        )
        assertEquals(
            "missing permission",
            SyncFailureMessageFormatter.userFacingMessage(
                syncException(cause = MissingBluetoothPermissionException()),
                strings,
            ),
        )
    }

    @Test
    fun userFacingMessage_fallsBackToThrowableMessage() {
        val strings = FakeStrings()

        assertEquals(
            "boom",
            SyncFailureMessageFormatter.userFacingMessage(IllegalStateException("boom"), strings),
        )
    }

    private class FakeStrings : SyncFailureMessageFormatter.SyncFailureMessageStrings {
        override fun missingBluetoothPermission(): String = "missing permission"

        override fun noBluetoothAdapter(): String = "no adapter"

        override fun noBondedDevices(): String = "no bonded devices"

        override fun noSelectedMonitor(): String = "select monitor"

        override fun selectedMonitorNotFound(): String = "selected monitor not found"

        override fun monitorNotBonded(): String = "monitor not bonded"

        override fun noMeasurementsForSelectedUser(): String = "no measurements for user"

        override fun syncAlreadyInProgress(): String = "sync already in progress"

        override fun syncFailed(): String = "sync failed"

        override fun pairingFailed(): String = "pairing failed"
    }

    private fun syncException(cause: Throwable? = null): OmronSyncClient.SyncException {
        return OmronSyncClient.SyncException(
            diagnostics = OmronSyncClient.SyncDiagnostics(emptyList()),
            capture = emptyCapture(),
            cause = cause,
        )
    }

    private fun pairingException(cause: Throwable? = null): OmronSyncClient.PairingException {
        return OmronSyncClient.PairingException(
            diagnostics = OmronSyncClient.SyncDiagnostics(emptyList()),
            capture = emptyCapture(),
            cause = cause,
        )
    }

    private fun emptyCapture(): SyncCapture {
        return SyncCapture(
            modelId = "test",
            modelCode = "TEST",
            deviceName = null,
            deviceAddress = null,
            packets = emptyList(),
            records = emptyList(),
        )
    }
}
