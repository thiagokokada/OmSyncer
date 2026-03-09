package com.github.thiagokokada.omronsyncer.healthconnect

import com.github.thiagokokada.omronsyncer.model.Measurement

interface HealthConnectExporter {
    fun sdkStatus(): Int
    suspend fun hasAllPermissions(): Boolean
    suspend fun sync(
        activeMeasurements: List<Measurement>,
        deletedMeasurements: List<Measurement>,
    ): HealthConnectBloodPressureExporter.ExportSummary
}
