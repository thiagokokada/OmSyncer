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

    @Test
    fun filterMeasurements_keepsOnlyMeasurementsWithinThirtyDays() {
        val now = LocalDateTime.of(2026, 3, 8, 12, 0)
        val withinThirtyDays = measurement(user = 1, daysAgo = 12, now = now)
        val exactlyThirtyDays = measurement(user = 2, daysAgo = 30, now = now, hour = 12)
        val olderThanThirtyDays = measurement(user = 1, daysAgo = 31, now = now)

        val filtered = TrendChartData.filterMeasurements(
            measurements = listOf(withinThirtyDays, exactlyThirtyDays, olderThanThirtyDays),
            selectedUser = null,
            selectedRange = TrendRange.THIRTY_DAYS,
            now = now,
        )

        assertEquals(listOf(withinThirtyDays, exactlyThirtyDays), filtered)
    }

    @Test
    fun chartBuckets_groupsMeasurementsByDayAndAveragesValues() {
        val now = LocalDateTime.of(2026, 3, 8, 12, 0)
        val measurements = listOf(
            measurement(user = 1, daysAgo = 1, now = now, hour = 8, systolic = 120, diastolic = 80, pulse = 60),
            measurement(user = 1, daysAgo = 1, now = now, hour = 20, systolic = 126, diastolic = 84, pulse = 66),
            measurement(user = 1, daysAgo = 3, now = now, hour = 9, systolic = 118, diastolic = 78, pulse = 62),
        )

        val buckets = TrendChartData.chartBuckets(measurements)

        assertEquals(2, buckets.size)
        assertEquals(now.minusDays(3).toLocalDate(), buckets[0].date)
        assertEquals(118, buckets[0].meanSystolic)
        assertEquals(now.minusDays(1).toLocalDate(), buckets[1].date)
        assertEquals(123, buckets[1].meanSystolic)
        assertEquals(82, buckets[1].meanDiastolic)
        assertEquals(63, buckets[1].meanPulse)
        assertEquals(2, buckets[1].measurements.size)
    }

    private fun measurement(
        user: Int,
        daysAgo: Long,
        now: LocalDateTime = LocalDateTime.of(2026, 3, 8, 12, 0),
        hour: Int = 9,
        systolic: Int = 120,
        diastolic: Int = 80,
        pulse: Int = 64,
    ) = Measurement(
        user = user,
        recordedAt = now.minusDays(daysAgo).withHour(hour),
        systolic = systolic,
        diastolic = diastolic,
        pulse = pulse,
        irregularHeartbeat = false,
        movement = false,
    )
}
