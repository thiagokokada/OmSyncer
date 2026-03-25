package com.github.thiagokokada.omronsyncer.export

import com.github.thiagokokada.omronsyncer.TrendRange
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
        assertEquals(2, report.dailyAverages.size)
        assertEquals(LocalDateTime.of(2026, 3, 25, 18, 0), report.measurements.first().recordedAt)
        assertEquals(123, report.dailyAverages.first().meanSystolic)
        assertEquals(2, report.dailyAverages.first().measurementCount)
    }

    @Test
    fun build_handlesEmptyMeasurements() {
        val report = builder.build(
            measurements = emptyList(),
            range = TrendRange.ALL,
            selectedUser = null,
            generatedAt = LocalDateTime.of(2026, 3, 25, 12, 0),
        )

        assertEquals(0, report.summary.measurementCount)
        assertEquals(0, report.summary.averageSystolic)
        assertNull(report.summary.firstRecordedAt)
        assertEquals(emptyList<MeasurementPdfDailyAverage>(), report.dailyAverages)
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
