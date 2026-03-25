package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.github.thiagokokada.omronsyncer.databinding.FragmentTrendsBinding
import java.time.format.DateTimeFormatter

class TrendsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onTrendRangeSelected(range: TrendRange)
    }

    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private var selectedBucket: TrendBucket? = null
    private var lastRenderedRange: TrendRange? = null

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

        binding.rangeSevenDays.setOnClickListener {
            binding.chartView.resetZoom()
            host.onTrendRangeSelected(TrendRange.SEVEN_DAYS)
        }
        binding.rangeThirtyDays.setOnClickListener {
            binding.chartView.resetZoom()
            host.onTrendRangeSelected(TrendRange.THIRTY_DAYS)
        }
        binding.rangeAll.setOnClickListener {
            binding.chartView.resetZoom()
            host.onTrendRangeSelected(TrendRange.ALL)
        }
        binding.chartView.onSelectionChanged = { bucket ->
            selectedBucket = bucket
            renderSelectedBucket()
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

        if (state.selectedTrendRange != lastRenderedRange) {
            binding.chartView.resetZoom()
        }
        lastRenderedRange = state.selectedTrendRange

        binding.rangeToggle.check(
            when (state.selectedTrendRange) {
                TrendRange.SEVEN_DAYS -> R.id.range_seven_days
                TrendRange.THIRTY_DAYS -> R.id.range_thirty_days
                TrendRange.ALL -> R.id.range_all
            },
        )

        val filteredMeasurements = TrendChartData.filterMeasurements(
            measurements = state.trendMeasurements,
            selectedUser = null,
            selectedRange = state.selectedTrendRange,
        )
        val buckets = TrendChartData.chartBuckets(filteredMeasurements)
        if (selectedBucket !in buckets) {
            selectedBucket = null
        }

        binding.chartView.setBuckets(
            buckets = buckets,
            selectedBucket = selectedBucket,
            classificationScheme = state.selectedBloodPressureClassificationScheme,
        )
        renderSelectedBucket()
        binding.emptyState.isVisible = buckets.isEmpty()
        binding.chartCard.isVisible = buckets.isNotEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderSelectedBucket() {
        val bucket = selectedBucket
        binding.selectedReadingCard.isVisible = bucket != null
        if (bucket == null) {
            return
        }

        binding.selectedReadingTime.text = SELECTED_READING_DATE_FORMATTER.format(bucket.date)
        binding.selectedReadingSummary.text = getString(
            R.string.trends_selected_reading_summary,
            bucket.meanSystolic,
            bucket.meanDiastolic,
            bucket.meanPulse,
            bucket.measurements.size,
        )
        binding.selectedReadingValues.text = bucket.measurements.joinToString(separator = "\n") { measurement ->
            getString(
                R.string.trends_selected_reading_value,
                SELECTED_READING_VALUE_TIME_FORMATTER.format(measurement.recordedAt),
                measurement.systolic,
                measurement.diastolic,
                measurement.pulse,
                measurement.flagsLabel(),
            )
        }
    }

    private companion object {
        val SELECTED_READING_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val SELECTED_READING_VALUE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm")
    }
}
