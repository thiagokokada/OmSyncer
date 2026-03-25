package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.BloodPressureCategoryCount
import com.github.thiagokokada.omronsyncer.BloodPressureClassificationScheme
import com.github.thiagokokada.omronsyncer.TrendRange
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.LocalDate
import java.time.LocalDateTime

data class MeasurementPdfReport(
    val generatedAt: LocalDateTime,
    val range: TrendRange,
    val selectedUser: Int?,
    val classificationScheme: BloodPressureClassificationScheme,
    val measurements: List<Measurement>,
    val summary: MeasurementPdfReportSummary,
    val dailyAverages: List<MeasurementPdfDailyAverage>,
    val pressureDistribution: MeasurementPdfPressureDistribution,
)

data class MeasurementPdfReportSummary(
    val measurementCount: Int,
    val averageSystolic: Int,
    val averageDiastolic: Int,
    val averagePulse: Int,
    val firstRecordedAt: LocalDateTime?,
    val lastRecordedAt: LocalDateTime?,
    val irregularHeartbeatCount: Int,
    val movementCount: Int,
    val latestMeasurement: Measurement?,
    val minimumSystolic: Int,
    val maximumSystolic: Int,
    val minimumDiastolic: Int,
    val maximumDiastolic: Int,
    val minimumPulse: Int,
    val maximumPulse: Int,
)

data class MeasurementPdfDailyAverage(
    val date: LocalDate,
    val meanSystolic: Int,
    val meanDiastolic: Int,
    val meanPulse: Int,
    val measurementCount: Int,
)

data class MeasurementPdfPressureDistribution(
    val scheme: BloodPressureClassificationScheme,
    val categories: List<BloodPressureCategoryCount>,
)
