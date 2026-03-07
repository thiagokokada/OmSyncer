package com.github.thiagokokada.omronsyncer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.thiagokokada.omronsyncer.data.MeasurementStore
import com.github.thiagokokada.omronsyncer.databinding.ActivityMainBinding
import com.github.thiagokokada.omronsyncer.model.Measurement
import com.github.thiagokokada.omronsyncer.omron.Hem7380T1SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var measurementsAdapter: MeasurementAdapter
    private lateinit var deviceNameAdapter: ArrayAdapter<String>
    private lateinit var preferences: SharedPreferences

    private val bondedDevices = mutableListOf<BluetoothDevice>()
    private val syncClient by lazy { Hem7380T1SyncClient(this) }
    private val measurementStore by lazy { MeasurementStore(this) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                loadBondedDevices()
            } else {
                showStatus(getString(R.string.permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)

        measurementsAdapter = MeasurementAdapter()
        binding.measurementsList.layoutManager = LinearLayoutManager(this)
        binding.measurementsList.adapter = measurementsAdapter

        deviceNameAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.deviceSpinner.adapter = it
        }
        binding.deviceSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            bondedDevices.getOrNull(position)?.address?.let(::persistSelectedDeviceAddress)
        }

        binding.bluetoothSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        binding.refreshButton.setOnClickListener {
            loadBondedDevices()
        }
        binding.syncButton.setOnClickListener {
            startSync()
        }

        loadPersistedMeasurements()
        showStatus(getString(R.string.status_idle))
    }

    override fun onResume() {
        super.onResume()
        loadBondedDevices()
    }

    private fun loadBondedDevices() {
        if (!ensureBluetoothPermission()) {
            return
        }

        showStatus(getString(R.string.status_loading_devices))

        val bluetoothAdapter = bluetoothAdapter()
        if (bluetoothAdapter == null) {
            showStatus(getString(R.string.status_no_adapter))
            updateBondedDevices(emptyList())
            return
        }

        val devices = bluetoothAdapter.bondedDevices
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }
            .sortedWith(
                compareBy(
                    { it.name.orEmpty().lowercase() },
                    { it.address },
                ),
            )

        updateBondedDevices(devices)

        val status = when {
            devices.isEmpty() -> getString(R.string.status_no_devices)
            else -> getString(R.string.status_idle)
        }
        showStatus(status)
    }

    private fun updateBondedDevices(devices: List<BluetoothDevice>) {
        bondedDevices.clear()
        bondedDevices += devices

        deviceNameAdapter.clear()
        deviceNameAdapter.addAll(
            devices.map { device ->
                val displayName = device.name ?: getString(R.string.device_name_placeholder)
                "$displayName (${device.address})"
            },
        )
        deviceNameAdapter.notifyDataSetChanged()

        val hasDevices = devices.isNotEmpty()
        binding.deviceSpinner.isEnabled = hasDevices
        binding.syncButton.isEnabled = hasDevices

        val savedAddress = selectedDeviceAddress()
        val selectedIndex = devices.indexOfFirst { it.address == savedAddress }
        if (selectedIndex >= 0) {
            binding.deviceSpinner.setSelection(selectedIndex)
        } else if (hasDevices) {
            binding.deviceSpinner.setSelection(0)
            persistSelectedDeviceAddress(devices.first().address)
        }
    }

    private fun startSync() {
        if (!ensureBluetoothPermission()) {
            return
        }

        val device = bondedDevices.getOrNull(binding.deviceSpinner.selectedItemPosition)
        if (device == null) {
            showStatus(getString(R.string.status_no_devices))
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            showStatus(getString(R.string.status_pair_device))
            return
        }

        setSyncing(true)
        showStatus(getString(R.string.status_syncing))

        lifecycleScope.launch {
            runCatching {
                val measurements = syncClient.sync(device)
                val saveSummary = withContext(Dispatchers.IO) {
                    measurementStore.saveAll(measurements)
                }
                val persistedMeasurements = withContext(Dispatchers.IO) {
                    measurementStore.loadAll()
                }
                SyncResult(
                    measurements = persistedMeasurements,
                    saveSummary = saveSummary,
                )
            }.onSuccess { result ->
                renderMeasurements(result.measurements)
                showStatus(
                    getString(
                        R.string.status_imported_summary,
                        result.saveSummary.imported,
                        result.saveSummary.inserted,
                        result.saveSummary.duplicates,
                    ),
                )
            }.onFailure { error ->
                showStatus(error.message ?: error.javaClass.simpleName)
            }

            setSyncing(false)
        }
    }

    private fun renderMeasurements(measurements: List<Measurement>) {
        measurementsAdapter.submitList(measurements)
        binding.emptyState.text = getString(R.string.status_results_empty)
        binding.emptyState.visibility =
            if (measurements.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun loadPersistedMeasurements() {
        lifecycleScope.launch {
            val measurements = withContext(Dispatchers.IO) {
                measurementStore.loadAll()
            }
            renderMeasurements(measurements)
        }
    }

    private fun setSyncing(syncing: Boolean) {
        binding.progressIndicator.isIndeterminate = syncing
        binding.progressIndicator.visibility =
            if (syncing) android.view.View.VISIBLE else android.view.View.GONE
        binding.syncButton.isEnabled = !syncing && bondedDevices.isNotEmpty()
        binding.refreshButton.isEnabled = !syncing
        binding.bluetoothSettingsButton.isEnabled = !syncing
        binding.deviceSpinner.isEnabled = !syncing && bondedDevices.isNotEmpty()
    }

    private fun ensureBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            showStatus(getString(R.string.status_missing_permission))
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }

        return granted
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
    }

    private fun persistSelectedDeviceAddress(address: String) {
        preferences.edit {
            putString(PREF_SELECTED_DEVICE_ADDRESS, address)
        }
    }

    private fun selectedDeviceAddress(): String? {
        return preferences.getString(PREF_SELECTED_DEVICE_ADDRESS, null)
    }

    private data class SyncResult(
        val measurements: List<Measurement>,
        val saveSummary: MeasurementStore.SaveSummary,
    )

    private companion object {
        const val PREFERENCES_NAME = "om_syncer_prefs"
        const val PREF_SELECTED_DEVICE_ADDRESS = "selected_device_address"
    }
}
