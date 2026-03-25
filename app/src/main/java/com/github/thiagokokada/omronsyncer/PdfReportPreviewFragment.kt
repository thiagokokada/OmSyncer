package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.thiagokokada.omronsyncer.databinding.FragmentPdfReportPreviewBinding
import com.github.thiagokokada.omronsyncer.export.MeasurementPdfExporter
import com.github.thiagokokada.omronsyncer.export.MeasurementPdfReportBuilder
import com.github.thiagokokada.omronsyncer.model.Measurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
    private val pdfExporter = MeasurementPdfExporter()
    private lateinit var previewAdapter: PdfPreviewPageAdapter
    private var previewJob: Job? = null
    private var lastPreviewRequest: PreviewRequest? = null

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
        previewAdapter = PdfPreviewPageAdapter()
        binding.pdfPreviewList.layoutManager = LinearLayoutManager(requireContext())
        binding.pdfPreviewList.adapter = previewAdapter
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
        binding.pdfRangeToggle.check(
            when (state.selectedTrendRange) {
                TrendRange.SEVEN_DAYS -> R.id.pdf_range_seven_days
                TrendRange.THIRTY_DAYS -> R.id.pdf_range_thirty_days
                TrendRange.ALL -> R.id.pdf_range_all
            },
        )
        binding.pdfSelectionSummary.text = selectionSummary(state)
        binding.exportPdfButton.isEnabled = state.canExport && !state.isWorking

        val measurements = state.resultsMeasurements.map(MeasurementListItem::displayMeasurement)
        binding.pdfEmptyState.isVisible = measurements.isEmpty()
        binding.pdfPreviewList.isVisible = measurements.isNotEmpty()

        val request = PreviewRequest(
            measurements = measurements,
            range = state.selectedTrendRange,
            selectedUser = state.selectedMeasurementUser,
            classificationScheme = state.selectedBloodPressureClassificationScheme,
        )
        if (measurements.isEmpty()) {
            lastPreviewRequest = null
            previewJob?.cancel()
            previewAdapter.submitPages(emptyList())
            binding.pdfPreviewStatus.text = getString(R.string.pdf_report_empty)
            return
        }
        if (request == lastPreviewRequest) {
            return
        }
        lastPreviewRequest = request
        binding.pdfPreviewStatus.text = getString(R.string.pdf_report_preview_rendering)
        renderPdfPreview(request)
    }

    override fun onDestroyView() {
        previewJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun renderPdfPreview(request: PreviewRequest) {
        previewJob?.cancel()
        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val pages = withContext(Dispatchers.IO) {
                val report = reportBuilder.build(
                    measurements = request.measurements,
                    range = request.range,
                    selectedUser = request.selectedUser,
                    classificationScheme = request.classificationScheme,
                )
                val previewFile = File(requireContext().cacheDir, "pdf-preview.pdf")
                FileOutputStream(previewFile).use { outputStream ->
                    pdfExporter.export(outputStream, report)
                }
                renderPdfPages(previewFile)
            }
            if (_binding == null || request != lastPreviewRequest) {
                return@launch
            }
            previewAdapter.submitPages(pages)
            binding.pdfPreviewStatus.text = resources.getQuantityString(
                R.plurals.pdf_report_preview_pages,
                pages.size,
                pages.size,
            )
        }
    }

    private fun renderPdfPages(file: File): List<Bitmap> {
        val parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return parcelFileDescriptor.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val width = page.width * PREVIEW_SCALE
                        val height = page.height * PREVIEW_SCALE
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }
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

    private data class PreviewRequest(
        val measurements: List<Measurement>,
        val range: TrendRange,
        val selectedUser: Int?,
        val classificationScheme: BloodPressureClassificationScheme,
    )

    private companion object {
        const val PREVIEW_SCALE = 2
    }
}
