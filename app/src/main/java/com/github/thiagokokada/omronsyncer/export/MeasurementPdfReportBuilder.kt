package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.BloodPressureClassificationScheme
import com.github.thiagokokada.omronsyncer.BloodPressureClassifier
import com.github.thiagokokada.omronsyncer.TrendRange
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDateTime
import kotlin.math.roundToInt

class MeasurementPdfReportBuilder {

    fun build(
        measurements: List<Measurement>,
        range: TrendRange,
        selectedUser: Int?,
        classificationScheme: BloodPressureClassificationScheme,
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
            classificationScheme = classificationScheme,
            measurements = sortedMeasurements,
            summary = summary,
            dailyAverages = dailyAverages,
            pressureDistribution = MeasurementPdfPressureDistribution(
                scheme = classificationScheme,
                categories = BloodPressureClassifier.categoryCounts(
                    measurements = sortedMeasurements,
                    scheme = classificationScheme,
                ),
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
}
