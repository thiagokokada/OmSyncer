package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TruReadMeasurementGrouperTest {

    private val truReadModel = OmronDeviceRegistry.findById("hem_7380t1")
    private val unsupportedModel = OmronDeviceRegistry.findById("hem_7146t")

    @Test
    fun displayMeasurements_mergesTruReadTripletsForSupportedModel() {
        val baseTime = LocalDateTime.of(2026, 3, 10, 12, 37, 31)
        val measurements = listOf(
            measurement(recordedAt = baseTime, systolic = 115, diastolic = 68, pulse = 79, truReadStage = 1),
            measurement(recordedAt = baseTime.plusSeconds(56), systolic = 123, diastolic = 71, pulse = 87, truReadStage = 2),
            measurement(recordedAt = baseTime.plusSeconds(107), systolic = 114, diastolic = 84, pulse = 87, truReadStage = 3),
        ).sortedByDescending { it.recordedAt }

        val grouped = TruReadMeasurementGrouper.displayMeasurements(
            model = truReadModel,
            measurements = measurements,
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(
            listOf(
                Measurement(
                    user = 1,
                    recordedAt = baseTime.plusSeconds(107),
                    systolic = 117,
                    diastolic = 74,
                    pulse = 84,
                    irregularHeartbeat = false,
                    movement = false,
                    isTruReadMerged = true,
                ),
            ),
            grouped,
        )
    }

    @Test
    fun displayMeasurements_keepsSeparateReadingsWhenModeDisabled() {
        val measurements = listOf(
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 37, 31), truReadStage = 1),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 38, 27), truReadStage = 2),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 39, 18), truReadStage = 3),
        ).sortedByDescending { it.recordedAt }

        val grouped = TruReadMeasurementGrouper.displayMeasurements(
            model = truReadModel,
            measurements = measurements,
            displayMode = TruReadDisplayMode.SEPARATE,
        )

        assertEquals(measurements, grouped)
    }

    @Test
    fun displayMeasurements_keepsSeparateReadingsForUnsupportedModel() {
        val measurements = listOf(
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 37, 31), truReadStage = 1),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 38, 27), truReadStage = 2),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 39, 18), truReadStage = 3),
        ).sortedByDescending { it.recordedAt }

        val grouped = TruReadMeasurementGrouper.displayMeasurements(
            model = unsupportedModel,
            measurements = measurements,
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(measurements, grouped)
    }

    @Test
    fun displayMeasurements_doesNotMergeIncompleteTruReadGroups() {
        val measurements = listOf(
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 37, 31), truReadStage = 1),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 38, 27), truReadStage = 2),
            measurement(recordedAt = LocalDateTime.of(2026, 3, 10, 12, 50, 18), truReadStage = 3),
        ).sortedByDescending { it.recordedAt }

        val grouped = TruReadMeasurementGrouper.displayMeasurements(
            model = truReadModel,
            measurements = measurements,
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(measurements, grouped)
    }

    private fun measurement(
        recordedAt: LocalDateTime,
        systolic: Int = 120,
        diastolic: Int = 80,
        pulse: Int = 64,
        truReadStage: Int? = null,
    ) = Measurement(
        user = 1,
        recordedAt = recordedAt,
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        irregularHeartbeat = false,
        movement = false,
        truReadStage = truReadStage,
    )
}
