package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.github.thiagokokada.omronsyncer.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onModelSelected(position: Int)
        fun onBluetoothSettingsRequested()
        fun onRefreshDevicesRequested()
        fun onExportRequested()
        fun onHealthConnectRequested()
        fun onHealthConnectExportRequested()
        fun onHealthConnectAutoExportChanged(enabled: Boolean)
        fun onHealthConnectExportUserSelected(position: Int)
        fun onNearbySyncChanged(enabled: Boolean)
        fun onSyncLogRequested()
        fun onDeviceSelected(position: Int)
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var modelAdapter: ArrayAdapter<String>
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var healthConnectUserAdapter: ArrayAdapter<String>
    private var suppressModelSelectionCallback = false
    private var suppressSelectionCallback = false
    private var suppressHealthConnectUserCallback = false
    private var suppressAutoExportCallback = false
    private var suppressNearbySyncCallback = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement SettingsFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modelAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.modelSpinner.adapter = it
        }
        deviceAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.deviceSpinner.adapter = it
        }
        healthConnectUserAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.healthConnectUserSpinner.adapter = it
        }
        binding.modelSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!suppressModelSelectionCallback) {
                host.onModelSelected(position)
            }
        }
        binding.deviceSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!suppressSelectionCallback) {
                host.onDeviceSelected(position)
            }
        }
        binding.healthConnectUserSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!suppressHealthConnectUserCallback) {
                host.onHealthConnectExportUserSelected(position)
            }
        }
        binding.bluetoothSettingsButton.setOnClickListener {
            host.onBluetoothSettingsRequested()
        }
        binding.refreshButton.setOnClickListener {
            host.onRefreshDevicesRequested()
        }
        binding.exportButton.setOnClickListener {
            host.onExportRequested()
        }
        binding.healthConnectActionButton.setOnClickListener {
            host.onHealthConnectRequested()
        }
        binding.healthConnectExportButton.setOnClickListener {
            host.onHealthConnectExportRequested()
        }
        binding.healthConnectAutoExportSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressAutoExportCallback) {
                host.onHealthConnectAutoExportChanged(isChecked)
            }
        }
        binding.nearbySyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!suppressNearbySyncCallback) {
                host.onNearbySyncChanged(isChecked)
            }
        }
        binding.syncLogButton.setOnClickListener {
            host.onSyncLogRequested()
        }
    }

    override fun onResume() {
        super.onResume()
        render(host.currentUiState())
    }

    fun render(state: MainUiState) {
        if (_binding == null) {
            return
        }

        binding.statusValue.text = state.statusMessage

        suppressModelSelectionCallback = true
        modelAdapter.clear()
        modelAdapter.addAll(state.modelLabels)
        modelAdapter.notifyDataSetChanged()
        binding.modelSpinner.isEnabled = state.modelLabels.isNotEmpty() && !state.isWorking
        if (state.selectedModelIndex >= 0 && state.selectedModelIndex < state.modelLabels.size) {
            binding.modelSpinner.setSelection(state.selectedModelIndex)
        }
        suppressModelSelectionCallback = false

        suppressSelectionCallback = true
        deviceAdapter.clear()
        deviceAdapter.addAll(state.deviceLabels)
        deviceAdapter.notifyDataSetChanged()
        binding.deviceSpinner.isEnabled = state.deviceLabels.isNotEmpty() && !state.isWorking
        if (state.selectedDeviceIndex >= 0 && state.selectedDeviceIndex < state.deviceLabels.size) {
            binding.deviceSpinner.setSelection(state.selectedDeviceIndex)
        }
        suppressSelectionCallback = false

        binding.refreshButton.isEnabled = !state.isWorking
        binding.bluetoothSettingsButton.isEnabled = !state.isWorking
        binding.exportButton.isEnabled = state.canExport && !state.isWorking
        binding.healthConnectStatusValue.text = state.healthConnectStatusMessage
        binding.healthConnectActionButton.isEnabled = state.canOpenHealthConnect && !state.isWorking
        binding.healthConnectActionButton.text = when {
            state.healthConnectNeedsSetup -> getString(R.string.open_health_connect)
            state.healthConnectConnected -> getString(R.string.manage_health_connect)
            else -> getString(R.string.grant_health_connect_access)
        }
        binding.healthConnectExportButton.isEnabled = state.canExportHealthConnect && !state.isWorking
        suppressHealthConnectUserCallback = true
        healthConnectUserAdapter.clear()
        healthConnectUserAdapter.addAll(state.healthConnectExportUserLabels)
        healthConnectUserAdapter.notifyDataSetChanged()
        binding.healthConnectUserSpinner.isEnabled =
            state.healthConnectExportUserLabels.size > 1 && !state.isWorking
        if (
            state.selectedHealthConnectExportUserIndex >= 0 &&
            state.selectedHealthConnectExportUserIndex < state.healthConnectExportUserLabels.size
        ) {
            binding.healthConnectUserSpinner.setSelection(state.selectedHealthConnectExportUserIndex)
        }
        suppressHealthConnectUserCallback = false
        suppressAutoExportCallback = true
        binding.healthConnectAutoExportSwitch.isChecked = state.autoExportHealthConnect
        suppressAutoExportCallback = false
        binding.healthConnectAutoExportSwitch.isEnabled = !state.isWorking
        binding.nearbySyncSummaryValue.text = state.nearbySyncSummary
        suppressNearbySyncCallback = true
        binding.nearbySyncSwitch.isChecked = state.nearbySyncEnabled
        suppressNearbySyncCallback = false
        binding.nearbySyncSwitch.isEnabled = !state.isWorking
        binding.syncLogButton.isEnabled = !state.isWorking
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
