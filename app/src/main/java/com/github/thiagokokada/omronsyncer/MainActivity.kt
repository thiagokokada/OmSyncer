package com.github.thiagokokada.omronsyncer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.databinding.ActivityMainBinding
import com.github.thiagokokada.omronsyncer.export.MeasurementCsvExporter
import com.github.thiagokokada.omronsyncer.export.MeasurementPdfExporter
import com.github.thiagokokada.omronsyncer.export.MeasurementPdfReportBuilder
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient.PairingException
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient.SyncException
import com.github.thiagokokada.omronsyncer.omron.VerificationLevel
import com.github.thiagokokada.omronsyncer.sync.NearbySyncRegistrar
import com.github.thiagokokada.omronsyncer.sync.SyncAlreadyInProgressException
import com.github.thiagokokada.omronsyncer.sync.SyncExecutionResult
import com.github.thiagokokada.omronsyncer.sync.SyncFailureMessageFormatter
import com.github.thiagokokada.omronsyncer.sync.SyncOrchestrator
import com.github.thiagokokada.omronsyncer.sync.SyncPreferences
import com.github.thiagokokada.omronsyncer.sync.SyncWorkerNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(),
    ResultsFragment.Host,
    TrendsFragment.Host,
    SettingsFragment.Host,
    PdfReportPreviewFragment.Host,
    SyncLogFragment.Host,
    DeletedMeasurementsFragment.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncPreferences: SyncPreferences

    private val bondedDevices = mutableListOf<BluetoothDevice>()
    private val measurementStore by lazy { MeasurementStore(this) }
    private val syncClient by lazy { OmronSyncClient(this) }
    private val csvExporter by lazy { MeasurementCsvExporter() }
    private val pdfExporter by lazy { MeasurementPdfExporter(this) }
    private val pdfReportBuilder by lazy { MeasurementPdfReportBuilder() }
    private val healthConnectExporter by lazy { HealthConnectBloodPressureExporter(this) }
    private val nearbySyncRegistrar by lazy { NearbySyncRegistrar(this) }
    private val syncOrchestrator by lazy {
        SyncOrchestrator(
            context = this,
            syncClient = syncClient,
            measurementStore = measurementStore,
            healthConnectExporter = healthConnectExporter,
            syncPreferences = syncPreferences,
        )
    }

    private var measurements: List<Measurement> = emptyList()
    private var trendMeasurements: List<Measurement> = emptyList()
    private var resultsMeasurements: List<MeasurementListItem> = emptyList()
    private var deletedMeasurements: List<MeasurementListItem> = emptyList()
    private var statusMessage: String = ""
    private var lastSyncLog: String = ""
    private var lastSyncCapture: String = ""
    private var isWorking: Boolean = false
    private var selectedTabId: Int = R.id.navigation_results
    private var isHealthConnectAvailable: Boolean = false
    private var isHealthConnectSetupRequired: Boolean = false
    private var isHealthConnectConnected: Boolean = false
    private var healthConnectStatusMessage: String = ""
    private var pendingEnableNearbySync: Boolean = false
    private var isActivityVisible: Boolean = false
    private var isManualSyncInProgress: Boolean = false
    private var manualSyncNotificationShown: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                refreshAvailableDevices()
            } else {
                updateStatus(getString(R.string.permission_denied))
                showBluetoothPermissionExplanation {
                    requestBluetoothConnectPermission()
                }
            }
        }

    private val nearbySyncPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestNotificationPermissionThenEnableNearbySync()
            } else {
                pendingEnableNearbySync = false
                updateStatus(getString(R.string.nearby_sync_permission_denied))
                showBluetoothPermissionExplanation {
                    requestBluetoothScanPermission()
                }
                notifyCurrentFragment()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingEnableNearbySync = false
            if (!granted) {
                updateStatus(getString(R.string.notification_permission_denied))
                showNotificationPermissionExplanation {
                    requestNotificationPermission()
                }
            }
            setNearbySyncEnabled(true)
        }

    private val exportDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) {
                updateStatus(getString(R.string.status_export_cancelled))
                setWorking(false)
            } else {
                completeExport(uri)
            }
        }

    private val exportLogDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                updateStatus(getString(R.string.status_log_export_cancelled))
                setWorking(false)
            } else {
                completeLogExport(uri)
            }
        }

    private val exportPdfDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                updateStatus(getString(R.string.status_pdf_export_cancelled))
                setWorking(false)
            } else {
                completePdfReportExport(uri)
            }
        }

    private val exportCaptureDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                updateStatus(getString(R.string.status_capture_export_cancelled))
                setWorking(false)
            } else {
                completeCaptureExport(uri)
            }
        }

    private val healthConnectPermissionLauncher =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { grantedPermissions ->
            launchUi {
                val granted = grantedPermissions.containsAll(healthConnectExporter.requiredPermissions)
                isHealthConnectConnected = granted
                healthConnectStatusMessage = if (granted) {
                    getString(R.string.health_connect_status_connected)
                } else {
                    getString(R.string.health_connect_status_not_connected)
                }
                updateStatus(
                    if (granted) {
                        getString(R.string.status_health_connect_permissions_granted)
                    } else {
                        getString(R.string.status_health_connect_permissions_denied)
                    },
                )
                notifyCurrentFragment()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        syncPreferences = SyncPreferences(this)
        applyWindowInsets()

        binding.appTitle.text = getString(R.string.app_name)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        supportFragmentManager.addOnBackStackChangedListener {
            updateTopLevelUi()
            notifyCurrentFragment()
        }

        selectedTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.navigation_results)
            ?: R.id.navigation_results
        binding.bottomNavigation.selectedItemId = selectedTabId
        if (savedInstanceState == null || supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            showScreen(selectedTabId)
        } else {
            updateTopLevelUi()
            binding.root.post { notifyCurrentFragment() }
        }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showScreen(item.itemId)
            true
        }

        loadPersistedMeasurements()
        renderSyncLog(lastSyncLog)
        refreshHealthConnectState()
        refreshNearbySyncRegistration()
        updateStatus(getString(R.string.status_idle))
        maybeRequestInitialBluetoothPermission()
    }

    override fun onResume() {
        super.onResume()
        loadPersistedMeasurements()
        refreshAvailableDevices(requestPermission = false)
        refreshHealthConnectState()
        refreshNearbySyncRegistration()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (isManualSyncInProgress && manualSyncNotificationShown) {
            SyncWorkerNotifications.dismiss(this, MANUAL_SYNC_RUNNING_NOTIFICATION_ID)
        }
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        maybeShowManualSyncRunningNotification()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabId)
    }

    @SuppressLint("MissingPermission")
    override fun currentUiState(): MainUiState {
        val selectedModel = selectedModel()
        val measurementUserOptions = buildMeasurementUserOptions(selectedModel)
        val selectedMeasurementUser = selectedMeasurementUser(selectedModel)
        val bloodPressureClassificationScheme = syncPreferences.bloodPressureClassificationScheme()
        val bloodPressureClassificationSchemeLabels = listOf(
            getString(R.string.blood_pressure_classification_scheme_disabled),
            getString(R.string.blood_pressure_classification_scheme_jnc7),
            getString(R.string.blood_pressure_classification_scheme_esc_esh_2018),
        )
        val truReadDisplayMode = syncPreferences.truReadDisplayMode()
        val truReadDisplayModeLabels = listOf(
            getString(R.string.tru_read_display_mode_separate),
            getString(R.string.tru_read_display_mode_merge),
        )
        val measurementUserLabels = measurementUserOptions.map { user ->
            user?.let { getString(R.string.measurement_user_single, it) }
                ?: getString(R.string.measurement_user_all)
        }
        val deviceLabels = if (hasBluetoothPermission()) {
            bondedDevices.map(::deviceLabel)
        } else {
            emptyList()
        }
        val selectedDeviceIndex = if (hasBluetoothPermission()) {
            bondedDevices.indexOfFirst { it.address == selectedDeviceAddress() }
        } else {
            -1
        }
        val selectedDevice = selectedBondedDevice()
        val nearbySyncCooldownOptions = nearbySyncCooldownOptions()
        val selectedNearbySyncCooldownMinutes = syncPreferences.nearbySyncCooldownMinutes()
            .takeIf { minutes -> nearbySyncCooldownOptions.any { it.minutes == minutes } }
            ?: SyncPreferences.DEFAULT_NEARBY_SYNC_COOLDOWN_MINUTES
        val nearbySyncEnabled = syncPreferences.nearbySyncEnabled()
        val nearbySyncSummary = if (!nearbySyncEnabled) {
            getString(R.string.nearby_sync_summary_off)
        } else if (!hasBluetoothScanPermission()) {
            getString(R.string.nearby_sync_summary_missing_permission)
        } else {
            val rawSummary = syncPreferences.lastNearbySyncSummary()?.takeIf { it.isNotBlank() }
                ?: getString(R.string.nearby_sync_summary_waiting)
            syncPreferences.lastNearbySyncAtMillis()?.let { timestampMillis ->
                getString(
                    R.string.nearby_sync_summary_with_time,
                    formatSyncTimestamp(timestampMillis),
                    rawSummary,
                )
            } ?: rawSummary
        }
        return MainUiState(
            measurements = measurements,
            trendMeasurements = trendMeasurements,
            resultsMeasurements = resultsMeasurements,
            deletedMeasurements = deletedMeasurements,
            measurementUserOptions = measurementUserOptions,
            measurementUserLabels = measurementUserLabels,
            selectedMeasurementUser = selectedMeasurementUser,
            selectedMeasurementUserIndex = measurementUserOptions.indexOf(selectedMeasurementUser),
            bloodPressureClassificationSchemeLabels = bloodPressureClassificationSchemeLabels,
            selectedBloodPressureClassificationScheme = bloodPressureClassificationScheme,
            selectedBloodPressureClassificationSchemeIndex =
                BloodPressureClassificationScheme.entries.indexOf(bloodPressureClassificationScheme),
            statusMessage = statusMessage,
            syncLog = lastSyncLog,
            canExportCapture = lastSyncCapture.isNotBlank(),
            modelLabels = OmronDeviceRegistry.supportedModels.map(::modelLabel),
            selectedModelIndex = OmronDeviceRegistry.supportedModels
                .indexOfFirst { it.id == selectedModel.id },
            deviceLabels = deviceLabels,
            selectedDeviceIndex = selectedDeviceIndex,
            isWorking = isWorking,
            canSync = selectedDevice?.bondState == BluetoothDevice.BOND_BONDED,
            canExport = measurements.isNotEmpty(),
            canExportLog = lastSyncLog.isNotBlank(),
            canRestoreDeletedMeasurements = deletedMeasurements.isNotEmpty(),
            canPairSelectedDevice = selectedModel.supportsAppPairingStep && selectedDevice != null,
            healthConnectAvailable = isHealthConnectAvailable,
            healthConnectNeedsSetup = isHealthConnectSetupRequired,
            healthConnectConnected = isHealthConnectConnected,
            healthConnectStatusMessage = healthConnectStatusMessage,
            canOpenHealthConnect = isHealthConnectAvailable || isHealthConnectSetupRequired,
            canExportHealthConnect =
                (measurements.isNotEmpty() || deletedMeasurements.isNotEmpty()) &&
                    isHealthConnectAvailable &&
                    isHealthConnectConnected,
            autoExportHealthConnect = syncPreferences.healthConnectAutoExportEnabled(),
            nearbySyncEnabled = nearbySyncEnabled,
            nearbySyncSummary = nearbySyncSummary,
            nearbySyncCooldownLabels = nearbySyncCooldownOptions.map { it.label },
            selectedNearbySyncCooldownIndex =
                nearbySyncCooldownOptions.indexOfFirst { it.minutes == selectedNearbySyncCooldownMinutes },
            showsSeedSampleMeasurements = BuildConfig.DEBUG,
            canSeedSampleMeasurements = BuildConfig.DEBUG && !isWorking && measurements.isEmpty(),
            selectedTrendRange = syncPreferences.selectedTrendRange(),
            showsMeasurementUserColumn = selectedModel.userCount > 1,
            showsTruReadDisplayMode = selectedModel.supportsTruReadMerge,
            truReadDisplayModeLabels = truReadDisplayModeLabels,
            selectedTruReadDisplayModeIndex = TruReadDisplayMode.entries.indexOf(truReadDisplayMode),
            resultsDeleteEnabled = true,
        )
    }

    override fun onModelSelected(position: Int) {
        OmronDeviceRegistry.supportedModels.getOrNull(position)?.let { model ->
            syncPreferences.setSelectedModelId(model.id)
            if (syncPreferences.selectedMeasurementUser() !in buildMeasurementUserOptions(model)) {
                syncPreferences.setSelectedMeasurementUser(null)
            }
            refreshNearbySyncRegistration()
            loadPersistedMeasurements()
            refreshAvailableDevices(requestPermission = false)
        }
    }

    override fun onSyncRequested() {
        startSync()
    }

    override fun onMeasurementUserSelected(user: Int?) {
        syncPreferences.setSelectedMeasurementUser(user)
        loadPersistedMeasurements()
    }

    override fun onTruReadDisplayModeSelected(position: Int) {
        TruReadDisplayMode.entries.getOrNull(position)?.let { mode ->
            syncPreferences.setTruReadDisplayMode(mode)
            loadPersistedMeasurements()
        }
    }

    override fun onBloodPressureClassificationSchemeSelected(position: Int) {
        BloodPressureClassificationScheme.entries.getOrNull(position)?.let { scheme ->
            syncPreferences.setBloodPressureClassificationScheme(scheme)
            notifyCurrentFragment()
        }
    }

    override fun onMeasurementDeleteRequested(measurement: MeasurementListItem) {
        confirmDeleteMeasurement(measurement)
    }

    override fun onResultsRangeSelected(range: TrendRange) {
        syncPreferences.setSelectedTrendRange(range)
        loadPersistedMeasurements()
    }

    override fun onTrendRangeSelected(range: TrendRange) {
        syncPreferences.setSelectedTrendRange(range)
        loadPersistedMeasurements()
    }

    override fun onBluetoothSettingsRequested() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun onRefreshDevicesRequested() {
        refreshAvailableDevices(requestPermission = true)
    }

    override fun onPairSelectedDeviceRequested() {
        confirmExplicitPairing()
    }

    override fun onExportRequested() {
        exportMeasurements()
    }

    override fun onPdfReportRequested() {
        showPdfReportPreview()
    }

    override fun onHealthConnectRequested() {
        openHealthConnect()
    }

    override fun onHealthConnectExportRequested() {
        exportToHealthConnect()
    }

    override fun onHealthConnectAutoExportChanged(enabled: Boolean) {
        syncPreferences.setHealthConnectAutoExportEnabled(enabled)
        notifyCurrentFragment()
    }

    override fun onNearbySyncChanged(enabled: Boolean) {
        if (enabled) {
            if (selectedDeviceAddress() == null) {
                updateStatus(getString(R.string.status_no_devices))
                notifyCurrentFragment()
                return
            }
            if (!hasBluetoothScanPermission()) {
                pendingEnableNearbySync = true
                updateStatus(getString(R.string.nearby_sync_permission_required))
                requestBluetoothScanPermission()
                return
            }
            if (!hasNotificationPermission()) {
                requestNotificationPermissionThenEnableNearbySync()
                return
            }
        }

        setNearbySyncEnabled(enabled)
    }

    override fun onNearbySyncCooldownSelected(position: Int) {
        nearbySyncCooldownOptions().getOrNull(position)?.let { option ->
            syncPreferences.setNearbySyncCooldownMinutes(option.minutes)
            notifyCurrentFragment()
        }
    }

    override fun onRestoreDeletedMeasurementsRequested() {
        showDeletedMeasurements()
    }

    override fun onSeedSampleMeasurementsRequested() {
        seedSampleMeasurements()
    }

    override fun onSyncLogRequested() {
        showSyncLog()
    }

    override fun onDeletedMeasurementRestoreRequested(measurement: MeasurementListItem) {
        restoreMeasurement(measurement)
    }

    override fun onExportSyncLogRequested() {
        exportSyncLog()
    }

    override fun onExportSyncCaptureRequested() {
        exportSyncCapture()
    }

    override fun onPdfReportRangeSelected(range: TrendRange) {
        syncPreferences.setSelectedTrendRange(range)
        loadPersistedMeasurements()
    }

    override fun onPdfReportExportRequested() {
        exportPdfReport()
    }

    override fun onDeviceSelected(position: Int) {
        bondedDevices.getOrNull(position)?.address?.let(::persistSelectedDeviceAddress)
        notifyCurrentFragment()
    }

    private fun showScreen(itemId: Int) {
        selectedTabId = itemId
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )
        val fragment: Fragment = when (itemId) {
            R.id.navigation_trends -> TrendsFragment()
            R.id.navigation_settings -> SettingsFragment()
            else -> ResultsFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitNow()

        updateTopLevelUi()
        notifyCurrentFragment()
    }

    private fun showSyncLog() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.animator.detail_enter_from_end,
                R.animator.detail_exit_to_start,
                R.animator.detail_pop_enter_from_start,
                R.animator.detail_pop_exit_to_end,
            )
            .replace(R.id.fragment_container, SyncLogFragment())
            .addToBackStack(BACKSTACK_SYNC_LOG)
            .commit()

        binding.root.post {
            updateTopLevelUi()
            notifyCurrentFragment()
        }
    }

    private fun showPdfReportPreview() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.animator.detail_enter_from_end,
                R.animator.detail_exit_to_start,
                R.animator.detail_pop_enter_from_start,
                R.animator.detail_pop_exit_to_end,
            )
            .replace(R.id.fragment_container, PdfReportPreviewFragment())
            .addToBackStack(BACKSTACK_PDF_REPORT)
            .commit()

        binding.root.post {
            updateTopLevelUi()
            notifyCurrentFragment()
        }
    }

    private fun applyWindowInsets() {
        val toolbarPaddingTop = binding.toolbar.paddingTop
        val toolbarPaddingLeft = binding.toolbar.paddingLeft
        val toolbarPaddingRight = binding.toolbar.paddingRight
        val bottomNavigationPaddingLeft = binding.bottomNavigation.paddingLeft
        val bottomNavigationPaddingRight = binding.bottomNavigation.paddingRight
        val bottomNavigationPaddingBottom = binding.bottomNavigation.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(
                left = toolbarPaddingLeft + systemBars.left,
                top = toolbarPaddingTop + systemBars.top,
                right = toolbarPaddingRight + systemBars.right,
            )
            binding.bottomNavigation.updatePadding(
                left = bottomNavigationPaddingLeft + systemBars.left,
                right = bottomNavigationPaddingRight + systemBars.right,
                bottom = bottomNavigationPaddingBottom + systemBars.bottom,
            )
            windowInsets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    @SuppressLint("MissingPermission")
    private fun refreshAvailableDevices(requestPermission: Boolean = false) {
        if (!ensureBluetoothPermission(requestPermission = requestPermission)) {
            bondedDevices.clear()
            notifyCurrentFragment()
            return
        }

        val bluetoothAdapter = bluetoothAdapter()
        if (bluetoothAdapter == null) {
            bondedDevices.clear()
            updateStatus(getString(R.string.status_no_adapter))
            notifyCurrentFragment()
            return
        }

        updateStatus(getString(R.string.status_loading_devices))

        bondedDevices.clear()
        bondedDevices += bluetoothAdapter.bondedDevices
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.address }))

        if (selectedDeviceAddress() == null && bondedDevices.isNotEmpty()) {
            persistSelectedDeviceAddress(bondedDevices.first().address)
        }

        updateStatus(
            if (bondedDevices.isNotEmpty()) {
                getString(R.string.status_idle)
            } else {
                getString(R.string.status_no_devices)
            },
        )
        notifyCurrentFragment()
    }

    @SuppressLint("MissingPermission")
    private fun startSync() {
        if (!ensureBluetoothPermission(requestPermission = true)) {
            return
        }

        val selectedAddress = selectedDeviceAddress()
        val device = selectedBondedDevice()

        if (device == null) {
            updateStatus(
                if (selectedAddress == null) {
                    getString(R.string.status_no_devices)
                } else {
                    getString(R.string.status_selected_device_not_found)
                },
            )
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            updateStatus(getString(R.string.status_pair_device))
            return
        }

        persistSelectedDeviceAddress(device.address)
        beginManualSync()
        setWorking(true)
        updateStatus(getString(R.string.status_syncing))

        launchUi {
            runCatching {
                syncOrchestrator.syncSelectedDevice(syncSource = "manual")
            }.onSuccess { result ->
                logSyncDiagnostics(source = "manual", syncLog = result.syncLog)
                renderSyncResult(result)
                updateStatus(getString(R.string.status_idle))
                showToast(syncCompletionToast(result))
                finishManualSync(result)
            }.onFailure { error ->
                if (error is SyncException) {
                    logSyncDiagnostics(source = "manual-failure", syncLog = error.diagnostics.asText())
                    renderSyncLog(error.diagnostics.asText())
                    renderSyncCapture(error.capture.asFixtureText())
                }
                val message = SyncFailureMessageFormatter.userFacingMessage(this@MainActivity, error)
                updateStatus(message)
                if (error is SyncAlreadyInProgressException) {
                    showToast(message)
                } else {
                    showToast(getString(R.string.toast_sync_failed, message))
                }
                finishManualSync()
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    @SuppressLint("MissingPermission")
    private fun confirmExplicitPairing() {
        if (!ensureBluetoothPermission(requestPermission = true)) {
            return
        }

        val model = selectedModel()
        if (!model.supportsAppPairingStep) {
            updateStatus(getString(R.string.status_pairing_not_supported))
            return
        }

        val selectedAddress = selectedDeviceAddress()
        val device = selectedBondedDevice()
        if (device == null) {
            updateStatus(
                if (selectedAddress == null) {
                    getString(R.string.status_no_devices)
                } else {
                    getString(R.string.status_selected_device_not_found)
                },
            )
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pairing_dialog_title)
            .setMessage(R.string.pairing_dialog_message)
            .setNegativeButton(R.string.close_label, null)
            .setPositiveButton(R.string.pairing_dialog_confirm) { _, _ ->
                startExplicitPairing(device, model)
            }
            .show()
    }

    private fun startExplicitPairing(
        device: BluetoothDevice,
        model: OmronDeviceDefinition,
    ) {
        persistSelectedDeviceAddress(device.address)
        beginManualSync()
        setWorking(true)
        updateStatus(getString(R.string.status_pairing))

        launchUi {
            runCatching {
                syncClient.pair(device, model)
            }.onSuccess { result ->
                logSyncDiagnostics(source = "manual-pair", syncLog = result.diagnostics.asText())
                renderSyncLog(result.diagnostics.asText())
                renderSyncCapture(result.capture.asFixtureText())
                updateStatus(getString(R.string.status_pairing_complete))
                showToast(getString(R.string.status_pairing_complete))
                finishManualSync()
            }.onFailure { error ->
                if (error is PairingException) {
                    logSyncDiagnostics(source = "manual-pair-failure", syncLog = error.diagnostics.asText())
                    renderSyncLog(error.diagnostics.asText())
                    renderSyncCapture(error.capture.asFixtureText())
                }
                val message = SyncFailureMessageFormatter.userFacingMessage(this@MainActivity, error)
                updateStatus(message)
                showToast(getString(R.string.toast_pairing_failed, message))
                finishManualSync()
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectedBondedDevice(): BluetoothDevice? {
        val selectedAddress = selectedDeviceAddress()
        return bondedDevices.firstOrNull { it.address == selectedAddress }
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice): String {
        val displayName = device.name ?: getString(R.string.device_name_placeholder)
        return "$displayName (${device.address})"
    }

    private fun renderSyncResult(result: SyncExecutionResult) {
        renderSyncLog(result.syncLog)
        renderSyncCapture(result.syncCapture.asFixtureText())
        launchUi {
            val measurementState = withContext(Dispatchers.IO) {
                loadStoredMeasurementState(selectedMeasurementUser())
            }
            applyStoredMeasurementState(measurementState)
            notifyCurrentFragment()
        }
    }

    private fun loadPersistedMeasurements() {
        launchUi {
            val selectedUser = selectedMeasurementUser()
            val measurementState = withContext(Dispatchers.IO) {
                loadStoredMeasurementState(selectedUser)
            }
            applyStoredMeasurementState(measurementState)
            notifyCurrentFragment()
        }
    }

    private fun confirmDeleteMeasurement(measurement: MeasurementListItem) {
        val message = buildString {
            append(getString(R.string.delete_measurement_message))
            if (!canDeleteFromHealthConnect()) {
                append("\n\n")
                append(getString(R.string.delete_measurement_message_health_connect_warning))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_measurement_title)
            .setMessage(message)
            .setNegativeButton(R.string.close_label, null)
            .setPositiveButton(R.string.delete_measurement_confirm) { _, _ ->
                deleteMeasurement(measurement)
            }
            .show()
    }

    private fun deleteMeasurement(measurement: MeasurementListItem) {
        setWorking(true)
        updateStatus(getString(R.string.status_delete_measurement))

        launchUi {
            var healthConnectWarning = false
            runCatching {
                withContext(Dispatchers.IO) {
                    measurement.sourceMeasurements.forEach(measurementStore::softDelete)
                }
                if (canDeleteFromHealthConnect()) {
                    runCatching {
                        syncOrchestrator.exportStoredMeasurementsToHealthConnect().also { summary ->
                            if (summary.diagnostics.isNotBlank()) {
                                logSyncDiagnostics(source = "health-connect-delete", syncLog = summary.diagnostics)
                            }
                        }
                    }.onFailure {
                        healthConnectWarning = true
                    }
                } else {
                    healthConnectWarning = true
                }

                val selectedUser = selectedMeasurementUser()
                withContext(Dispatchers.IO) {
                    loadStoredMeasurementState(selectedUser)
                }
            }.onSuccess { measurementState ->
                applyStoredMeasurementState(measurementState)
                val message = if (healthConnectWarning) {
                    getString(R.string.status_measurement_deleted_health_connect_warning)
                } else {
                    getString(R.string.status_measurement_deleted)
                }
                updateStatus(message)
                showToast(getString(R.string.status_measurement_deleted))
            }.onFailure {
                updateStatus(getString(R.string.status_measurement_delete_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun showDeletedMeasurements() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.animator.detail_enter_from_end,
                R.animator.detail_exit_to_start,
                R.animator.detail_pop_enter_from_start,
                R.animator.detail_pop_exit_to_end,
            )
            .replace(R.id.fragment_container, DeletedMeasurementsFragment())
            .addToBackStack(BACKSTACK_DELETED_MEASUREMENTS)
            .commit()

        binding.root.post {
            updateTopLevelUi()
            notifyCurrentFragment()
        }
    }

    private fun restoreMeasurement(measurement: MeasurementListItem) {
        setWorking(true)

        launchUi {
            var healthConnectWarning = false
            runCatching {
                withContext(Dispatchers.IO) {
                    measurement.sourceMeasurements.forEach(measurementStore::undelete)
                }
                if (isHealthConnectAvailable && isHealthConnectConnected) {
                    runCatching {
                        syncOrchestrator.exportStoredMeasurementsToHealthConnect().also { summary ->
                            if (summary.diagnostics.isNotBlank()) {
                                logSyncDiagnostics(source = "health-connect-restore", syncLog = summary.diagnostics)
                            }
                        }
                    }.onFailure {
                        healthConnectWarning = true
                    }
                } else {
                    healthConnectWarning = true
                }

                val selectedUser = selectedMeasurementUser()
                withContext(Dispatchers.IO) {
                    loadStoredMeasurementState(selectedUser)
                }
            }.onSuccess { measurementState ->
                applyStoredMeasurementState(measurementState)
                val message = if (healthConnectWarning) {
                    getString(R.string.status_measurement_restored_health_connect_warning)
                } else {
                    getString(R.string.status_measurement_restored)
                }
                updateStatus(message)
                showToast(getString(R.string.status_measurement_restored))
            }.onFailure {
                updateStatus(getString(R.string.status_measurement_restore_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun seedSampleMeasurements() {
        val model = selectedModel()
        setWorking(true)
        updateStatus(getString(R.string.status_seed_measurements))

        launchUi {
            runCatching {
                val seededMeasurements = sampleMeasurementsForModel(model)
                val measurementState = withContext(Dispatchers.IO) {
                    measurementStore.saveAll(seededMeasurements)
                    loadStoredMeasurementState(selectedMeasurementUser(model))
                }
                measurementState to seededMeasurements.size
            }.onSuccess { (measurementState, seededCount) ->
                applyStoredMeasurementState(measurementState)
                updateStatus(getString(R.string.status_seeded_measurements, seededCount))
                showToast(getString(R.string.status_seeded_measurements, seededCount))
            }.onFailure {
                updateStatus(getString(R.string.status_seed_measurements_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun refreshHealthConnectState() {
        when (healthConnectExporter.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                isHealthConnectAvailable = true
                isHealthConnectSetupRequired = false
                launchUi {
                    isHealthConnectConnected = runCatching {
                        healthConnectExporter.hasAllPermissions()
                    }.getOrDefault(false)
                    healthConnectStatusMessage = if (isHealthConnectConnected) {
                        getString(R.string.health_connect_status_connected)
                    } else {
                        getString(R.string.health_connect_status_not_connected)
                    }
                    notifyCurrentFragment()
                }
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                isHealthConnectAvailable = false
                isHealthConnectSetupRequired = true
                isHealthConnectConnected = false
                healthConnectStatusMessage = getString(R.string.health_connect_status_setup_required)
                notifyCurrentFragment()
            }

            else -> {
                isHealthConnectAvailable = false
                isHealthConnectSetupRequired = false
                isHealthConnectConnected = false
                healthConnectStatusMessage = getString(R.string.health_connect_status_unavailable)
                notifyCurrentFragment()
            }
        }
    }

    private fun exportMeasurements() {
        setWorking(true)
        updateStatus(getString(R.string.status_export_choose_location))
        exportDocumentLauncher.launch(csvExporter.suggestedFileName())
    }

    private fun exportPdfReport() {
        setWorking(true)
        updateStatus(getString(R.string.status_pdf_export_choose_location))
        exportPdfDocumentLauncher.launch(pdfExporter.suggestedFileName())
    }

    private fun openHealthConnect() {
        when {
            isHealthConnectSetupRequired -> startActivity(healthConnectExporter.manageOrInstallIntent())
            !isHealthConnectAvailable -> updateStatus(getString(R.string.health_connect_status_unavailable))
            isHealthConnectConnected -> startActivity(healthConnectExporter.manageOrInstallIntent())
            else -> healthConnectPermissionLauncher.launch(healthConnectExporter.requiredPermissions)
        }
    }

    private fun exportToHealthConnect() {
        if (!isHealthConnectAvailable) {
            updateStatus(getString(R.string.health_connect_status_unavailable))
            return
        }
        if (!isHealthConnectConnected) {
            updateStatus(getString(R.string.health_connect_status_not_connected))
            return
        }
        if (measurements.isEmpty() && deletedMeasurements.isEmpty()) {
            showToast(getString(R.string.status_health_connect_no_matching_measurements))
            return
        }

        setWorking(true)
        updateStatus(getString(R.string.status_health_connect_exporting))

        launchUi {
            runCatching {
                syncOrchestrator.exportStoredMeasurementsToHealthConnect()
            }.onSuccess { summary ->
                if (summary.diagnostics.isNotBlank()) {
                    logSyncDiagnostics(source = "health-connect-export", syncLog = summary.diagnostics)
                    renderSyncLog(summary.diagnostics)
                }
                updateStatus(healthConnectExportMessage(summary))
                showToast(getString(R.string.toast_health_connect_exported))
            }.onFailure { error ->
                updateStatus(SyncFailureMessageFormatter.userFacingMessage(this@MainActivity, error))
            }

            setWorking(false)
            refreshHealthConnectState()
            notifyCurrentFragment()
        }
    }

    private fun exportSyncLog() {
        if (lastSyncLog.isBlank()) {
            updateStatus(getString(R.string.sync_log_empty))
            return
        }

        setWorking(true)
        updateStatus(getString(R.string.status_log_export_choose_location))
        exportLogDocumentLauncher.launch(
            csvExporter.suggestedFileName(
                prefix = "omsyncer-sync-log",
                extension = "txt",
            ),
        )
    }

    private fun exportSyncCapture() {
        if (lastSyncCapture.isBlank()) {
            updateStatus(getString(R.string.status_capture_empty))
            return
        }

        setWorking(true)
        updateStatus(getString(R.string.status_capture_export_choose_location))
        exportCaptureDocumentLauncher.launch(
            csvExporter.suggestedFileName(
                prefix = "omsyncer-sync-capture",
                extension = "txt",
            ),
        )
    }

    private fun completeExport(uri: Uri) {
        updateStatus(getString(R.string.status_exporting))

        launchUi {
            runCatching {
                val measurementState = withContext(Dispatchers.IO) {
                    loadStoredMeasurementState(selectedMeasurementUser())
                }
                val exportMeasurements = measurementState.resultsMeasurements.map(MeasurementListItem::displayMeasurement)
                if (exportMeasurements.isEmpty()) {
                    throw IllegalStateException(NO_MEASUREMENTS_TO_EXPORT)
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        csvExporter.export(outputStream, exportMeasurements)
                    } ?: throw IllegalStateException(EXPORT_DESTINATION_UNAVAILABLE)
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_exported))
            }.onFailure { error ->
                updateStatus(exportFailureMessage(error, R.string.status_export_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun completePdfReportExport(uri: Uri) {
        updateStatus(getString(R.string.status_pdf_exporting))

        launchUi {
            runCatching {
                val selectedUser = selectedMeasurementUser()
                val report = withContext(Dispatchers.IO) {
                    val measurementState = loadStoredMeasurementState(selectedUser)
                    val exportMeasurements = measurementState.resultsMeasurements
                        .map(MeasurementListItem::displayMeasurement)
                    if (exportMeasurements.isEmpty()) {
                        throw IllegalStateException(NO_MEASUREMENTS_TO_EXPORT)
                    }
                    pdfReportBuilder.build(
                        measurements = exportMeasurements,
                        range = syncPreferences.selectedTrendRange(),
                        selectedUser = selectedUser,
                        classificationScheme = syncPreferences.bloodPressureClassificationScheme(),
                    )
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        pdfExporter.export(outputStream, report)
                    } ?: throw IllegalStateException(EXPORT_DESTINATION_UNAVAILABLE)
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_pdf_exported))
            }.onFailure { error ->
                updateStatus(exportFailureMessage(error, R.string.status_pdf_export_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun completeLogExport(uri: Uri) {
        updateStatus(getString(R.string.status_log_exporting))

        launchUi {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(lastSyncLog)
                    } ?: throw IllegalStateException(EXPORT_DESTINATION_UNAVAILABLE)
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_log_exported))
            }.onFailure { error ->
                updateStatus(exportFailureMessage(error, R.string.status_log_export_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun completeCaptureExport(uri: Uri) {
        updateStatus(getString(R.string.status_capture_exporting))

        launchUi {
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(lastSyncCapture)
                    } ?: throw IllegalStateException(EXPORT_DESTINATION_UNAVAILABLE)
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_capture_exported))
            }.onFailure { error ->
                updateStatus(exportFailureMessage(error, R.string.status_capture_export_failed))
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun notifyCurrentFragment() {
        when (val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)) {
            is ResultsFragment -> fragment.render(currentUiState())
            is TrendsFragment -> fragment.render(currentUiState())
            is SettingsFragment -> fragment.render(currentUiState())
            is PdfReportPreviewFragment -> fragment.render(currentUiState())
            is SyncLogFragment -> fragment.render(currentUiState())
            is DeletedMeasurementsFragment -> fragment.render(currentUiState())
        }
    }

    private fun renderSyncLog(log: String) {
        lastSyncLog = log
        notifyCurrentFragment()
    }

    private fun renderSyncCapture(capture: String) {
        lastSyncCapture = capture
        notifyCurrentFragment()
    }

    private fun updateStatus(message: String) {
        statusMessage = message
        notifyCurrentFragment()
    }

    private fun setWorking(working: Boolean) {
        isWorking = working
        binding.progressIndicator.visibility = if (working) View.VISIBLE else View.GONE
        notifyCurrentFragment()
    }

    private fun beginManualSync() {
        isManualSyncInProgress = true
        manualSyncNotificationShown = false
        SyncWorkerNotifications.dismiss(this, MANUAL_SYNC_RUNNING_NOTIFICATION_ID)
    }

    private fun finishManualSync(result: SyncExecutionResult? = null) {
        val shouldShowCompletionNotification = manualSyncNotificationShown && !isActivityVisible
        SyncWorkerNotifications.dismiss(this, MANUAL_SYNC_RUNNING_NOTIFICATION_ID)
        if (shouldShowCompletionNotification && result != null) {
            SyncWorkerNotifications.showSuccessfulSync(
                context = this,
                notificationId = MANUAL_SYNC_SUCCESS_NOTIFICATION_ID,
                titleResId = R.string.manual_sync_success_notification_title,
                inserted = result.insertedDisplayCount,
                exportedToHealthConnect = result.healthConnectExportSummary != null,
            )
        }
        isManualSyncInProgress = false
        manualSyncNotificationShown = false
    }

    private fun maybeShowManualSyncRunningNotification() {
        if (!isManualSyncInProgress || manualSyncNotificationShown) {
            return
        }
        SyncWorkerNotifications.showRunningSync(
            context = this,
            notificationId = MANUAL_SYNC_RUNNING_NOTIFICATION_ID,
            titleResId = R.string.manual_sync_notification_title,
            bodyResId = R.string.manual_sync_notification_body,
        )
        manualSyncNotificationShown = true
    }

    private fun logSyncDiagnostics(source: String, syncLog: String) {
        if (syncLog.isBlank()) {
            return
        }
        Log.d(SYNC_LOG_TAG, "[$source] diagnostics begin")
        syncLog.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                Log.d(SYNC_LOG_TAG, "[$source] $line")
            }
        Log.d(SYNC_LOG_TAG, "[$source] diagnostics end")
    }

    private fun persistSelectedDeviceAddress(address: String) {
        syncPreferences.setSelectedDeviceAddress(address)
        refreshNearbySyncRegistration()
    }

    private fun selectedDeviceAddress(): String? {
        return syncPreferences.selectedDeviceAddress()
    }

    private fun ensureBluetoothPermission(requestPermission: Boolean): Boolean {
        val granted = hasBluetoothPermission()

        if (!granted && requestPermission) {
            updateStatus(getString(R.string.status_missing_permission))
            requestBluetoothConnectPermission()
        }

        return granted
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun setNearbySyncEnabled(enabled: Boolean) {
        syncPreferences.setNearbySyncEnabled(enabled)
        refreshNearbySyncRegistration()
        updateStatus(
            if (enabled) {
                getString(R.string.status_nearby_sync_enabled)
            } else {
                getString(R.string.status_nearby_sync_disabled)
            },
        )
        notifyCurrentFragment()
    }

    private fun refreshNearbySyncRegistration() {
        nearbySyncRegistrar.updateRegistration(
            enabled = syncPreferences.nearbySyncEnabled() && hasBluetoothScanPermission(),
            model = selectedModel(),
        )
    }

    private fun requestNotificationPermissionThenEnableNearbySync() {
        if (hasNotificationPermission()) {
            pendingEnableNearbySync = false
            setNearbySyncEnabled(true)
            return
        }

        pendingEnableNearbySync = true
        requestNotificationPermission()
    }

    private fun requestBluetoothConnectPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
    }

    private fun requestBluetoothScanPermission() {
        nearbySyncPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showBluetoothPermissionExplanation(onRetry: () -> Unit) {
        showPermissionExplanation(
            permission = Manifest.permission.BLUETOOTH_CONNECT,
            titleResId = R.string.bluetooth_permission_explanation_title,
            messageResId = R.string.bluetooth_permission_explanation_body,
            onRetry = onRetry,
        )
    }

    private fun showNotificationPermissionExplanation(onRetry: () -> Unit) {
        showPermissionExplanation(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            titleResId = R.string.notification_permission_explanation_title,
            messageResId = R.string.notification_permission_explanation_body,
            onRetry = onRetry,
        )
    }

    private fun showPermissionExplanation(
        permission: String,
        titleResId: Int,
        messageResId: Int,
        onRetry: () -> Unit,
    ) {
        val canRequestAgain = shouldShowRequestPermissionRationale(permission)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setPositiveButton(
                if (canRequestAgain) {
                    R.string.permission_try_again
                } else {
                    R.string.permission_open_settings
                },
            ) { _, _ ->
                if (canRequestAgain) {
                    onRetry()
                } else {
                    openAppSettings()
                }
            }
            .setNegativeButton(R.string.close_label, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun maybeRequestInitialBluetoothPermission() {
        if (hasBluetoothPermission()) {
            return
        }
        if (syncPreferences.initialBluetoothPermissionPromptShown()) {
            return
        }

        syncPreferences.setInitialBluetoothPermissionPromptShown(true)
        requestBluetoothConnectPermission()
    }

    private fun buildMeasurementUserOptions(
        model: OmronDeviceDefinition,
    ): List<Int?> {
        val users = model.userLayouts.map { it.user }
        return when {
            users.isEmpty() -> emptyList()
            users.size == 1 -> listOf(users.first())
            else -> listOf(null) + users
        }
    }

    private fun selectedMeasurementUser(
        model: OmronDeviceDefinition = selectedModel(),
    ): Int? {
        val users = model.userLayouts.map { it.user }
        return when {
            users.isEmpty() -> null
            users.size == 1 -> users.first()
            else -> syncPreferences.selectedMeasurementUser().takeIf { it in users }
        }
    }

    private fun applyStoredMeasurementState(state: StoredMeasurementState) {
        measurements = state.measurements
        trendMeasurements = state.trendMeasurements
        resultsMeasurements = state.resultsMeasurements
        deletedMeasurements = state.deletedMeasurements
    }

    private fun loadStoredMeasurementState(
        selectedUser: Int?,
    ): StoredMeasurementState {
        val resultsRecordedAtFrom = syncPreferences.selectedTrendRange()
            .recordedAtFrom(LocalDateTime.now())
        val selectedModel = selectedModel()
        val truReadDisplayMode = syncPreferences.truReadDisplayMode()
        val storedMeasurements = measurementStore.loadAll(selectedUser)
        val storedTrendMeasurements = measurementStore.loadAll(selectedUser)
        return StoredMeasurementState(
            measurements = storedMeasurements,
            trendMeasurements = TruReadMeasurementGrouper.displayMeasurements(
                model = selectedModel,
                measurements = storedTrendMeasurements,
                displayMode = truReadDisplayMode,
            ),
            resultsMeasurements = TruReadMeasurementGrouper.displayItems(
                model = selectedModel,
                measurements = measurementStore.loadAll(
                    user = selectedUser,
                    recordedAtFrom = resultsRecordedAtFrom,
                ),
                displayMode = truReadDisplayMode,
            ),
            deletedMeasurements = TruReadMeasurementGrouper.displayItems(
                model = selectedModel,
                measurements = measurementStore.loadDeleted(selectedUser),
                displayMode = truReadDisplayMode,
            ),
        )
    }

    private fun selectedModel(): OmronDeviceDefinition {
        return syncPreferences.selectedModel()
    }

    private fun sampleMeasurementsForModel(model: OmronDeviceDefinition): List<Measurement> {
        val users = model.userLayouts.map { it.user }.ifEmpty { listOf(1) }
        val baseTime = LocalDateTime.now().withNano(0)
        return List(SAMPLE_MEASUREMENTS_TOTAL) { index ->
            val userIndex = index % users.size
            val user = users[userIndex]
            val dayOffset = ((index.toLong() * SAMPLE_MEASUREMENTS_DAY_SPAN) / SAMPLE_MEASUREMENTS_TOTAL)
            val recordedAt = baseTime
                .minusDays(dayOffset)
                .minusHours((index % 4).toLong() + (userIndex * 2L))
                .minusMinutes((index % 3 * 10).toLong())
            Measurement(
                user = user,
                recordedAt = recordedAt,
                systolic = 118 + userIndex + ((index % 15) * 2),
                diastolic = 76 + userIndex + (index % 10),
                pulse = 60 + userIndex + (index % 12),
                irregularHeartbeat = index % 9 == 0,
                movement = index % 7 == 0,
            )
        }.sortedByDescending { it.recordedAt }
    }

    private fun modelLabel(model: OmronDeviceDefinition): String {
        return when (model.verificationLevel) {
            VerificationLevel.VERIFIED -> model.modelCode
            VerificationLevel.EXPERIMENTAL ->
                "${model.modelCode} - ${getString(R.string.model_support_experimental)}"
        }
    }

    private fun formatSyncTimestamp(timestampMillis: Long): String {
        return SYNC_TIME_FORMATTER.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
        )
    }

    private fun syncCompletionToast(result: SyncExecutionResult): String {
        val inserted = result.insertedDisplayCount
        val exportedToHealthConnect = result.healthConnectExportSummary != null
        return when {
            inserted <= 0 && exportedToHealthConnect ->
                getString(R.string.toast_sync_no_new_health_connect)

            inserted <= 0 ->
                getString(R.string.toast_sync_no_new)

            exportedToHealthConnect ->
                resources.getQuantityString(
                    R.plurals.toast_sync_saved_new_health_connect,
                    inserted,
                    inserted,
                )

            else ->
                resources.getQuantityString(
                    R.plurals.toast_sync_saved_new,
                    inserted,
                    inserted,
                )
        }
    }

    private fun healthConnectExportMessage(
        summary: HealthConnectBloodPressureExporter.ExportSummary,
    ): String {
        return when {
            summary.deletedMeasurements > 0 && summary.bloodPressureExported > 0 -> {
                getString(
                    R.string.status_health_connect_exported_with_deletions,
                    summary.bloodPressureExported,
                    summary.heartRateExported,
                    summary.deletedMeasurements,
                )
            }

            summary.deletedMeasurements > 0 -> {
                getString(
                    R.string.status_health_connect_deleted_measurements,
                    summary.deletedMeasurements,
                )
            }

            else -> {
                getString(
                    R.string.status_health_connect_exported,
                    summary.bloodPressureExported,
                    summary.heartRateExported,
                )
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun canDeleteFromHealthConnect(): Boolean {
        return isHealthConnectAvailable && isHealthConnectConnected
    }

    private fun launchUi(block: suspend CoroutineScope.() -> Unit) {
        lifecycleScope.launch(block = block)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun exportFailureMessage(error: Throwable, genericMessageRes: Int): String {
        return when (error.message) {
            NO_MEASUREMENTS_TO_EXPORT -> getString(R.string.status_export_no_measurements)
            EXPORT_DESTINATION_UNAVAILABLE -> getString(R.string.status_export_destination_unavailable)
            else -> getString(genericMessageRes)
        }
    }

    private fun updateTopLevelUi() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isLogScreen = currentFragment is SyncLogFragment
        val isDeletedMeasurementsScreen = currentFragment is DeletedMeasurementsFragment
        val isPdfReportScreen = currentFragment is PdfReportPreviewFragment
        val isDetailScreen = isLogScreen || isDeletedMeasurementsScreen || isPdfReportScreen

        binding.screenTitle.text = when {
            isLogScreen -> getString(R.string.sync_log_title)
            isDeletedMeasurementsScreen -> getString(R.string.deleted_measurements_title)
            isPdfReportScreen -> getString(R.string.pdf_report_title)
            selectedTabId == R.id.navigation_trends -> getString(R.string.trends_title)
            selectedTabId == R.id.navigation_settings -> getString(R.string.settings_title)
            else -> getString(R.string.results_title)
        }
        binding.toolbar.navigationIcon =
            if (isDetailScreen) {
                AppCompatResources.getDrawable(
                    this,
                    androidx.appcompat.R.drawable.abc_ic_ab_back_material,
                )
            } else {
                null
            }
        binding.bottomNavigation.visibility = if (isDetailScreen) View.GONE else View.VISIBLE
    }

    private data class StoredMeasurementState(
        val measurements: List<Measurement>,
        val trendMeasurements: List<Measurement>,
        val resultsMeasurements: List<MeasurementListItem>,
        val deletedMeasurements: List<MeasurementListItem>,
    )

    private data class NearbySyncCooldownOption(
        val minutes: Int,
        val label: String,
    )

    private fun nearbySyncCooldownOptions(): List<NearbySyncCooldownOption> {
        return buildList {
            if (BuildConfig.DEBUG) {
                add(NearbySyncCooldownOption(0, getString(R.string.nearby_sync_cooldown_immediately)))
            }
            add(NearbySyncCooldownOption(1, getString(R.string.nearby_sync_cooldown_1_minute)))
            add(NearbySyncCooldownOption(2, getString(R.string.nearby_sync_cooldown_2_minutes)))
            add(NearbySyncCooldownOption(3, getString(R.string.nearby_sync_cooldown_3_minutes)))
            add(NearbySyncCooldownOption(5, getString(R.string.nearby_sync_cooldown_5_minutes)))
            add(NearbySyncCooldownOption(10, getString(R.string.nearby_sync_cooldown_10_minutes)))
        }
    }

    private companion object {
        const val BACKSTACK_DELETED_MEASUREMENTS = "deleted_measurements"
        const val BACKSTACK_SYNC_LOG = "sync_log"
        const val BACKSTACK_PDF_REPORT = "pdf_report"
        const val KEY_SELECTED_TAB = "selected_tab"
        const val SYNC_LOG_TAG = "OmSyncerSync"
        const val MANUAL_SYNC_RUNNING_NOTIFICATION_ID = 1004
        const val MANUAL_SYNC_SUCCESS_NOTIFICATION_ID = 1005
        const val SAMPLE_MEASUREMENTS_TOTAL = 100
        const val SAMPLE_MEASUREMENTS_DAY_SPAN = 90L
        const val NO_MEASUREMENTS_TO_EXPORT = "no-measurements-to-export"
        const val EXPORT_DESTINATION_UNAVAILABLE = "destination-unavailable"
        val SYNC_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
