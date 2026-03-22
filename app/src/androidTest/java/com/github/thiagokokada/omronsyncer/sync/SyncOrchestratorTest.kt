package com.github.thiagokokada.omronsyncer.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.thiagokokada.omronsyncer.TruReadDisplayMode
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectSyncPlan
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

        assertEquals(listOf(activeUserOne), exporter.plan.activeItems.map { it.measurement })
        assertEquals(listOf("omron-bp:1:2026-03-07T09:30:00:121:81:65:0:0"), exporter.plan.deletedRecordIds.map { it.bloodPressureClientRecordId })
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

        assertEquals(emptyList<Measurement>(), exporter.plan.activeItems.map { it.measurement })
        assertEquals(listOf("omron-bp:1:2026-03-08T09:30:00:121:81:65:0:0"), exporter.plan.deletedRecordIds.map { it.bloodPressureClientRecordId })
        assertEquals(0, summary.bloodPressureExported)
        assertEquals(1, summary.deletedMeasurements)
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_mergeModeExportsSingleTruReadSessionAndDeletesRawIds() = runBlocking {
        syncPreferences.setSelectedModelId("hem_7380t1")
        syncPreferences.setTruReadDisplayMode(TruReadDisplayMode.MERGE)
        val session = truReadSession()
        measurementStore.saveAll(session)
        val exporter = FakeHealthConnectExporter()
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = exporter,
            syncPreferences = syncPreferences,
        )

        val summary = orchestrator.exportStoredMeasurementsToHealthConnect()

        assertEquals(1, exporter.plan.activeItems.size)
        assertEquals(
            "omron-bp:merged:1:2026-03-08T09:30:00:2026-03-08T09:32:00:2026-03-08T09:34:00",
            exporter.plan.activeItems.single().recordIds.bloodPressureClientRecordId,
        )
        assertEquals(
            listOf(
                "omron-bp:1:2026-03-08T09:30:00:126:79:63:0:0",
                "omron-bp:1:2026-03-08T09:32:00:124:78:64:0:1",
                "omron-bp:1:2026-03-08T09:34:00:125:80:65:1:0",
            ),
            exporter.plan.deletedRecordIds.map { it.bloodPressureClientRecordId }.sorted(),
        )
        assertEquals(1, summary.bloodPressureExported)
        assertEquals(3, summary.deletedMeasurements)
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_separateModeDeletesMergedTruReadId() = runBlocking {
        syncPreferences.setSelectedModelId("hem_7380t1")
        syncPreferences.setTruReadDisplayMode(TruReadDisplayMode.SEPARATE)
        val session = truReadSession()
        measurementStore.saveAll(session)
        val exporter = FakeHealthConnectExporter()
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = exporter,
            syncPreferences = syncPreferences,
        )

        val summary = orchestrator.exportStoredMeasurementsToHealthConnect()

        assertEquals(3, exporter.plan.activeItems.size)
        assertEquals(
            listOf("omron-bp:merged:1:2026-03-08T09:30:00:2026-03-08T09:32:00:2026-03-08T09:34:00"),
            exporter.plan.deletedRecordIds.map { it.bloodPressureClientRecordId },
        )
        assertEquals(3, summary.bloodPressureExported)
        assertEquals(1, summary.deletedMeasurements)
    }

    @Test
    fun exportStoredMeasurementsToHealthConnect_deletedMergedSessionDeletesBothRepresentations() = runBlocking {
        syncPreferences.setSelectedModelId("hem_7380t1")
        syncPreferences.setTruReadDisplayMode(TruReadDisplayMode.MERGE)
        val session = truReadSession()
        measurementStore.saveAll(session)
        session.forEach(measurementStore::softDelete)
        val exporter = FakeHealthConnectExporter()
        val orchestrator = SyncOrchestrator(
            context = context,
            measurementStore = measurementStore,
            healthConnectExporter = exporter,
            syncPreferences = syncPreferences,
        )

        val summary = orchestrator.exportStoredMeasurementsToHealthConnect()

        assertEquals(emptyList<Measurement>(), exporter.plan.activeItems.map { it.measurement })
        assertEquals(
            listOf(
                "omron-bp:1:2026-03-08T09:30:00:126:79:63:0:0",
                "omron-bp:1:2026-03-08T09:32:00:124:78:64:0:1",
                "omron-bp:1:2026-03-08T09:34:00:125:80:65:1:0",
                "omron-bp:merged:1:2026-03-08T09:30:00:2026-03-08T09:32:00:2026-03-08T09:34:00",
            ),
            exporter.plan.deletedRecordIds.map { it.bloodPressureClientRecordId }.sorted(),
        )
        assertEquals(0, summary.bloodPressureExported)
        assertEquals(4, summary.deletedMeasurements)
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

    private fun truReadSession(): List<Measurement> {
        return listOf(
            Measurement(
                user = 1,
                recordedAt = LocalDateTime.of(2026, 3, 8, 9, 30),
                systolic = 126,
                diastolic = 79,
                pulse = 63,
                irregularHeartbeat = false,
                movement = false,
                truReadStage = 1,
            ),
            Measurement(
                user = 1,
                recordedAt = LocalDateTime.of(2026, 3, 8, 9, 32),
                systolic = 124,
                diastolic = 78,
                pulse = 64,
                irregularHeartbeat = false,
                movement = true,
                truReadStage = 2,
            ),
            Measurement(
                user = 1,
                recordedAt = LocalDateTime.of(2026, 3, 8, 9, 34),
                systolic = 125,
                diastolic = 80,
                pulse = 65,
                irregularHeartbeat = true,
                movement = false,
                truReadStage = 3,
            ),
        )
    }

    private class FakeHealthConnectExporter : HealthConnectExporter {
        lateinit var plan: HealthConnectSyncPlan
            private set

        override fun sdkStatus(): Int = HealthConnectClient.SDK_AVAILABLE

        override suspend fun hasAllPermissions(): Boolean = true

        override suspend fun sync(plan: HealthConnectSyncPlan): HealthConnectBloodPressureExporter.ExportSummary {
            this.plan = plan
            return HealthConnectBloodPressureExporter.ExportSummary(
                bloodPressureExported = plan.activeItems.size,
                heartRateExported = plan.activeItems.size,
                deletedMeasurements = plan.deletedRecordIds.size,
                diagnostics = "",
            )
        }
    }
}
