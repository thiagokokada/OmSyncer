package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.thiagokokada.omronsyncer.databinding.FragmentDeletedMeasurementsBinding

class DeletedMeasurementsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onDeletedMeasurementRestoreRequested(measurement: MeasurementListItem)
    }

    private var _binding: FragmentDeletedMeasurementsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var adapter: DeletedMeasurementAdapter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement DeletedMeasurementsFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDeletedMeasurementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = DeletedMeasurementAdapter(host::onDeletedMeasurementRestoreRequested)
        binding.deletedMeasurementsList.layoutManager = LinearLayoutManager(requireContext())
        binding.deletedMeasurementsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        render(host.currentUiState())
    }

    fun render(state: MainUiState) {
        if (_binding == null) {
            return
        }

        adapter.submitList(state.deletedMeasurements)
        binding.emptyState.visibility = if (state.deletedMeasurements.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
