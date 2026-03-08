package com.github.thiagokokada.omronsyncer.sync

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition

class CompanionDeviceSyncManager(private val context: Context) {

    private var observingAssociationId: Int? = null

    fun isAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP) &&
            companionDeviceManager() != null
    }

    fun supportsPresenceObservation(): Boolean {
        return isAvailable() && Build.VERSION.SDK_INT >= 36
    }

    fun associationForSelectedDevice(selectedDeviceAddress: String?): AssociationInfo? {
        if (!isAvailable() || selectedDeviceAddress == null) {
            return null
        }
        return runCatching {
            companionDeviceManager()?.myAssociations.orEmpty().firstOrNull { association ->
                selectedDeviceAddress.equals(
                    association.getDeviceMacAddress()?.toString(),
                    ignoreCase = true,
                )
            }
        }.getOrNull()
    }

    fun requestAssociation(
        activity: Activity,
        model: OmronDeviceDefinition,
        selectedDeviceAddress: String,
        callback: CompanionDeviceManager.Callback,
    ): Boolean {
        val manager = companionDeviceManager() ?: return false
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setDeviceAddress(selectedDeviceAddress)
                    .setServiceUuid(ParcelUuid(model.serviceUuid))
                    .build(),
            )
            .build()
        val request = AssociationRequest.Builder()
            .setSingleDevice(true)
            .addDeviceFilter(filter)
            .build()

        manager.associate(
            request,
            activity.mainExecutor,
            callback,
        )
        return true
    }

    fun updatePresenceObservation(enabled: Boolean, selectedDeviceAddress: String?): Boolean {
        if (!supportsPresenceObservation()) {
            return false
        }

        val manager = companionDeviceManager() ?: return false
        val selectedAssociation = associationForSelectedDevice(selectedDeviceAddress)

        if (!enabled || selectedAssociation == null) {
            stopCurrentObservation(manager)
            return false
        }

        if (observingAssociationId == selectedAssociation.id) {
            return true
        }

        stopCurrentObservation(manager)
        val request = ObservingDevicePresenceRequest.Builder()
            .setAssociationId(selectedAssociation.id)
            .build()
        return runCatching {
            manager.startObservingDevicePresence(request)
            observingAssociationId = selectedAssociation.id
            true
        }.onFailure { error ->
            Log.w(TAG, "Failed to start companion presence observation.", error)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun associationAddress(associationId: Int): String? {
        val manager = companionDeviceManager() ?: return null
        return runCatching {
            manager.myAssociations
                .firstOrNull { it.id == associationId }
                ?.getDeviceMacAddress()
                ?.toString()
        }.getOrNull()
    }

    private fun stopCurrentObservation(manager: CompanionDeviceManager) {
        if (!supportsPresenceObservation()) {
            observingAssociationId = null
            return
        }

        val associationId = observingAssociationId ?: return
        runCatching {
            manager.stopObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(associationId)
                    .build(),
            )
        }.onFailure { error ->
            Log.d(TAG, "Ignoring failure while stopping companion presence observation.", error)
        }
        observingAssociationId = null
    }

    private fun companionDeviceManager(): CompanionDeviceManager? {
        return context.getSystemService(CompanionDeviceManager::class.java)
    }

    companion object {
        private const val TAG = "OmSyncerCompanion"
    }
}
