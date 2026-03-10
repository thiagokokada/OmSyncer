package com.github.thiagokokada.omronsyncer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.thiagokokada.omronsyncer.model.Measurement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class MeasurementStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var measurementStore: MeasurementStore

    @Before
    fun setUp() {
        context.deleteDatabase("measurements.db")
        measurementStore = MeasurementStore(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("measurements.db")
    }

    @Test
    fun loadAll_filtersByUserAndKeepsNewestFirst() {
        val olderUserTwo = measurement(user = 2, day = 7, hour = 7)
        val newerUserTwo = measurement(user = 2, day = 8, hour = 9)
        measurementStore.saveAll(
            listOf(
                measurement(user = 1, day = 6),
                olderUserTwo,
                newerUserTwo,
            ),
        )

        val filtered = measurementStore.loadAll(user = 2)

        assertEquals(listOf(newerUserTwo, olderUserTwo), filtered)
    }

    @Test
    fun softDelete_hidesMeasurementUntilUndeleted() {
        val measurement = measurement(user = 1, day = 8, hour = 9)
        measurementStore.saveAll(listOf(measurement))

        measurementStore.softDelete(measurement)

        assertEquals(emptyList<Measurement>(), measurementStore.loadAll(user = 1))
        assertEquals(listOf(measurement), measurementStore.loadDeleted(user = 1))

        measurementStore.undelete(measurement)

        assertEquals(listOf(measurement), measurementStore.loadAll(user = 1))
        assertEquals(emptyList<Measurement>(), measurementStore.loadDeleted(user = 1))
    }

    @Test
    fun saveAll_excludesSoftDeletedMatchesFromVisibleCounts() {
        val deletedMeasurement = measurement(user = 1, day = 8, hour = 9)
        val newMeasurement = measurement(user = 1, day = 9, hour = 9)
        measurementStore.saveAll(listOf(deletedMeasurement))
        measurementStore.softDelete(deletedMeasurement)

        val summary = measurementStore.saveAll(listOf(deletedMeasurement, newMeasurement))

        assertEquals(2, summary.imported)
        assertEquals(1, summary.inserted)
        assertEquals(1, summary.duplicates)
        assertEquals(1, summary.hiddenByDeletedMeasurements)
        assertEquals(1, summary.visibleImported)
        assertEquals(0, summary.visibleDuplicates)
        assertEquals(listOf(newMeasurement), measurementStore.loadAll(user = 1))
        assertEquals(listOf(deletedMeasurement), measurementStore.loadDeleted(user = 1))
    }

    private fun measurement(user: Int, day: Int, hour: Int = 9): Measurement {
        return Measurement(
            user = user,
            recordedAt = LocalDateTime.of(2026, 3, day, hour, 30),
            systolic = 120 + user,
            diastolic = 80 + user,
            pulse = 64 + user,
            irregularHeartbeat = false,
            movement = false,
        )
    }
}
