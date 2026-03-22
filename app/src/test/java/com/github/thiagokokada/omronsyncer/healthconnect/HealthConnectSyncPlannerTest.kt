package com.github.thiagokokada.omronsyncer.healthconnect

import com.github.thiagokokada.omronsyncer.MeasurementListItem
import com.github.thiagokokada.omronsyncer.TruReadDisplayMode
import com.github.thiagokokada.omronsyncer.TruReadMeasurementGrouper
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class HealthConnectSyncPlannerTest {

    private val truReadModel = OmronDeviceRegistry.findById("hem_7380t1")
    private val regularModel = OmronDeviceRegistry.findById("hem_7146t")

    @Test
    fun rawRecordIdsFor_keepsExistingIdFormat() {
        val measurement = measurement(
            recordedAt = LocalDateTime.of(2026, 3, 21, 8, 14, 32),
            systolic = 126,
            diastolic = 79,
            pulse = 64,
        )

        val ids = HealthConnectSyncPlanner.rawRecordIdsFor(measurement)

        assertEquals(
            "omron-bp:1:2026-03-21T08:14:32:126:79:64:0:0",
            ids.bloodPressureClientRecordId,
        )
        assertEquals(
            "omron-hr:1:2026-03-21T08:14:32:126:79:64:0:0",
            ids.heartRateClientRecordId,
        )
    }

    @Test
    fun mergedRecordIdsFor_usesTruReadSessionTimestamps() {
        val sourceMeasurements = truReadSession()
        val mergedItem = TruReadMeasurementGrouper.displayItems(
            model = truReadModel,
            measurements = sourceMeasurements,
            displayMode = TruReadDisplayMode.MERGE,
        ).single()

        val ids = HealthConnectSyncPlanner.mergedRecordIdsFor(mergedItem)

        assertEquals(
            "omron-bp:merged:1:2026-03-21T08:14:32:2026-03-21T08:16:17:2026-03-21T08:18:03",
            ids.bloodPressureClientRecordId,
        )
        assertEquals(
            "omron-hr:merged:1:2026-03-21T08:14:32:2026-03-21T08:16:17:2026-03-21T08:18:03",
            ids.heartRateClientRecordId,
        )
    }

    @Test
    fun plan_mergeModeExportsMergedSessionAndDeletesRawIds() {
        val sourceMeasurements = truReadSession()

        val plan = HealthConnectSyncPlanner.plan(
            model = truReadModel,
            activeMeasurements = sourceMeasurements,
            deletedMeasurements = emptyList(),
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(1, plan.activeItems.size)
        assertEquals(
            "omron-bp:merged:1:2026-03-21T08:14:32:2026-03-21T08:16:17:2026-03-21T08:18:03",
            plan.activeItems.single().recordIds.bloodPressureClientRecordId,
        )
        assertEquals(
            listOf(
                "omron-bp:1:2026-03-21T08:14:32:128:80:63:0:0",
                "omron-bp:1:2026-03-21T08:16:17:124:78:64:0:1",
                "omron-bp:1:2026-03-21T08:18:03:126:79:65:1:0",
            ),
            plan.deletedRecordIds.map(HealthConnectRecordIds::bloodPressureClientRecordId).sorted(),
        )
    }

    @Test
    fun plan_separateModeExportsRawRowsAndDeletesMergedId() {
        val sourceMeasurements = truReadSession()

        val plan = HealthConnectSyncPlanner.plan(
            model = truReadModel,
            activeMeasurements = sourceMeasurements,
            deletedMeasurements = emptyList(),
            displayMode = TruReadDisplayMode.SEPARATE,
        )

        assertEquals(3, plan.activeItems.size)
        assertEquals(
            listOf("omron-bp:merged:1:2026-03-21T08:14:32:2026-03-21T08:16:17:2026-03-21T08:18:03"),
            plan.deletedRecordIds.map(HealthConnectRecordIds::bloodPressureClientRecordId),
        )
    }

    @Test
    fun plan_deletedMergedSessionDeletesMergedAndRawIds() {
        val sourceMeasurements = truReadSession()

        val plan = HealthConnectSyncPlanner.plan(
            model = truReadModel,
            activeMeasurements = emptyList(),
            deletedMeasurements = sourceMeasurements,
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(emptyList<HealthConnectExportItem>(), plan.activeItems)
        assertEquals(
            listOf(
                "omron-bp:1:2026-03-21T08:14:32:128:80:63:0:0",
                "omron-bp:1:2026-03-21T08:16:17:124:78:64:0:1",
                "omron-bp:1:2026-03-21T08:18:03:126:79:65:1:0",
                "omron-bp:merged:1:2026-03-21T08:14:32:2026-03-21T08:16:17:2026-03-21T08:18:03",
            ),
            plan.deletedRecordIds.map(HealthConnectRecordIds::bloodPressureClientRecordId).sorted(),
        )
    }

    @Test
    fun plan_incompleteTruReadRowsStayRawInMergeMode() {
        val incompleteSession = truReadSession().take(2)

        val plan = HealthConnectSyncPlanner.plan(
            model = truReadModel,
            activeMeasurements = incompleteSession,
            deletedMeasurements = emptyList(),
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(2, plan.activeItems.size)
        assertEquals(emptyList<HealthConnectRecordIds>(), plan.deletedRecordIds)
    }

    @Test
    fun plan_nonTruReadModelNeverAddsMergedIds() {
        val measurement = measurement(recordedAt = LocalDateTime.of(2026, 3, 22, 7, 0, 0))

        val plan = HealthConnectSyncPlanner.plan(
            model = regularModel,
            activeMeasurements = listOf(measurement),
            deletedMeasurements = emptyList(),
            displayMode = TruReadDisplayMode.MERGE,
        )

        assertEquals(
            listOf(HealthConnectSyncPlanner.rawRecordIdsFor(measurement)),
            plan.activeItems.map(HealthConnectExportItem::recordIds),
        )
        assertEquals(emptyList<HealthConnectRecordIds>(), plan.deletedRecordIds)
    }

    private fun truReadSession(): List<Measurement> {
        return listOf(
            measurement(
                recordedAt = LocalDateTime.of(2026, 3, 21, 8, 14, 32),
                systolic = 128,
                diastolic = 80,
                pulse = 63,
                truReadStage = 1,
            ),
            measurement(
                recordedAt = LocalDateTime.of(2026, 3, 21, 8, 16, 17),
                systolic = 124,
                diastolic = 78,
                pulse = 64,
                movement = true,
                truReadStage = 2,
            ),
            measurement(
                recordedAt = LocalDateTime.of(2026, 3, 21, 8, 18, 3),
                systolic = 126,
                diastolic = 79,
                pulse = 65,
                irregularHeartbeat = true,
                truReadStage = 3,
            ),
        )
    }

    private fun measurement(
        recordedAt: LocalDateTime,
        systolic: Int = 120,
        diastolic: Int = 80,
        pulse: Int = 64,
        irregularHeartbeat: Boolean = false,
        movement: Boolean = false,
        truReadStage: Int? = null,
    ): Measurement {
        return Measurement(
            user = 1,
            recordedAt = recordedAt,
            systolic = systolic,
            diastolic = diastolic,
            pulse = pulse,
            irregularHeartbeat = irregularHeartbeat,
            movement = movement,
            truReadStage = truReadStage,
        )
    }
}
