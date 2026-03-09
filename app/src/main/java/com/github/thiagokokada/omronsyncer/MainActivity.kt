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
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.databinding.ActivityMainBinding
import com.github.thiagokokada.omronsyncer.export.MeasurementCsvExporter
import com.github.thiagokokada.omronsyncer.healthconnect.HealthConnectBloodPressureExporter
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceDefinition
import com.github.thiagokokada.omronsyncer.omron.OmronDeviceRegistry
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient
import com.github.thiagokokada.omronsyncer.omron.OmronSyncClient.SyncException
import com.github.thiagokokada.omronsyncer.omron.VerificationLevel
import com.github.thiagokokada.omronsyncer.sync.MissingBluetoothPermissionException
import com.github.thiagokokada.omronsyncer.sync.NearbySyncRegistrar
import com.github.thiagokokada.omronsyncer.sync.SyncExecutionResult
import com.github.thiagokokada.omronsyncer.sync.SyncOrchestrator
import com.github.thiagokokada.omronsyncer.sync.SyncPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(), ResultsFragment.Host, TrendsFragment.Host, SettingsFragment.Host, SyncLogFragment.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var syncPreferences: SyncPreferences

    private val bondedDevices = mutableListOf<BluetoothDevice>()
    private val measurementStore by lazy { MeasurementStore(this) }
    private val syncClient by lazy { OmronSyncClient(this) }
    private val csvExporter by lazy { MeasurementCsvExporter() }
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
    private var statusMessage: String = ""
    private var lastSyncLog: String = ""
    private var isWorking: Boolean = false
    private var selectedTabId: Int = R.id.navigation_results
    private var isHealthConnectAvailable: Boolean = false
    private var isHealthConnectSetupRequired: Boolean = false
    private var isHealthConnectConnected: Boolean = false
    private var healthConnectStatusMessage: String = ""
    private var pendingEnableNearbySync: Boolean = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                loadBondedDevices()
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
        cancelLegacyPeriodicSync()
        refreshNearbySyncRegistration()
        updateStatus(getString(R.string.status_idle))
        maybeRequestInitialBluetoothPermission()
    }

    override fun onResume() {
        super.onResume()
        loadPersistedMeasurements()
        loadBondedDevices(requestPermission = false)
        refreshHealthConnectState()
        refreshNearbySyncRegistration()
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
        val measurementUserLabels = measurementUserOptions.map { user ->
            user?.let { getString(R.string.measurement_user_single, it) }
                ?: getString(R.string.measurement_user_all)
        }
        val deviceLabels = if (hasBluetoothPermission()) {
            bondedDevices.map { device ->
                val displayName = device.name ?: getString(R.string.device_name_placeholder)
                "$displayName (${device.address})"
            }
        } else {
            emptyList()
        }
        val selectedDeviceIndex = if (hasBluetoothPermission()) {
            bondedDevices.indexOfFirst { it.address == selectedDeviceAddress() }
        } else {
            -1
        }
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
            measurementUserOptions = measurementUserOptions,
            measurementUserLabels = measurementUserLabels,
            selectedMeasurementUser = selectedMeasurementUser,
            selectedMeasurementUserIndex = measurementUserOptions.indexOf(selectedMeasurementUser),
            statusMessage = statusMessage,
            syncLog = lastSyncLog,
            modelLabels = OmronDeviceRegistry.supportedModels.map(::modelLabel),
            selectedModelIndex = OmronDeviceRegistry.supportedModels
                .indexOfFirst { it.id == selectedModel.id },
            deviceLabels = deviceLabels,
            selectedDeviceIndex = selectedDeviceIndex,
            isWorking = isWorking,
            canSync = bondedDevices.isNotEmpty(),
            canExport = measurements.isNotEmpty(),
            canExportLog = lastSyncLog.isNotBlank(),
            healthConnectAvailable = isHealthConnectAvailable,
            healthConnectNeedsSetup = isHealthConnectSetupRequired,
            healthConnectConnected = isHealthConnectConnected,
            healthConnectStatusMessage = healthConnectStatusMessage,
            canOpenHealthConnect = isHealthConnectAvailable || isHealthConnectSetupRequired,
            canExportHealthConnect =
                measurements.isNotEmpty() && isHealthConnectAvailable && isHealthConnectConnected,
            autoExportHealthConnect = syncPreferences.healthConnectAutoExportEnabled(),
            nearbySyncEnabled = nearbySyncEnabled,
            nearbySyncSummary = nearbySyncSummary,
            nearbySyncCooldownLabels = nearbySyncCooldownOptions.map { it.label },
            selectedNearbySyncCooldownIndex =
                nearbySyncCooldownOptions.indexOfFirst { it.minutes == selectedNearbySyncCooldownMinutes },
            selectedTrendRange = syncPreferences.selectedTrendRange(),
            showsMeasurementUserColumn = selectedModel.userCount > 1,
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
        }
    }

    override fun onSyncRequested() {
        startSync()
    }

    override fun onMeasurementUserSelected(user: Int?) {
        syncPreferences.setSelectedMeasurementUser(user)
        loadPersistedMeasurements()
    }

    override fun onTrendRangeSelected(range: TrendRange) {
        syncPreferences.setSelectedTrendRange(range)
        notifyCurrentFragment()
    }

    override fun onBluetoothSettingsRequested() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun onRefreshDevicesRequested() {
        loadBondedDevices(requestPermission = true)
    }

    override fun onExportRequested() {
        exportMeasurements()
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

    override fun onSyncLogRequested() {
        showSyncLog()
    }

    override fun onExportSyncLogRequested() {
        exportSyncLog()
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
            .commit()

        binding.root.post {
            updateTopLevelUi()
            notifyCurrentFragment()
        }
    }

    private fun showSyncLog() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SyncLogFragment())
            .addToBackStack(BACKSTACK_SYNC_LOG)
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
    private fun loadBondedDevices(requestPermission: Boolean = false) {
        if (!ensureBluetoothPermission(requestPermission = requestPermission)) {
            bondedDevices.clear()
            notifyCurrentFragment()
            return
        }

        updateStatus(getString(R.string.status_loading_devices))

        val bluetoothAdapter = bluetoothAdapter()
        if (bluetoothAdapter == null) {
            bondedDevices.clear()
            updateStatus(getString(R.string.status_no_adapter))
            notifyCurrentFragment()
            return
        }

        bondedDevices.clear()
        bondedDevices += bluetoothAdapter.bondedDevices
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .sortedWith(compareBy({ it.name.orEmpty().lowercase() }, { it.address }))

        if (selectedDeviceAddress() == null && bondedDevices.isNotEmpty()) {
            persistSelectedDeviceAddress(bondedDevices.first().address)
        }

        updateStatus(
            if (bondedDevices.isEmpty()) {
                getString(R.string.status_no_devices)
            } else {
                getString(R.string.status_idle)
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
        val device = bondedDevices.firstOrNull { it.address == selectedAddress }
            ?: bondedDevices.firstOrNull()

        if (device == null) {
            updateStatus(getString(R.string.status_no_devices))
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            updateStatus(getString(R.string.status_pair_device))
            return
        }

        persistSelectedDeviceAddress(device.address)
        setWorking(true)
        updateStatus(getString(R.string.status_syncing))

        launchUi {
            runCatching {
                syncOrchestrator.syncSelectedDevice()
            }.onSuccess { result ->
                renderSyncResult(result)
                updateStatus(getString(R.string.status_idle))
                showToast(syncCompletionToast(result))
            }.onFailure { error ->
                if (error is SyncException) {
                    renderSyncLog(error.diagnostics.asText())
                }
                updateStatus(
                    if (error is MissingBluetoothPermissionException) {
                        getString(R.string.status_missing_permission)
                    } else {
                        error.message ?: error.javaClass.simpleName
                    },
                )
            }

            setWorking(false)
            notifyCurrentFragment()
        }
    }

    private fun renderSyncResult(result: SyncExecutionResult) {
        measurements = result.persistedMeasurements
        renderSyncLog(result.syncLog)
    }

    private fun loadPersistedMeasurements() {
        launchUi {
            measurements = withContext(Dispatchers.IO) {
                measurementStore.loadAll(selectedMeasurementUser())
            }
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
        if (measurements.isEmpty()) {
            showToast(getString(R.string.empty_measurements))
            return
        }

        setWorking(true)
        updateStatus(getString(R.string.status_health_connect_exporting))

        launchUi {
            runCatching {
                syncOrchestrator.exportStoredMeasurementsToHealthConnect()
            }.onSuccess { summary ->
                updateStatus(getString(R.string.health_connect_status_connected))
                showToast(
                    getString(
                        R.string.status_health_connect_exported,
                        summary.bloodPressureExported,
                        summary.heartRateExported,
                    ),
                )
            }.onFailure { error ->
                updateStatus(error.message ?: error.javaClass.simpleName)
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

    private fun completeExport(uri: Uri) {
        updateStatus(getString(R.string.status_exporting))

        launchUi {
            runCatching {
                val storedMeasurements = withContext(Dispatchers.IO) {
                    measurementStore.loadAll(selectedMeasurementUser())
                }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        csvExporter.export(outputStream, storedMeasurements)
                    } ?: error("Could not open the selected destination for writing.")
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_exported))
            }.onFailure { error ->
                updateStatus(error.message ?: error.javaClass.simpleName)
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
                    } ?: error("Could not open the selected destination for writing.")
                }
            }.onSuccess {
                updateStatus(getString(R.string.status_log_exported))
            }.onFailure { error ->
                updateStatus(error.message ?: error.javaClass.simpleName)
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
            is SyncLogFragment -> fragment.render(currentUiState())
        }
    }

    private fun renderSyncLog(log: String) {
        lastSyncLog = log
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

    private fun persistSelectedDeviceAddress(address: String) {
        syncPreferences.setSelectedDeviceAddress(address)
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

    private fun cancelLegacyPeriodicSync() {
        WorkManager.getInstance(this).cancelUniqueWork(LEGACY_PERIODIC_SYNC_WORK_NAME)
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

    private fun selectedModel(): OmronDeviceDefinition {
        return syncPreferences.selectedModel()
    }

    private fun modelLabel(model: OmronDeviceDefinition): String {
        val status = when (model.verificationLevel) {
            VerificationLevel.VERIFIED -> getString(R.string.model_support_verified)
            VerificationLevel.EXPERIMENTAL -> getString(R.string.model_support_experimental)
        }
        return "${model.modelCode} - $status"
    }

    private fun formatSyncTimestamp(timestampMillis: Long): String {
        return SYNC_TIME_FORMATTER.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()),
        )
    }

    private fun syncCompletionToast(result: SyncExecutionResult): String {
        val inserted = result.inserted
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

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun launchUi(block: suspend CoroutineScope.() -> Unit) {
        lifecycleScope.launch(block = block)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateTopLevelUi() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isLogScreen = currentFragment is SyncLogFragment

        binding.screenTitle.text = when {
            isLogScreen -> getString(R.string.sync_log_title)
            selectedTabId == R.id.navigation_trends -> getString(R.string.trends_title)
            selectedTabId == R.id.navigation_settings -> getString(R.string.settings_title)
            else -> getString(R.string.results_title)
        }
        binding.toolbar.navigationIcon =
            if (isLogScreen) {
                AppCompatResources.getDrawable(
                    this,
                    androidx.appcompat.R.drawable.abc_ic_ab_back_material,
                )
            } else {
                null
            }
        binding.bottomNavigation.visibility = if (isLogScreen) View.GONE else View.VISIBLE
    }
    private data class NearbySyncCooldownOption(
        val minutes: Int,
        val label: String,
    )

    private fun nearbySyncCooldownOptions(): List<NearbySyncCooldownOption> {
        return listOf(
            NearbySyncCooldownOption(0, getString(R.string.nearby_sync_cooldown_immediately)),
            NearbySyncCooldownOption(1, getString(R.string.nearby_sync_cooldown_1_minute)),
            NearbySyncCooldownOption(2, getString(R.string.nearby_sync_cooldown_2_minutes)),
            NearbySyncCooldownOption(3, getString(R.string.nearby_sync_cooldown_3_minutes)),
            NearbySyncCooldownOption(5, getString(R.string.nearby_sync_cooldown_5_minutes)),
            NearbySyncCooldownOption(10, getString(R.string.nearby_sync_cooldown_10_minutes)),
        )
    }

    private companion object {
        const val BACKSTACK_SYNC_LOG = "sync_log"
        const val KEY_SELECTED_TAB = "selected_tab"
        const val LEGACY_PERIODIC_SYNC_WORK_NAME = "background_sync"
        val SYNC_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
