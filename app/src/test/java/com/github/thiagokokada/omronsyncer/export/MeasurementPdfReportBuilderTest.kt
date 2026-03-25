package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.TrendRange
import com.github.thiagokokada.omronsyncer.BloodPressureClassificationScheme
import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class MeasurementPdfReportBuilderTest {

    private val builder = MeasurementPdfReportBuilder()

    @Test
    fun build_summarizesMeasurementsAndGroupsDailyAverages() {
        val generatedAt = LocalDateTime.of(2026, 3, 25, 12, 0)
        val report = builder.build(
            measurements = listOf(
                measurement(recordedAt = LocalDateTime.of(2026, 3, 25, 9, 0), systolic = 120, diastolic = 80, pulse = 60),
                measurement(recordedAt = LocalDateTime.of(2026, 3, 25, 18, 0), systolic = 126, diastolic = 84, pulse = 66, irregularHeartbeat = true),
                measurement(recordedAt = LocalDateTime.of(2026, 3, 24, 8, 0), systolic = 118, diastolic = 78, pulse = 62, movement = true),
            ),
            range = TrendRange.THIRTY_DAYS,
            selectedUser = 1,
            classificationScheme = BloodPressureClassificationScheme.JNC7,
            generatedAt = generatedAt,
        )

        assertEquals(generatedAt, report.generatedAt)
        assertEquals(3, report.summary.measurementCount)
        assertEquals(121, report.summary.averageSystolic)
        assertEquals(81, report.summary.averageDiastolic)
        assertEquals(63, report.summary.averagePulse)
        assertEquals(1, report.summary.irregularHeartbeatCount)
        assertEquals(1, report.summary.movementCount)
        assertEquals(LocalDateTime.of(2026, 3, 24, 8, 0), report.summary.firstRecordedAt)
        assertEquals(LocalDateTime.of(2026, 3, 25, 18, 0), report.summary.lastRecordedAt)
        assertEquals(118, report.summary.minimumSystolic)
        assertEquals(126, report.summary.maximumSystolic)
        assertEquals(2, report.dailyAverages.size)
        assertEquals(LocalDateTime.of(2026, 3, 25, 18, 0), report.measurements.first().recordedAt)
        assertEquals(123, report.dailyAverages.first().meanSystolic)
        assertEquals(2, report.dailyAverages.first().measurementCount)
        assertEquals(BloodPressureClassificationScheme.JNC7, report.classificationScheme)
        assertEquals(4, report.pressureDistribution!!.categories.size)
        assertEquals("Normal", report.pressureDistribution.categories[0].category.shortLabel)
        assertEquals(1, report.pressureDistribution.categories[0].count)
        assertEquals("Prehypertension", report.pressureDistribution.categories[1].category.shortLabel)
        assertEquals(2, report.pressureDistribution.categories[1].count)
    }

    @Test
    fun build_handlesEmptyMeasurements() {
        val report = builder.build(
            measurements = emptyList(),
            range = TrendRange.ALL,
            selectedUser = null,
            classificationScheme = BloodPressureClassificationScheme.ESC_ESH_2018,
            generatedAt = LocalDateTime.of(2026, 3, 25, 12, 0),
        )

        assertEquals(0, report.summary.measurementCount)
        assertEquals(0, report.summary.averageSystolic)
        assertNull(report.summary.firstRecordedAt)
        assertEquals(emptyList<MeasurementPdfDailyAverage>(), report.dailyAverages)
        assertEquals(6, report.pressureDistribution!!.categories.size)
        assertEquals(0, report.pressureDistribution.categories.sumOf { it.count })
    }

    @Test
    fun build_omitsPressureDistributionWhenDisabled() {
        val report = builder.build(
            measurements = listOf(
                measurement(recordedAt = LocalDateTime.of(2026, 3, 25, 9, 0), systolic = 160, diastolic = 100, pulse = 60),
            ),
            range = TrendRange.ALL,
            selectedUser = null,
            classificationScheme = BloodPressureClassificationScheme.DISABLED,
            generatedAt = LocalDateTime.of(2026, 3, 25, 12, 0),
        )

        assertEquals(BloodPressureClassificationScheme.DISABLED, report.classificationScheme)
        assertNull(report.pressureDistribution)
    }

    private fun measurement(
        recordedAt: LocalDateTime,
        systolic: Int,
        diastolic: Int,
        pulse: Int,
        irregularHeartbeat: Boolean = false,
        movement: Boolean = false,
    ) = Measurement(
        user = 1,
        recordedAt = recordedAt,
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        irregularHeartbeat = irregularHeartbeat,
        movement = movement,
    )
}
