package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.TrendRange
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDateTime
import kotlin.math.roundToInt

class MeasurementPdfReportBuilder {

    fun build(
        measurements: List<Measurement>,
        range: TrendRange,
        selectedUser: Int?,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): MeasurementPdfReport {
        val sortedMeasurements = measurements.sortedByDescending { it.recordedAt }
        val summary = MeasurementPdfReportSummary(
            measurementCount = sortedMeasurements.size,
            averageSystolic = sortedMeasurements.averageOfOrZero { it.systolic },
            averageDiastolic = sortedMeasurements.averageOfOrZero { it.diastolic },
            averagePulse = sortedMeasurements.averageOfOrZero { it.pulse },
            firstRecordedAt = sortedMeasurements.minOfOrNull { it.recordedAt },
            lastRecordedAt = sortedMeasurements.maxOfOrNull { it.recordedAt },
            irregularHeartbeatCount = sortedMeasurements.count { it.irregularHeartbeat },
            movementCount = sortedMeasurements.count { it.movement },
            latestMeasurement = sortedMeasurements.firstOrNull(),
            minimumSystolic = sortedMeasurements.minValueOrZero { it.systolic },
            maximumSystolic = sortedMeasurements.maxValueOrZero { it.systolic },
            minimumDiastolic = sortedMeasurements.minValueOrZero { it.diastolic },
            maximumDiastolic = sortedMeasurements.maxValueOrZero { it.diastolic },
            minimumPulse = sortedMeasurements.minValueOrZero { it.pulse },
            maximumPulse = sortedMeasurements.maxValueOrZero { it.pulse },
        )
        val dailyAverages = sortedMeasurements
            .groupBy { it.recordedAt.toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (date, values) ->
                MeasurementPdfDailyAverage(
                    date = date,
                    meanSystolic = values.averageOfOrZero { it.systolic },
                    meanDiastolic = values.averageOfOrZero { it.diastolic },
                    meanPulse = values.averageOfOrZero { it.pulse },
                    measurementCount = values.size,
                )
            }
        return MeasurementPdfReport(
            generatedAt = generatedAt,
            range = range,
            selectedUser = selectedUser,
            measurements = sortedMeasurements,
            summary = summary,
            dailyAverages = dailyAverages,
            pressureDistribution = MeasurementPdfPressureDistribution(
                normal = sortedMeasurements.count(::isNormal),
                elevated = sortedMeasurements.count(::isElevated),
                stageOne = sortedMeasurements.count(::isStageOne),
                stageTwo = sortedMeasurements.count(::isStageTwo),
                crisis = sortedMeasurements.count(::isCrisis),
            ),
        )
    }

    private fun List<Measurement>.averageOfOrZero(selector: (Measurement) -> Int): Int {
        if (isEmpty()) {
            return 0
        }
        return map(selector).average().roundToInt()
    }

    private fun List<Measurement>.minValueOrZero(selector: (Measurement) -> Int): Int {
        return minOfOrNull(selector) ?: 0
    }

    private fun List<Measurement>.maxValueOrZero(selector: (Measurement) -> Int): Int {
        return maxOfOrNull(selector) ?: 0
    }

    private fun isNormal(measurement: Measurement): Boolean {
        return measurement.systolic < 120 && measurement.diastolic < 80
    }

    private fun isElevated(measurement: Measurement): Boolean {
        return measurement.systolic in 120..129 && measurement.diastolic < 80
    }

    private fun isStageOne(measurement: Measurement): Boolean {
        return !isCrisis(measurement) &&
            !isStageTwo(measurement) &&
            (measurement.systolic in 130..139 || measurement.diastolic in 80..89)
    }

    private fun isStageTwo(measurement: Measurement): Boolean {
        return !isCrisis(measurement) &&
            (measurement.systolic >= 140 || measurement.diastolic >= 90)
    }

    private fun isCrisis(measurement: Measurement): Boolean {
        return measurement.systolic >= 180 || measurement.diastolic >= 120
    }
}
