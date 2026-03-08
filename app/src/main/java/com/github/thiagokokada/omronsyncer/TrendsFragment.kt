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
import java.time.LocalDateTime

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
                selectedUser = userOptions(host.currentUiState().measurements).getOrNull(position)
                render(host.currentUiState())
            }
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

        val allUserOptions = userOptions(state.measurements)
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

        val filteredMeasurements = state.measurements
            .filter { measurement -> selectedUser == null || measurement.user == selectedUser }
            .filter { measurement -> selectedRange.includes(measurement.recordedAt) }

        binding.chartView.setMeasurements(filteredMeasurements)
        binding.emptyState.isVisible = filteredMeasurements.isEmpty()
        binding.chartCard.isVisible = filteredMeasurements.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun userOptions(measurements: List<Measurement>): List<Int?> {
        val distinctUsers = measurements.map { it.user }.distinct().sorted()
        return if (distinctUsers.size <= 1) {
            distinctUsers.map { it as Int? }.ifEmpty { listOf(null) }
        } else {
            listOf(null) + distinctUsers.map { it as Int? }
        }
    }

    private enum class TrendRange {
        SEVEN_DAYS,
        THIRTY_DAYS,
        ALL,
        ;

        fun includes(recordedAt: LocalDateTime): Boolean {
            val now = LocalDateTime.now()
            return when (this) {
                SEVEN_DAYS -> !recordedAt.isBefore(now.minusDays(7))
                THIRTY_DAYS -> !recordedAt.isBefore(now.minusDays(30))
                ALL -> true
            }
        }
    }
}
