package com.github.thiagokokada.omronsyncer.sync

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncOrchestrator(
    private val context: Context,
    private val syncClient: OmronSyncClient = OmronSyncClient(context),
    private val measurementStore: MeasurementStore = MeasurementStore(context),
    private val healthConnectExporter: HealthConnectBloodPressureExporter =
        HealthConnectBloodPressureExporter(context),
    private val syncPreferences: SyncPreferences = SyncPreferences(context),
) {

    suspend fun syncSelectedDevice(): SyncExecutionResult {
        requireBluetoothConnectPermission()
        val device = requireSelectedBondedDevice()
        val model = syncPreferences.selectedModel()
        val syncResult = syncClient.sync(device, model)
        val saveSummary = withContext(Dispatchers.IO) {
            measurementStore.saveAll(syncResult.measurements)
        }
        val persistedMeasurements = withContext(Dispatchers.IO) {
            measurementStore.loadAll(resolveSelectedMeasurementUser(model))
        }
        val healthConnectSummary = maybeExportToHealthConnect(
            saveSummary.insertedMeasurements,
            model,
        )

        return SyncExecutionResult(
            persistedMeasurements = persistedMeasurements,
            imported = saveSummary.imported,
            inserted = saveSummary.inserted,
            duplicates = saveSummary.duplicates,
            syncLog = syncResult.diagnostics.asText(),
            healthConnectExportSummary = healthConnectSummary,
        )
    }

    suspend fun exportStoredMeasurementsToHealthConnect(): HealthConnectBloodPressureExporter.ExportSummary {
        val model = syncPreferences.selectedModel()
        val selectedUser = resolveSelectedMeasurementUser(model)
        val measurementState = withContext(Dispatchers.IO) {
            measurementStore.loadAll(selectedUser) to measurementStore.loadDeleted(selectedUser)
        }
        require(measurementState.first.isNotEmpty() || measurementState.second.isNotEmpty()) {
            "No measurements match the selected user."
        }
        return healthConnectExporter.sync(
            activeMeasurements = measurementState.first,
            deletedMeasurements = measurementState.second,
        )
    }

    private suspend fun maybeExportToHealthConnect(
        measurements: List<Measurement>,
        model: OmronDeviceDefinition,
    ): HealthConnectBloodPressureExporter.ExportSummary? {
        if (!syncPreferences.healthConnectAutoExportEnabled()) {
            return null
        }
        if (healthConnectExporter.sdkStatus() != androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        if (!healthConnectExporter.hasAllPermissions()) {
            return null
        }

        val selectedUser = resolveSelectedMeasurementUser(model)
        val filteredMeasurements = filterMeasurementsForHealthConnect(measurements, model)
        val deletedMeasurements = withContext(Dispatchers.IO) {
            measurementStore.loadDeleted(selectedUser)
        }
        if (filteredMeasurements.isEmpty() && deletedMeasurements.isEmpty()) {
            return null
        }

        return healthConnectExporter.sync(
            activeMeasurements = filteredMeasurements,
            deletedMeasurements = deletedMeasurements,
        )
    }

    private fun requireSelectedBondedDevice(): BluetoothDevice {
        val adapter = bluetoothAdapter()
            ?: error("This device does not have a Bluetooth adapter.")
        val selectedAddress = syncPreferences.selectedDeviceAddress()
        val bondedDevices = adapter.bondedDevices
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }

        val device = bondedDevices.firstOrNull { it.address == selectedAddress }
            ?: bondedDevices.firstOrNull()
            ?: error("No bonded Bluetooth devices found.")

        require(device.bondState == BluetoothDevice.BOND_BONDED) {
            "This monitor is not bonded yet. Pair it in Android Bluetooth settings first."
        }
        return device
    }

    private fun requireBluetoothConnectPermission() {
        if (!context.hasBluetoothConnectPermission()) {
            throw MissingBluetoothPermissionException()
        }
    }

    private fun filterMeasurementsForHealthConnect(
        sourceMeasurements: List<Measurement>,
        model: OmronDeviceDefinition,
    ): List<Measurement> {
        val exportUser = resolveSelectedMeasurementUser(model)
        return if (exportUser == null) {
            sourceMeasurements
        } else {
            sourceMeasurements.filter { it.user == exportUser }
        }
    }

    private fun resolveSelectedMeasurementUser(model: OmronDeviceDefinition): Int? {
        val users = model.userLayouts.map { it.user }
        return when {
            users.isEmpty() -> null
            users.size == 1 -> users.first()
            else -> syncPreferences.selectedMeasurementUser().takeIf { it in users }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }
}

data class SyncExecutionResult(
    val persistedMeasurements: List<Measurement>,
    val imported: Int,
    val inserted: Int,
    val duplicates: Int,
    val syncLog: String,
    val healthConnectExportSummary: HealthConnectBloodPressureExporter.ExportSummary?,
)
