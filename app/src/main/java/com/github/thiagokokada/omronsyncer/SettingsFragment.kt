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
        fun onBluetoothSettingsRequested()
        fun onRefreshDevicesRequested()
        fun onExportRequested()
        fun onSyncLogRequested()
        fun onDeviceSelected(position: Int)
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var suppressSelectionCallback = false

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

        deviceAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.deviceSpinner.adapter = it
        }

        binding.deviceSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!suppressSelectionCallback) {
                host.onDeviceSelected(position)
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
        binding.syncLogButton.isEnabled = !state.isWorking
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
