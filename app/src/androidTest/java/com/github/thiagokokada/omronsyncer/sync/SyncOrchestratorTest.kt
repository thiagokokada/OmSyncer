package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectExporter
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class SyncOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var measurementStore: MeasurementStore
    private lateinit var syncPreferences: SyncPreferences

    @Before
    fun setUp() {
        context.deleteDatabase("measurements.db")
        context.getSharedPreferences("om_syncer_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        measurementStore = MeasurementStore(context)
        syncPreferences = SyncPreferences(context)
        syncPreferences.setSelectedModelId(OmronDeviceRegistry.defaultModel().id)
        syncPreferences.setSelectedMeasurementUser(1)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("measurements.db")
        context.getSharedPreferences("om_syncer_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_includesDeletedMeasurementsForSelectedUser() = runBlocking {
        val activeUserOne = measurement(user = 1, day = 8, hour = 9)
        val activeUserTwo = measurement(user = 2, day = 8, hour = 10)
        val deletedUserOne = measurement(user = 1, day = 7, hour = 9)
        val deletedUserTwo = measurement(user = 2, day = 7, hour = 10)
        measurementStore.saveAll(listOf(activeUserOne, activeUserTwo, deletedUserOne, deletedUserTwo))
        measurementStore.softDelete(deletedUserOne)
        measurementStore.softDelete(deletedUserTwo)
        val exporter = FakeHealthConnectExporter()
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = exporter,
            syncPreferences = syncPreferences,
        )

        val summary = orchestrator.exportStoredMeasurementsToHealthConnect()

        assertEquals(listOf(activeUserOne), exporter.activeMeasurements)
        assertEquals(listOf(deletedUserOne), exporter.deletedMeasurements)
        assertEquals(1, summary.bloodPressureExported)
        assertEquals(1, summary.deletedMeasurements)
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_allowsDeletedOnlyMeasurements() = runBlocking {
        val deletedUserOne = measurement(user = 1, day = 8, hour = 9)
        measurementStore.saveAll(listOf(deletedUserOne))
        measurementStore.softDelete(deletedUserOne)
        val exporter = FakeHealthConnectExporter()
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = exporter,
            syncPreferences = syncPreferences,
        )

        val summary = orchestrator.exportStoredMeasurementsToHealthConnect()

        assertEquals(emptyList<Measurement>(), exporter.activeMeasurements)
        assertEquals(listOf(deletedUserOne), exporter.deletedMeasurements)
        assertEquals(0, summary.bloodPressureExported)
        assertEquals(1, summary.deletedMeasurements)
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_throwsTypedFailureWhenNoMeasurementsMatch() {
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = FakeHealthConnectExporter(),
            syncPreferences = syncPreferences,
        )

        assertThrows(NoMeasurementsForSelectedUserException::class.java) {
            runBlocking {
                orchestrator.exportStoredMeasurementsToHealthConnect()
            }
        }
    }

    private fun measurement(user: Int, day: Int, hour: Int): Measurement {
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

    private class FakeHealthConnectExporter : HealthConnectExporter {
        var activeMeasurements: List<Measurement> = emptyList()
            private set
        var deletedMeasurements: List<Measurement> = emptyList()
            private set

        override fun sdkStatus(): Int = HealthConnectClient.SDK_AVAILABLE

        override suspend fun hasAllPermissions(): Boolean = true

        override suspend fun sync(
            activeMeasurements: List<Measurement>,
            deletedMeasurements: List<Measurement>,
        ): HealthConnectBloodPressureExporter.ExportSummary {
            this.activeMeasurements = activeMeasurements
            this.deletedMeasurements = deletedMeasurements
            return HealthConnectBloodPressureExporter.ExportSummary(
                bloodPressureExported = activeMeasurements.size,
                heartRateExported = activeMeasurements.size,
                deletedMeasurements = deletedMeasurements.size,
            )
        }
    }
}
