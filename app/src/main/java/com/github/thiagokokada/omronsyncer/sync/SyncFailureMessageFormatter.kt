package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import com.github.thiagokokada.omronsyncer.R
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient

object SyncFailureMessageFormatter {

    fun userFacingMessage(
        context: Context,
        error: Throwable,
    ): String {
        return userFacingMessage(
            error = error,
            strings = AndroidSyncFailureMessageStrings(context),
        )
    }

    internal fun userFacingMessage(
        error: Throwable,
        strings: SyncFailureMessageStrings,
    ): String {
        knownMessage(error.cause, strings)?.let { return it }
        knownMessage(error, strings)?.let { return it }
        return when (error) {
            is OmronSyncClient.SyncException -> strings.syncFailed()
            is OmronSyncClient.PairingException -> strings.pairingFailed()
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    private fun knownMessage(
        error: Throwable?,
        strings: SyncFailureMessageStrings,
    ): String? {
        return when (error) {
            null -> null
            is MissingBluetoothPermissionException -> strings.missingBluetoothPermission()
            is NoBluetoothAdapterException -> strings.noBluetoothAdapter()
            is NoBondedBluetoothDevicesException -> strings.noBondedDevices()
            is NoSelectedMonitorException -> strings.noSelectedMonitor()
            is SelectedMonitorNotFoundException -> strings.selectedMonitorNotFound()
            is MonitorNotBondedException -> strings.monitorNotBonded()
            is NoMeasurementsForSelectedUserException -> strings.noMeasurementsForSelectedUser()
            is SyncAlreadyInProgressException -> strings.syncAlreadyInProgress()
            else -> knownMessage(error.cause, strings)
        }
    }

    internal interface SyncFailureMessageStrings {
        fun missingBluetoothPermission(): String
        fun noBluetoothAdapter(): String
        fun noBondedDevices(): String
        fun noSelectedMonitor(): String
        fun selectedMonitorNotFound(): String
        fun monitorNotBonded(): String
        fun noMeasurementsForSelectedUser(): String
        fun syncAlreadyInProgress(): String
        fun syncFailed(): String
        fun pairingFailed(): String
    }

    private class AndroidSyncFailureMessageStrings(
        private val context: Context,
    ) : SyncFailureMessageStrings {
        override fun missingBluetoothPermission(): String {
            return context.getString(R.string.status_missing_permission)
        }

        override fun noBluetoothAdapter(): String {
            return context.getString(R.string.status_no_adapter)
        }

        override fun noBondedDevices(): String {
            return context.getString(R.string.status_no_devices)
        }

        override fun noSelectedMonitor(): String {
            return context.getString(R.string.status_select_monitor_first)
        }

        override fun selectedMonitorNotFound(): String {
            return context.getString(R.string.status_selected_device_not_found)
        }

        override fun monitorNotBonded(): String {
            return context.getString(R.string.status_pair_device)
        }

        override fun noMeasurementsForSelectedUser(): String {
            return context.getString(R.string.status_health_connect_no_matching_measurements)
        }

        override fun syncAlreadyInProgress(): String {
            return context.getString(R.string.status_sync_already_in_progress)
        }

        override fun syncFailed(): String {
            return context.getString(R.string.status_sync_failed_generic)
        }

        override fun pairingFailed(): String {
            return context.getString(R.string.status_pairing_failed_generic)
        }
    }
}
