package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import kotlin.math.roundToInt

enum class TruReadDisplayMode {
    SEPARATE,
    MERGE,
}

object TruReadMeasurementGrouper {
    private const val TRU_READ_SESSION_STAGE_1 = 1
    private const val TRU_READ_SESSION_STAGE_2 = 2
    private const val TRU_READ_SESSION_STAGE_3 = 3
    private const val MAX_TRU_READ_SESSION_SPAN_SECONDS = 5 * 60L

    fun displayMeasurements(
        model: OmronDeviceDefinition,
        measurements: List<Measurement>,
        displayMode: TruReadDisplayMode,
    ): List<Measurement> {
        if (!model.supportsTruReadMerge || displayMode != TruReadDisplayMode.MERGE) {
            return measurements
        }

        val ascendingMeasurements = measurements.sortedBy { it.recordedAt }
        val groupedMeasurements = mutableListOf<Measurement>()
        var index = 0
        while (index < ascendingMeasurements.size) {
            val candidateGroup = ascendingMeasurements.subList(
                index,
                minOf(index + 3, ascendingMeasurements.size),
            )
            if (candidateGroup.size == 3 && isMergeableTruReadGroup(candidateGroup)) {
                groupedMeasurements += mergeTruReadGroup(candidateGroup)
                index += 3
                continue
            }

            groupedMeasurements += ascendingMeasurements[index]
            index += 1
        }

        return groupedMeasurements.sortedByDescending { it.recordedAt }
    }

    internal fun isMergeableTruReadGroup(measurements: List<Measurement>): Boolean {
        if (measurements.size != 3) {
            return false
        }

        val ascendingMeasurements = measurements.sortedBy { it.recordedAt }
        val firstMeasurement = ascendingMeasurements.first()
        val lastMeasurement = ascendingMeasurements.last()
        val stages = ascendingMeasurements.map { it.truReadStage }
        return ascendingMeasurements.all { it.user == firstMeasurement.user } &&
            stages == listOf(
                TRU_READ_SESSION_STAGE_1,
                TRU_READ_SESSION_STAGE_2,
                TRU_READ_SESSION_STAGE_3,
            ) &&
            !lastMeasurement.recordedAt.isAfter(
                firstMeasurement.recordedAt.plusSeconds(MAX_TRU_READ_SESSION_SPAN_SECONDS),
            )
    }

    internal fun mergeTruReadGroup(measurements: List<Measurement>): Measurement {
        require(isMergeableTruReadGroup(measurements)) {
            "Measurements do not represent a mergeable TruRead session."
        }

        val ascendingMeasurements = measurements.sortedBy { it.recordedAt }
        val lastMeasurement = ascendingMeasurements.last()
        return Measurement(
            user = lastMeasurement.user,
            recordedAt = lastMeasurement.recordedAt,
            systolic = ascendingMeasurements.map { it.systolic }.average().roundToInt(),
            diastolic = ascendingMeasurements.map { it.diastolic }.average().roundToInt(),
            pulse = ascendingMeasurements.map { it.pulse }.average().roundToInt(),
            irregularHeartbeat = ascendingMeasurements.any { it.irregularHeartbeat },
            movement = ascendingMeasurements.any { it.movement },
            isTruReadMerged = true,
        )
    }
}
