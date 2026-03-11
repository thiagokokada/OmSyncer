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
import java.time.format.DateTimeFormatter

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

    suspend fun export(measurements: List<Measurement>): ExportSummary {
        return sync(
            activeMeasurements = measurements,
            deletedMeasurements = emptyList(),
        )
    }

    override suspend fun sync(
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
    ): ExportSummary {
        if (activeMeasurements.isNotEmpty()) {
            client().insertRecords(activeMeasurements.flatMap(::toHealthConnectRecords))
        }
        deletedMeasurements.forEach { measurement ->
            delete(measurement)
        }
        return ExportSummary(
            bloodPressureExported = activeMeasurements.size,
            heartRateExported = activeMeasurements.size,
            deletedMeasurements = deletedMeasurements.size,
        )
    }

    suspend fun delete(measurement: Measurement) {
        client().deleteRecords<BloodPressureRecord>(
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(clientRecordId(measurement)),
        )
        client().deleteRecords<HeartRateRecord>(
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(heartRateClientRecordId(measurement)),
        )
    }

    private fun client(): HealthConnectClient {
        return HealthConnectClient.getOrCreate(context)
    }

    private fun toBloodPressureRecord(measurement: Measurement): BloodPressureRecord {
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
                clientRecordId = clientRecordId(measurement),
                clientRecordVersion = 0,
            ),
            systolic = Pressure.millimetersOfMercury(measurement.systolic.toDouble()),
            diastolic = Pressure.millimetersOfMercury(measurement.diastolic.toDouble()),
            bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN,
        )
    }

    private fun toHeartRateRecord(measurement: Measurement): HeartRateRecord {
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
                clientRecordId = heartRateClientRecordId(measurement),
                clientRecordVersion = 0,
            ),
        )
    }

    private fun toHealthConnectRecords(measurement: Measurement): List<Record> {
        return listOf(
            toBloodPressureRecord(measurement),
            toHeartRateRecord(measurement),
        )
    }

    private fun clientRecordId(measurement: Measurement): String {
        return buildString {
            append("omron-bp:")
            append(baseMeasurementKey(measurement))
        }
    }

    private fun heartRateClientRecordId(measurement: Measurement): String {
        return buildString {
            append("omron-hr:")
            append(baseMeasurementKey(measurement))
        }
    }

    private fun baseMeasurementKey(measurement: Measurement): String {
        return buildString {
            append(measurement.user)
            append(':')
            append(CLIENT_TIME_FORMATTER.format(measurement.recordedAt))
            append(':')
            append(measurement.systolic)
            append(':')
            append(measurement.diastolic)
            append(':')
            append(measurement.pulse)
            append(':')
            append(if (measurement.irregularHeartbeat) 1 else 0)
            append(':')
            append(if (measurement.movement) 1 else 0)
        }
    }

    data class ExportSummary(
        val bloodPressureExported: Int,
        val heartRateExported: Int,
        val deletedMeasurements: Int,
    )

    private companion object {
        const val DEVICE_MANUFACTURER = "Omron"
        const val DEVICE_MODEL = "Blood Pressure Monitor"
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        val CLIENT_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
