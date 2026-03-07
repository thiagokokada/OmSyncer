package com.github.thiagokokada.omronsyncer

import com.github.thiagokokada.omronsyncer.model.Measurement

data class MainUiState(
    val measurements: List<Measurement>,
    val statusMessage: String,
    val syncLog: String,
    val deviceLabels: List<String>,
    val selectedDeviceIndex: Int,
    val isWorking: Boolean,
    val canSync: Boolean,
    val canExport: Boolean,
    val canExportLog: Boolean,
)
