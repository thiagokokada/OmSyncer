package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

object TrendChartData {

    fun userOptions(measurements: List<Measurement>): List<Int?> {
        val distinctUsers = measurements.map { it.user }.distinct().sorted()
        return if (distinctUsers.size <= 1) {
            distinctUsers.map { it as Int? }.ifEmpty { listOf(null) }
        } else {
            listOf(null) + distinctUsers.map { it as Int? }
        }
    }

    fun filterMeasurements(
        measurements: List<Measurement>,
        selectedUser: Int?,
        selectedRange: TrendRange,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<Measurement> {
        return measurements
            .filter { measurement -> selectedUser == null || measurement.user == selectedUser }
            .filter { measurement -> selectedRange.includes(measurement.recordedAt, now) }
    }

    fun chartBuckets(measurements: List<Measurement>): List<TrendBucket> {
        return measurements
            .groupBy { measurement -> measurement.recordedAt.toLocalDate() }
            .toSortedMap()
            .map { (date, values) ->
                TrendBucket(
                    date = date,
                    meanSystolic = values.map { it.systolic }.average().roundToInt(),
                    meanDiastolic = values.map { it.diastolic }.average().roundToInt(),
                    meanPulse = values.map { it.pulse }.average().roundToInt(),
                    measurements = values.sortedBy { it.recordedAt },
                )
            }
    }
}

data class TrendBucket(
    val date: LocalDate,
    val meanSystolic: Int,
    val meanDiastolic: Int,
    val meanPulse: Int,
    val measurements: List<Measurement>,
)

enum class TrendRange {
    SEVEN_DAYS,
    THIRTY_DAYS,
    ALL,
    ;

    fun recordedAtFrom(now: LocalDateTime): LocalDateTime? {
        return when (this) {
            SEVEN_DAYS -> now.minusDays(7)
            THIRTY_DAYS -> now.minusDays(30)
            ALL -> null
        }
    }

    fun includes(recordedAt: LocalDateTime, now: LocalDateTime): Boolean {
        return recordedAtFrom(now)?.let { cutoff -> !recordedAt.isBefore(cutoff) } ?: true
    }
}
