package com.github.thiagokokada.omronsyncer.healthconnect

interface HealthConnectExporter {
    fun sdkStatus(): Int
    suspend fun hasAllPermissions(): Boolean
    suspend fun sync(plan: HealthConnectSyncPlan): HealthConnectBloodPressureExporter.ExportSummary
}
