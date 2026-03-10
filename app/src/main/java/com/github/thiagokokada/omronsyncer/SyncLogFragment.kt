package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.thiagokokada.omronsyncer.databinding.FragmentSyncLogBinding

class SyncLogFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onExportSyncLogRequested()
        fun onExportSyncCaptureRequested()
    }

    private var _binding: FragmentSyncLogBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement SyncLogFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSyncLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.exportLogButton.setOnClickListener {
            host.onExportSyncLogRequested()
        }
        binding.exportCaptureButton.setOnClickListener {
            host.onExportSyncCaptureRequested()
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

        binding.exportLogButton.isEnabled = state.canExportLog && !state.isWorking
        binding.exportCaptureButton.isEnabled = state.canExportCapture && !state.isWorking
        binding.syncLogText.text = state.syncLog.ifBlank {
            getString(R.string.sync_log_empty)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
