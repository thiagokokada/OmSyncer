package com.github.thiagokokada.omronsyncer.healthconnect

import com.github.thiagokokada.omronsyncer.MeasurementListItem
import com.github.thiagokokada.omronsyncer.TruReadDisplayMode
import com.github.thiagokokada.omronsyncer.TruReadMeasurementGrouper
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import java.time.format.DateTimeFormatter

data class HealthConnectRecordIds(
    val bloodPressureClientRecordId: String,
    val heartRateClientRecordId: String,
)

data class HealthConnectExportItem(
    val measurement: Measurement,
    val recordIds: HealthConnectRecordIds,
)

data class HealthConnectSyncPlan(
    val activeItems: List<HealthConnectExportItem>,
    val deletedRecordIds: List<HealthConnectRecordIds>,
)

object HealthConnectSyncPlanner {
    fun plan(
        model: OmronDeviceDefinition,
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): HealthConnectSyncPlan {
        val activeItems = exportItems(
            model = model,
            measurements = activeMeasurements,
            displayMode = displayMode,
        )
        val deletedRecordIds = buildList {
            addAll(currentRepresentationDeleteIds(model, deletedMeasurements, displayMode))
            addAll(oppositeRepresentationDeleteIds(model, activeMeasurements, displayMode))
            addAll(oppositeRepresentationDeleteIds(model, deletedMeasurements, displayMode))
        }.distinct()

        return HealthConnectSyncPlan(
            activeItems = activeItems,
            deletedRecordIds = deletedRecordIds,
        )
    }

    internal fun exportItems(
        model: OmronDeviceDefinition,
        measurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): List<HealthConnectExportItem> {
        return displayItems(model, measurements, displayMode).map { item ->
            HealthConnectExportItem(
                measurement = item.displayMeasurement,
                recordIds = recordIdsFor(item),
            )
        }
    }

    internal fun currentRepresentationDeleteIds(
        model: OmronDeviceDefinition,
        measurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): List<HealthConnectRecordIds> {
        return displayItems(model, measurements, displayMode).map(::recordIdsFor)
    }

    internal fun oppositeRepresentationDeleteIds(
        model: OmronDeviceDefinition,
        measurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): List<HealthConnectRecordIds> {
        if (!model.supportsTruReadMerge) {
            return emptyList()
        }

        val mergedItems = TruReadMeasurementGrouper.displayItems(
            model = model,
            measurements = measurements,
            displayMode = TruReadDisplayMode.MERGE,
        ).filter { it.displayMeasurement.isTruReadMerged }

        return when (displayMode) {
            TruReadDisplayMode.MERGE -> mergedItems.flatMap { item ->
                item.sourceMeasurements.map(::rawRecordIdsFor)
            }

            TruReadDisplayMode.SEPARATE -> mergedItems.map(::mergedRecordIdsFor)
        }
    }

    internal fun recordIdsFor(item: MeasurementListItem): HealthConnectRecordIds {
        return if (item.displayMeasurement.isTruReadMerged) {
            mergedRecordIdsFor(item)
        } else {
            rawRecordIdsFor(item.displayMeasurement)
        }
    }

    internal fun rawRecordIdsFor(measurement: Measurement): HealthConnectRecordIds {
        val key = buildString {
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
        return HealthConnectRecordIds(
            bloodPressureClientRecordId = "omron-bp:$key",
            heartRateClientRecordId = "omron-hr:$key",
        )
    }

    internal fun mergedRecordIdsFor(item: MeasurementListItem): HealthConnectRecordIds {
        require(item.displayMeasurement.isTruReadMerged) {
            "Merged Health Connect record IDs require a TruRead merged display measurement."
        }
        val sourceMeasurements = item.sourceMeasurements.sortedBy { it.recordedAt }
        val key = buildString {
            append("merged:")
            append(sourceMeasurements.first().user)
            sourceMeasurements.forEach { measurement ->
                append(':')
                append(CLIENT_TIME_FORMATTER.format(measurement.recordedAt))
            }
        }
        return HealthConnectRecordIds(
            bloodPressureClientRecordId = "omron-bp:$key",
            heartRateClientRecordId = "omron-hr:$key",
        )
    }

    private fun displayItems(
        model: OmronDeviceDefinition,
        measurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): List<MeasurementListItem> {
        return TruReadMeasurementGrouper.displayItems(
            model = model,
            measurements = measurements,
            displayMode = displayMode,
        )
    }

    private val CLIENT_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
}
