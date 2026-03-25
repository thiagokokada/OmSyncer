package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.github.thiagokokada.omronsyncer.databinding.FragmentPdfReportPreviewBinding
import com.github.thiagokokada.omronsyncer.export.MeasurementPdfReportBuilder
import java.time.format.DateTimeFormatter

class PdfReportPreviewFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onPdfReportRangeSelected(range: TrendRange)
        fun onPdfReportExportRequested()
    }

    private var _binding: FragmentPdfReportPreviewBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private val reportBuilder = MeasurementPdfReportBuilder()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement PdfReportPreviewFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPdfReportPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.pdfRangeSevenDays.setOnClickListener {
            host.onPdfReportRangeSelected(TrendRange.SEVEN_DAYS)
        }
        binding.pdfRangeThirtyDays.setOnClickListener {
            host.onPdfReportRangeSelected(TrendRange.THIRTY_DAYS)
        }
        binding.pdfRangeAll.setOnClickListener {
            host.onPdfReportRangeSelected(TrendRange.ALL)
        }
        binding.exportPdfButton.setOnClickListener {
            host.onPdfReportExportRequested()
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
        val report = reportBuilder.build(
            measurements = state.resultsMeasurements.map(MeasurementListItem::displayMeasurement),
            range = state.selectedTrendRange,
            selectedUser = state.selectedMeasurementUser,
        )

        binding.pdfRangeToggle.check(
            when (state.selectedTrendRange) {
                TrendRange.SEVEN_DAYS -> R.id.pdf_range_seven_days
                TrendRange.THIRTY_DAYS -> R.id.pdf_range_thirty_days
                TrendRange.ALL -> R.id.pdf_range_all
            },
        )
        binding.pdfMeasurementCount.text = resources.getQuantityString(
            R.plurals.measurement_count,
            report.summary.measurementCount,
            report.summary.measurementCount,
        )
        binding.pdfSelectionSummary.text = selectionSummary(state)
        binding.pdfLatestMeasurement.text = report.summary.lastRecordedAt?.let {
            getString(R.string.pdf_report_latest_measurement, TIMESTAMP_FORMATTER.format(it))
        } ?: getString(R.string.no_measurement_summary_filtered)
        binding.pdfAveragePressure.text =
            "${report.summary.averageSystolic}/${report.summary.averageDiastolic}"
        binding.pdfAveragePulse.text = "${report.summary.averagePulse} bpm"
        binding.pdfDateSpan.text = report.summary.firstRecordedAt?.let { first ->
            report.summary.lastRecordedAt?.let { last ->
                getString(
                    R.string.pdf_report_date_span_value,
                    TIMESTAMP_FORMATTER.format(first),
                    TIMESTAMP_FORMATTER.format(last),
                )
            }
        } ?: "-"
        binding.pdfFlaggedReadings.text = getString(
            R.string.pdf_report_flagged_value,
            report.summary.irregularHeartbeatCount,
            report.summary.movementCount,
        )

        val previewLines = report.measurements.take(PREVIEW_ROW_LIMIT).joinToString("\n") { measurement ->
            buildString {
                append(TIMESTAMP_FORMATTER.format(measurement.recordedAt))
                append(" - ")
                append("${measurement.systolic}/${measurement.diastolic}")
                append(" - pulse ${measurement.pulse}")
                append(" - flags ${measurement.flagsLabel()}")
                if (state.showsMeasurementUserColumn) {
                    append(" - user ${measurement.user}")
                }
            }
        }
        val previewText = if (report.measurements.size > PREVIEW_ROW_LIMIT) {
            "$previewLines\n${getString(R.string.pdf_report_preview_more_measurements, report.measurements.size - PREVIEW_ROW_LIMIT)}"
        } else {
            previewLines
        }
        binding.pdfPreviewValues.text = previewText
        binding.pdfPreviewValues.visibility = if (report.measurements.isEmpty()) View.GONE else View.VISIBLE
        binding.pdfEmptyState.visibility = if (report.measurements.isEmpty()) View.VISIBLE else View.GONE
        binding.exportPdfButton.isEnabled = state.canExport && !state.isWorking
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun selectionSummary(state: MainUiState): String {
        val rangeLabel = when (state.selectedTrendRange) {
            TrendRange.SEVEN_DAYS -> "Last 7 days"
            TrendRange.THIRTY_DAYS -> "Last 30 days"
            TrendRange.ALL -> "All data"
        }
        val selectedUserLabel = state.selectedMeasurementUser?.let {
            getString(R.string.measurement_user_single, it)
        }
        return if (selectedUserLabel == null) {
            getString(R.string.pdf_report_selection_summary_all_users, rangeLabel)
        } else {
            getString(R.string.pdf_report_selection_summary, rangeLabel, selectedUserLabel)
        }
    }

    private companion object {
        const val PREVIEW_ROW_LIMIT = 12
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
