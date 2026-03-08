package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.github.thiagokokada.omronsyncer.databinding.FragmentTrendsBinding
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TrendsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
    }

    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var userAdapter: ArrayAdapter<String>
    private var suppressUserSelection = false
    private var selectedUser: Int? = null
    private var selectedRange: TrendRange = TrendRange.THIRTY_DAYS
    private var selectedMeasurement: Measurement? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host ?: error("MainActivity must implement TrendsFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        userAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>(),
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.userSpinner.adapter = it
        }

        binding.rangeSevenDays.setOnClickListener {
            selectedRange = TrendRange.SEVEN_DAYS
            render(host.currentUiState())
        }
        binding.rangeThirtyDays.setOnClickListener {
            selectedRange = TrendRange.THIRTY_DAYS
            render(host.currentUiState())
        }
        binding.rangeAll.setOnClickListener {
            selectedRange = TrendRange.ALL
            render(host.currentUiState())
        }
        binding.userSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!suppressUserSelection) {
                selectedUser = TrendChartData.userOptions(host.currentUiState().measurements).getOrNull(position)
                render(host.currentUiState())
            }
        }
        binding.chartView.onSelectionChanged = { measurement ->
            selectedMeasurement = measurement
            renderSelectedMeasurement()
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

        val allUserOptions = TrendChartData.userOptions(state.measurements)
        if (selectedUser != null && selectedUser !in allUserOptions) {
            selectedUser = null
        }

        suppressUserSelection = true
        val userLabels = allUserOptions.map { user ->
            user?.let { getString(R.string.health_connect_export_user_single, it) }
                ?: getString(R.string.health_connect_export_user_all)
        }
        userAdapter.clear()
        userAdapter.addAll(userLabels)
        userAdapter.notifyDataSetChanged()
        binding.userFilterLabel.isVisible = userLabels.size > 1
        binding.userSpinner.isVisible = userLabels.size > 1
        binding.userSpinner.isEnabled = userLabels.size > 1
        binding.userSpinner.setSelection(allUserOptions.indexOf(selectedUser).coerceAtLeast(0))
        suppressUserSelection = false

        binding.rangeToggle.check(
            when (selectedRange) {
                TrendRange.SEVEN_DAYS -> R.id.range_seven_days
                TrendRange.THIRTY_DAYS -> R.id.range_thirty_days
                TrendRange.ALL -> R.id.range_all
            },
        )

        val filteredMeasurements = TrendChartData.filterMeasurements(
            measurements = state.measurements,
            selectedUser = selectedUser,
            selectedRange = selectedRange,
        )
        if (selectedMeasurement !in filteredMeasurements) {
            selectedMeasurement = filteredMeasurements.lastOrNull()
        }

        binding.chartView.setMeasurements(filteredMeasurements, selectedMeasurement)
        renderSelectedMeasurement()
        binding.emptyState.isVisible = filteredMeasurements.isEmpty()
        binding.chartCard.isVisible = filteredMeasurements.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderSelectedMeasurement() {
        val measurement = selectedMeasurement
        binding.selectedReadingCard.isVisible = measurement != null
        if (measurement == null) {
            return
        }

        binding.selectedReadingTime.text = SELECTED_READING_TIME_FORMATTER.format(
            measurement.recordedAt.atZone(ZoneId.systemDefault()),
        )
        binding.selectedReadingSummary.text = getString(
            R.string.trends_selected_reading_summary,
            measurement.systolic,
            measurement.diastolic,
            measurement.pulse,
            measurement.flagsLabel(),
        )
    }

    private companion object {
        val SELECTED_READING_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
