package com.github.thiagokokada.omronsyncer.healthconnect

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.deleteRecords
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Pressure
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.ZoneId

class HealthConnectBloodPressureExporter(private val context: Context) : HealthConnectExporter {

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
    )

    override fun sdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    fun manageOrInstallIntent(): Intent {
        return if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getHealthConnectManageDataIntent(context)
        } else {
            Intent(
                Intent.ACTION_VIEW,
                "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri(),
            ).apply {
                setPackage("com.android.vending")
                putExtra("overlay", true)
                putExtra("callerId", context.packageName)
            }
        }
    }

    override suspend fun hasAllPermissions(): Boolean {
        return sdkStatus() == HealthConnectClient.SDK_AVAILABLE &&
            client().permissionController.getGrantedPermissions().containsAll(requiredPermissions)
    }

    override suspend fun sync(plan: HealthConnectSyncPlan): ExportSummary {
        if (plan.activeItems.isNotEmpty()) {
            client().insertRecords(plan.activeItems.flatMap(::toHealthConnectRecords))
        }
        plan.deletedRecordIds.forEach { recordIds ->
            delete(recordIds)
        }
        return ExportSummary(
            bloodPressureExported = plan.activeItems.size,
            heartRateExported = plan.activeItems.size,
            deletedMeasurements = plan.deletedRecordIds.size,
        )
    }

    suspend fun delete(measurement: Measurement) {
        delete(HealthConnectSyncPlanner.rawRecordIdsFor(measurement))
    }

    suspend fun delete(recordIds: HealthConnectRecordIds) {
        client().deleteRecords<BloodPressureRecord>(
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(recordIds.bloodPressureClientRecordId),
        )
        client().deleteRecords<HeartRateRecord>(
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(recordIds.heartRateClientRecordId),
        )
    }

    private fun client(): HealthConnectClient {
        return HealthConnectClient.getOrCreate(context)
    }

    private fun toBloodPressureRecord(item: HealthConnectExportItem): BloodPressureRecord {
        val measurement = item.measurement
        val zonedTime = measurement.recordedAt.atZone(ZoneId.systemDefault())
        return BloodPressureRecord(
            time = zonedTime.toInstant(),
            zoneOffset = zonedTime.offset,
            metadata = Metadata.autoRecorded(
                device = Device(
                    type = Device.TYPE_UNKNOWN,
                    manufacturer = DEVICE_MANUFACTURER,
                    model = DEVICE_MODEL,
                ),
                clientRecordId = item.recordIds.bloodPressureClientRecordId,
                clientRecordVersion = 0,
            ),
            systolic = Pressure.millimetersOfMercury(measurement.systolic.toDouble()),
            diastolic = Pressure.millimetersOfMercury(measurement.diastolic.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN,
        )
    }

    private fun toHeartRateRecord(item: HealthConnectExportItem): HeartRateRecord {
        val measurement = item.measurement
        val zonedTime = measurement.recordedAt.atZone(ZoneId.systemDefault())
        return HeartRateRecord(
            startTime = zonedTime.toInstant(),
            startZoneOffset = zonedTime.offset,
            endTime = zonedTime.toInstant(),
            endZoneOffset = zonedTime.offset,
            samples = listOf(
                HeartRateRecord.Sample(
                    time = zonedTime.toInstant(),
                    beatsPerMinute = measurement.pulse.toLong(),
                ),
            ),
            metadata = Metadata.autoRecorded(
                device = Device(
                    type = Device.TYPE_UNKNOWN,
                    manufacturer = DEVICE_MANUFACTURER,
                    model = DEVICE_MODEL,
                ),
                clientRecordId = item.recordIds.heartRateClientRecordId,
                clientRecordVersion = 0,
            ),
        )
    }

    private fun toHealthConnectRecords(item: HealthConnectExportItem): List<Record> {
        return listOf(
            toBloodPressureRecord(item),
            toHeartRateRecord(item),
        )
    }

    data class ExportSummary(
        val bloodPressureExported: Int,
        val heartRateExported: Int,
        val deletedMeasurements: Int,
        val diagnostics: String = "",
    )

    private companion object {
        const val DEVICE_MANUFACTURER = "Omron"
        const val DEVICE_MODEL = "Blood Pressure Monitor"
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
