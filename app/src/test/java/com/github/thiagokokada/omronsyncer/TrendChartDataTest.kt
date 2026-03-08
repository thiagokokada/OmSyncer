package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TrendChartDataTest {

    @Test
    fun userOptions_returnsOnlySingleUserForSingleUserMeasurements() {
        val measurements = listOf(
            measurement(user = 1, daysAgo = 1),
            measurement(user = 1, daysAgo = 2),
        )

        assertEquals(listOf(1), TrendChartData.userOptions(measurements))
    }

    @Test
    fun userOptions_prependsAllUsersOptionForMultiUserMeasurements() {
        val measurements = listOf(
            measurement(user = 2, daysAgo = 1),
            measurement(user = 1, daysAgo = 2),
        )

        assertEquals(listOf(null, 1, 2), TrendChartData.userOptions(measurements))
    }

    @Test
    fun filterMeasurements_filtersByUserAndRange() {
        val now = LocalDateTime.of(2026, 3, 8, 12, 0)
        val measurements = listOf(
            measurement(user = 1, daysAgo = 2, now = now),
            measurement(user = 2, daysAgo = 3, now = now),
            measurement(user = 1, daysAgo = 10, now = now),
        )

        val filtered = TrendChartData.filterMeasurements(
            measurements = measurements,
            selectedUser = 1,
            selectedRange = TrendRange.SEVEN_DAYS,
            now = now,
        )

        assertEquals(listOf(measurements.first()), filtered)
    }

    @Test
    fun filterMeasurements_keepsAllMeasurementsForAllRange() {
        val now = LocalDateTime.of(2026, 3, 8, 12, 0)
        val measurements = listOf(
            measurement(user = 1, daysAgo = 2, now = now),
            measurement(user = 2, daysAgo = 40, now = now),
        )

        val filtered = TrendChartData.filterMeasurements(
            measurements = measurements,
            selectedUser = null,
            selectedRange = TrendRange.ALL,
            now = now,
        )

        assertEquals(measurements, filtered)
    }

    private fun measurement(
        user: Int,
        daysAgo: Long,
        now: LocalDateTime = LocalDateTime.of(2026, 3, 8, 12, 0),
    ) = Measurement(
        user = user,
        recordedAt = now.minusDays(daysAgo),
        systolic = 120,
        diastolic = 80,
        pulse = 64,
        irregularHeartbeat = false,
        movement = false,
    )
}
