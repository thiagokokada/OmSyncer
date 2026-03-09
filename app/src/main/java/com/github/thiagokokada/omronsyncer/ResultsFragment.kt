package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.thiagokokada.omronsyncer.databinding.FragmentResultsBinding

class ResultsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onSyncRequested()
    }

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var adapter: MeasurementAdapter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement ResultsFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MeasurementAdapter()
        binding.measurementsList.layoutManager = LinearLayoutManager(requireContext())
        binding.measurementsList.adapter = adapter

        binding.syncButton.setOnClickListener {
            host.onSyncRequested()
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

        adapter.setShowUserColumn(state.showsMeasurementUserColumn)
        adapter.submitList(state.measurements)

        binding.syncButton.isEnabled = state.canSync && !state.isWorking
        binding.syncButton.text = if (state.isWorking) {
            getString(R.string.syncing_button_label)
        } else {
            getString(R.string.sync_now)
        }

        binding.measurementCount.text = resources.getQuantityString(
            R.plurals.measurement_count,
            state.measurements.size,
            state.measurements.size,
        )

        val latestMeasurement = state.measurements.firstOrNull()
        binding.latestMeasurement.text = latestMeasurement?.let {
            getString(
                R.string.latest_measurement_summary,
                adapter.formatTimestamp(it.recordedAt),
                it.systolic,
                it.diastolic,
                it.pulse,
            )
        } ?: if (state.selectedMeasurementUser != null && state.measurementUserOptions.size > 1) {
            getString(R.string.no_measurement_summary_filtered)
        } else {
            getString(R.string.no_measurement_summary)
        }

        binding.emptyState.visibility =
            if (state.measurements.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.text = if (
            state.selectedMeasurementUser != null && state.measurementUserOptions.size > 1
        ) {
            getString(R.string.empty_measurements_filtered)
        } else {
            getString(R.string.empty_measurements)
        }
        binding.userColumnLabel.visibility =
            if (state.showsMeasurementUserColumn) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
