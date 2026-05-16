package com.github.thiagokokada.omronsyncer.sync

import androidx.health.connect.client.HealthConnectClient
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.github.thiagokokada.omronsyncer.TruReadMeasurementGrouper
import com.github.thiagokokada.omronsyncer.TruReadDisplayMode
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectSyncPlan
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectSyncPlanner
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import com.github.thiagokokada.omronsyncer.omron.SyncCapture
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncOrchestrator(
    private val context: Context,
    private val syncClient: OmronSyncClient = OmronSyncClient(context),
    private val measurementStore: MeasurementStore = MeasurementStore(context),
    private val healthConnectExporter: HealthConnectExporter =
        HealthConnectBloodPressureExporter(context),
    private val syncPreferences: SyncPreferences = SyncPreferences(context),
    private val syncRunCoordinator: SyncRunCoordinator = SyncRunCoordinator(context),
) {

    suspend fun syncSelectedDevice(
        syncSource: String = "manual",
    ): SyncExecutionResult {
        val lease = syncRunCoordinator.acquireOrThrow(syncSource)
        return try {
            requireBluetoothConnectPermission()
            val device = requireSelectedBondedDevice()
            val model = syncPreferences.selectedModel()
            val selectedUser = resolveSelectedMeasurementUser(model)
            val preSyncMeasurements = withContext(Dispatchers.IO) {
                measurementStore.loadAll()
            }
            val syncResult = syncClient.sync(
                device = device,
                model = model,
                syncTimeoutMillis = syncPreferences.syncTimeoutMillis(),
            )
            val saveSummary = withContext(Dispatchers.IO) {
                measurementStore.saveAll(syncResult.measurements)
            }
            val insertedDisplayCount = TruReadMeasurementGrouper.displayCount(
                model = model,
                measurements = saveSummary.insertedMeasurements,
                displayMode = healthConnectDisplayMode(model),
            )
            syncRunCoordinator.markSuccessfulCompletion()
            val persistedAllMeasurements = withContext(Dispatchers.IO) {
                measurementStore.loadAll()
            }
            val persistedMeasurements = withContext(Dispatchers.IO) {
                measurementStore.loadAll(selectedUser)
            }
            val healthConnectSummary = maybeExportToHealthConnect(
                syncSource = syncSource,
                saveSummary.insertedMeasurements,
                model,
            )
            val orchestrationDiagnostics = listOf(
                "Sync source: $syncSource",
                "Selected measurement user: ${selectedUser ?: "all"}",
                measurementSnapshot("Stored measurements before sync", preSyncMeasurements),
                measurementSnapshot("Imported measurements from device", syncResult.measurements),
                measurementSnapshot("Inserted measurements", saveSummary.insertedMeasurements),
                measurementSnapshot("Stored measurements after sync", persistedAllMeasurements),
            )

            SyncExecutionResult(
                persistedMeasurements = persistedMeasurements,
                inserted = saveSummary.inserted,
                insertedDisplayCount = insertedDisplayCount,
                syncLog = (syncResult.diagnostics.entries + orchestrationDiagnostics)
                    .joinToString(separator = "\n"),
                syncCapture = syncResult.capture,
                healthConnectExportSummary = healthConnectSummary,
            )
        } finally {
            syncRunCoordinator.release(lease)
        }
    }

    suspend fun exportStoredMeasurementsToHealthConnect(): HealthConnectBloodPressureExporter.ExportSummary {
        val model = syncPreferences.selectedModel()
        val selectedUser = resolveSelectedMeasurementUser(model)
        val measurementState = withContext(Dispatchers.IO) {
            measurementStore.loadAll(selectedUser) to measurementStore.loadDeleted(selectedUser)
        }
        if (measurementState.first.isEmpty() && measurementState.second.isEmpty()) {
            throw NoMeasurementsForSelectedUserException()
        }
        val plan = healthConnectPlan(
            model = model,
            activeMeasurements = measurementState.first,
            deletedMeasurements = measurementState.second,
        )
        val summary = healthConnectExporter.sync(plan)
        return summary.copy(
            diagnostics = healthConnectDiagnostics(
                trigger = "manual",
                model = model,
                plan = plan,
                activeMeasurements = measurementState.first,
                deletedMeasurements = measurementState.second,
            ),
        )
    }

    private fun healthConnectPlan(
        model: OmronDeviceDefinition,
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
    ) = HealthConnectSyncPlanner.plan(
        model = model,
        activeMeasurements = activeMeasurements,
        deletedMeasurements = deletedMeasurements,
        displayMode = healthConnectDisplayMode(model),
    )

    private fun healthConnectDisplayMode(model: OmronDeviceDefinition): TruReadDisplayMode {
        return if (model.supportsTruReadMerge) {
            syncPreferences.truReadDisplayMode()
        } else {
            TruReadDisplayMode.SEPARATE
        }
    }

    private suspend fun exportToHealthConnect(
        model: OmronDeviceDefinition,
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
    ): HealthConnectBloodPressureExporter.ExportSummary {
        val plan = healthConnectPlan(
            model = model,
            activeMeasurements = activeMeasurements,
            deletedMeasurements = deletedMeasurements,
        )
        val summary = healthConnectExporter.sync(plan)
        return summary.copy(
            diagnostics = healthConnectDiagnostics(
                trigger = "auto",
                model = model,
                plan = plan,
                activeMeasurements = activeMeasurements,
                deletedMeasurements = deletedMeasurements,
            ),
        )
    }

    private suspend fun maybeExportToHealthConnect(
        syncSource: String,
        measurements: List<Measurement>,
        model: OmronDeviceDefinition,
    ): HealthConnectBloodPressureExporter.ExportSummary? {
        if (!syncPreferences.healthConnectAutoExportEnabled()) {
            return null
        }
        if (healthConnectExporter.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
            return null
        }
        if (!healthConnectExporter.hasAllPermissions()) {
            return null
        }
        if (shouldSkipBackgroundHealthConnectExport(syncSource, measurements.size)) {
            return null
        }

        val selectedUser = resolveSelectedMeasurementUser(model)
        val activeMeasurements = withContext(Dispatchers.IO) {
            measurementStore.loadAll(selectedUser)
        }
        val deletedMeasurements = withContext(Dispatchers.IO) {
            measurementStore.loadDeleted(selectedUser)
        }
        if (activeMeasurements.isEmpty() && deletedMeasurements.isEmpty()) {
            return null
        }

        return exportToHealthConnect(
            model = model,
            activeMeasurements = activeMeasurements,
            deletedMeasurements = deletedMeasurements,
        )
    }

    private fun requireSelectedBondedDevice(): BluetoothDevice {
        val adapter = bluetoothAdapter()
            ?: throw NoBluetoothAdapterException()
        val selectedAddress = syncPreferences.selectedDeviceAddress()
        val bondedDevices = adapter.bondedDevices
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }

        if (bondedDevices.isEmpty()) {
            throw NoBondedBluetoothDevicesException()
        }
        if (selectedAddress == null) {
            throw NoSelectedMonitorException()
        }

        val device = bondedDevices.firstOrNull { it.address == selectedAddress }
            ?: throw SelectedMonitorNotFoundException()

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw MonitorNotBondedException()
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

    private fun measurementSnapshot(label: String, measurements: List<Measurement>): String {
        return buildString {
            append(label)
            append(": total=")
            append(measurements.size)
            append(" byUser=")
            append(formatCountsByUser(measurements))
            append(" latest=")
            append(formatLatestByUser(measurements))
        }
    }

    private fun formatCountsByUser(measurements: List<Measurement>): String {
        if (measurements.isEmpty()) {
            return "none"
        }
        return measurements.groupingBy { it.user }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(separator = ",") { (user, count) -> "u$user=$count" }
    }

    private fun formatLatestByUser(measurements: List<Measurement>): String {
        if (measurements.isEmpty()) {
            return "none"
        }
        return measurements.groupBy { it.user }
            .toSortedMap()
            .entries
            .joinToString(separator = ",") { (user, items) ->
                "u$user=${items.maxOf { it.recordedAt }}"
            }
    }

    private fun healthConnectDiagnostics(
        trigger: String,
        model: OmronDeviceDefinition,
        plan: HealthConnectSyncPlan,
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
    ): String {
        return buildString {
            appendLine("Health Connect trigger: $trigger")
            appendLine("Health Connect model: ${model.id} (${model.modelCode})")
            appendLine("Health Connect TruRead mode: ${healthConnectDisplayMode(model)}")
            appendLine(measurementSnapshot("Health Connect active raw measurements", activeMeasurements))
            appendLine(measurementSnapshot("Health Connect deleted raw measurements", deletedMeasurements))
            appendLine("Health Connect export items: ${plan.activeItems.size}")
            plan.activeItems.forEachIndexed { index, item ->
                appendLine(
                    "  active[$index] bpId=${item.recordIds.bloodPressureClientRecordId} " +
                        "hrId=${item.recordIds.heartRateClientRecordId} " +
                        "measurement=${formatMeasurement(item.measurement)}",
                )
            }
            appendLine("Health Connect delete IDs: ${plan.deletedRecordIds.size}")
            plan.deletedRecordIds.forEachIndexed { index, ids ->
                appendLine(
                    "  delete[$index] bpId=${ids.bloodPressureClientRecordId} " +
                        "hrId=${ids.heartRateClientRecordId}",
                )
            }
        }.trimEnd()
    }

    private fun formatMeasurement(measurement: Measurement): String {
        return buildString {
            append("u")
            append(measurement.user)
            append(' ')
            append(MEASUREMENT_TIME_FORMATTER.format(measurement.recordedAt))
            append(' ')
            append(measurement.systolic)
            append('/')
            append(measurement.diastolic)
            append(" pulse=")
            append(measurement.pulse)
            append(" ihb=")
            append(if (measurement.irregularHeartbeat) 1 else 0)
            append(" mov=")
            append(if (measurement.movement) 1 else 0)
            append(" stage=")
            append(measurement.truReadStage ?: "-")
            append(" merged=")
            append(measurement.isTruReadMerged)
        }
    }
}

data class SyncExecutionResult(
    val persistedMeasurements: List<Measurement>,
    val inserted: Int,
    val insertedDisplayCount: Int,
    val syncLog: String,
    val syncCapture: SyncCapture,
    val healthConnectExportSummary: HealthConnectBloodPressureExporter.ExportSummary?,
)

internal fun shouldSkipBackgroundHealthConnectExport(
    syncSource: String,
    insertedMeasurementCount: Int,
): Boolean {
    return syncSource == "nearby" && insertedMeasurementCount <= 0
}

private val MEASUREMENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
