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
