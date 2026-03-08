package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDateTime

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
}

enum class TrendRange {
    SEVEN_DAYS,
    THIRTY_DAYS,
    ALL,
    ;

    fun includes(recordedAt: LocalDateTime, now: LocalDateTime): Boolean {
        return when (this) {
            SEVEN_DAYS -> !recordedAt.isBefore(now.minusDays(7))
            THIRTY_DAYS -> !recordedAt.isBefore(now.minusDays(30))
            ALL -> true
        }
    }
}
