package com.github.thiagokokada.omronsyncer.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.github.thiagokokada.omronsyncer.BloodPressureClassifier
import com.github.thiagokokada.omronsyncer.BloodPressureClassificationScheme
import com.github.thiagokokada.omronsyncer.R
import com.github.thiagokokada.omronsyncer.TrendRange
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

class MeasurementPdfExporter(
    private val context: Context,
) {

    fun export(
        outputStream: OutputStream,
        report: MeasurementPdfReport,
    ) {
        val document = PdfDocument()
        try {
            val detailPages = paginateDetailRows(report)
            val totalPages = 3 + detailPages.size
            drawPage(document, 1, totalPages) { canvas ->
                drawSummaryPage(canvas, report, 1, totalPages)
            }
            drawPage(document, 2, totalPages) { canvas ->
                drawTrendPage(canvas, report, 2, totalPages)
            }
            drawPage(document, 3, totalPages) { canvas ->
                drawInsightsPage(canvas, report, 3, totalPages)
            }
            detailPages.forEachIndexed { index, pageRows ->
                val pageNumber = 4 + index
                drawPage(document, pageNumber, totalPages) { canvas ->
                    drawMeasurementTablePage(canvas, report, pageRows, pageNumber, totalPages)
                }
            }
            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }

    fun suggestedFileName(
        now: LocalDateTime = LocalDateTime.now(),
        prefix: String = context.getString(R.string.pdf_report_file_prefix),
        extension: String = "pdf",
    ): String {
        return "$prefix-${FILE_NAME_FORMATTER.format(now)}.$extension"
    }

    private fun drawPage(
        document: PdfDocument,
        pageNumber: Int,
        totalPages: Int,
        drawContent: (Canvas) -> Unit,
    ) {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        val page = document.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        drawContent(page.canvas)
        drawFooter(page.canvas, pageNumber, totalPages)
        document.finishPage(page)
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, totalPages: Int) {
        canvas.drawText(
            context.getString(R.string.pdf_report_page_format, pageNumber, totalPages),
            MARGIN_HORIZONTAL.toFloat(),
            (PAGE_HEIGHT - MARGIN_BOTTOM).toFloat(),
            footerPaint,
        )
    }

    private fun drawSummaryPage(
        canvas: Canvas,
        report: MeasurementPdfReport,
        pageNumber: Int,
        totalPages: Int,
    ) {
        val contentTop = 72f
        canvas.drawText(context.getString(R.string.pdf_report_overview_title), MARGIN_HORIZONTAL.toFloat(), contentTop, sectionPaint)
        canvas.drawText(
            "${rangeLabel(report.range)} - ${userLabel(report.selectedUser)}",
            MARGIN_HORIZONTAL.toFloat(),
            contentTop + 20f,
            bodyPaint,
        )
        canvas.drawText(
            context.getString(R.string.pdf_report_generated_format, TIMESTAMP_FORMATTER.format(report.generatedAt)),
            MARGIN_HORIZONTAL.toFloat(),
            contentTop + 36f,
            bodyPaint,
        )

        val cardsTop = contentTop + 56f
        drawMetricCard(
            canvas = canvas,
            rect = RectF(40f, cardsTop, 290f, cardsTop + 86f),
            label = context.getString(R.string.pdf_report_measurements_label),
            value = report.summary.measurementCount.toString(),
            accentPaint = accentRedPaint,
        )
        drawMetricCard(
            canvas = canvas,
            rect = RectF(305f, cardsTop, 555f, cardsTop + 86f),
            label = context.getString(R.string.pdf_report_average_bp_label),
            value = "${report.summary.averageSystolic}/${report.summary.averageDiastolic}",
            accentPaint = accentBluePaint,
        )
        drawMetricCard(
            canvas = canvas,
            rect = RectF(40f, cardsTop + 100f, 290f, cardsTop + 186f),
            label = context.getString(R.string.pdf_report_average_pulse_label),
            value = report.summary.averagePulse.toString(),
            accentPaint = accentGreenPaint,
        )
        drawMetricCard(
            canvas = canvas,
            rect = RectF(305f, cardsTop + 100f, 555f, cardsTop + 186f),
            label = context.getString(R.string.pdf_report_days_label),
            value = report.dailyAverages.size.toString(),
            accentPaint = accentOrangePaint,
        )

        val summaryTop = cardsTop + 208f
        drawPanel(
            canvas,
            RectF(40f, summaryTop, 285f, summaryTop + 170f),
            context.getString(R.string.pdf_report_selection_summary_title),
        )
        val latestMeasurement = report.summary.latestMeasurement
        drawPanelLines(
            canvas = canvas,
            startX = 58f,
            startY = summaryTop + 48f,
            lines = listOf(
                context.getString(R.string.pdf_report_date_start_format, formatDate(report.summary.firstRecordedAt)),
                context.getString(R.string.pdf_report_date_end_format, formatDate(report.summary.lastRecordedAt)),
                context.getString(
                    R.string.pdf_report_latest_format,
                    latestMeasurement?.let { TIMESTAMP_FORMATTER.format(it.recordedAt) } ?: "-",
                ),
                context.getString(
                    R.string.pdf_report_systolic_range_format,
                    report.summary.minimumSystolic,
                    report.summary.maximumSystolic,
                ),
                context.getString(
                    R.string.pdf_report_diastolic_range_format,
                    report.summary.minimumDiastolic,
                    report.summary.maximumDiastolic,
                ),
                context.getString(
                    R.string.pdf_report_pulse_range_format,
                    report.summary.minimumPulse,
                    report.summary.maximumPulse,
                ),
            ),
        )

        drawPanel(
            canvas,
            RectF(300f, summaryTop, 555f, summaryTop + 170f),
            context.getString(R.string.pdf_report_recent_reading_title),
        )
        if (latestMeasurement == null) {
            canvas.drawText(context.getString(R.string.pdf_report_no_measurements_matched), 318f, summaryTop + 60f, bodyPaint)
        } else {
            canvas.drawText(TIMESTAMP_FORMATTER.format(latestMeasurement.recordedAt), 318f, summaryTop + 56f, panelValuePaint)
            canvas.drawText(
                "${latestMeasurement.systolic}/${latestMeasurement.diastolic}",
                318f,
                summaryTop + 92f,
                largeValuePaint,
            )
            canvas.drawText(context.getString(R.string.pdf_report_pulse_format, latestMeasurement.pulse), 318f, summaryTop + 120f, bodyPaint)
            canvas.drawText(context.getString(R.string.pdf_report_flags_format, latestMeasurement.flagsLabel()), 318f, summaryTop + 140f, bodyPaint)
            canvas.drawText(context.getString(R.string.pdf_report_user_format, latestMeasurement.user), 318f, summaryTop + 158f, bodyPaint)
        }

        report.pressureDistribution?.let { distribution ->
            val distributionTop = summaryTop + 190f
            val distributionHeight = max(
                112f,
                74f + max(0, distribution.categories.size - 1) * 22f,
            )
            drawPanel(
                canvas,
                RectF(40f, distributionTop, 555f, distributionTop + distributionHeight),
                context.getString(
                    R.string.pdf_report_pressure_categories_title,
                    classificationSchemeLabel(report.classificationScheme),
                ),
            )
            drawDistributionBars(canvas, distribution, distributionTop + 54f)
        }
    }

    private fun drawTrendPage(
        canvas: Canvas,
        report: MeasurementPdfReport,
        pageNumber: Int,
        totalPages: Int,
    ) {
        canvas.drawText(context.getString(R.string.pdf_report_trends_title), MARGIN_HORIZONTAL.toFloat(), 72f, sectionPaint)
        canvas.drawText(context.getString(R.string.pdf_report_trend_subtitle), MARGIN_HORIZONTAL.toFloat(), 92f, bodyPaint)
        drawLegend(canvas, 112f)

        val chartRect = RectF(58f, 136f, 545f, 556f)
        drawChart(canvas, report, chartRect)

        val insightRect = RectF(40f, 586f, 555f, 748f)
        drawPanel(canvas, insightRect, context.getString(R.string.pdf_report_chart_notes_title))
        val summary = report.summary
        drawPanelLines(
            canvas = canvas,
            startX = insightRect.left + 18f,
            startY = insightRect.top + 44f,
            lines = listOf(
                context.getString(
                    R.string.pdf_report_average_blood_pressure_format,
                    summary.averageSystolic,
                    summary.averageDiastolic,
                ),
                context.getString(R.string.pdf_report_pulse_format, summary.averagePulse),
                context.getString(
                    R.string.pdf_report_flagged_readings_format,
                    summary.irregularHeartbeatCount,
                    summary.movementCount,
                ),
                context.getString(R.string.pdf_report_trend_points_format, report.dailyAverages.size),
            ),
        )
    }

    private fun drawInsightsPage(
        canvas: Canvas,
        report: MeasurementPdfReport,
        pageNumber: Int,
        totalPages: Int,
    ) {
        canvas.drawText(context.getString(R.string.pdf_report_insights_title), MARGIN_HORIZONTAL.toFloat(), 72f, sectionPaint)
        canvas.drawText(context.getString(R.string.pdf_report_insights_subtitle), MARGIN_HORIZONTAL.toFloat(), 92f, bodyPaint)

        val flaggedRect = RectF(40f, 122f, 555f, 356f)
        drawPanel(canvas, flaggedRect, context.getString(R.string.pdf_report_flagged_readings_title))
        val flaggedMeasurements = report.measurements
            .filter { it.irregularHeartbeat || it.movement }
            .take(6)
        if (flaggedMeasurements.isEmpty()) {
            canvas.drawText(context.getString(R.string.pdf_report_no_flagged_readings), 58f, 182f, bodyPaint)
        } else {
            flaggedMeasurements.forEachIndexed { index, measurement ->
                val y = 174f + index * 28f
                canvas.drawText(
                    "${TIMESTAMP_FORMATTER.format(measurement.recordedAt)}  ${measurement.systolic}/${measurement.diastolic}  pulse ${measurement.pulse}  ${measurement.flagsLabel()}",
                    58f,
                    y,
                    bodyPaint,
                )
            }
        }

        val recentRect = RectF(40f, 384f, 555f, 748f)
        drawPanel(canvas, recentRect, context.getString(R.string.pdf_report_recent_measurements_title))
        report.measurements.take(8).forEachIndexed { index, measurement ->
            val y = 438f + index * 30f
            canvas.drawText(TIMESTAMP_FORMATTER.format(measurement.recordedAt), 58f, y, bodyPaint)
            canvas.drawText("${measurement.systolic}/${measurement.diastolic}", 262f, y, panelValuePaint)
            canvas.drawText(context.getString(R.string.pdf_report_pulse_format, measurement.pulse), 352f, y, bodyPaint)
            canvas.drawText(context.getString(R.string.pdf_report_user_format, measurement.user), 442f, y, bodyPaint)
        }
    }

    private fun drawMeasurementTablePage(
        canvas: Canvas,
        report: MeasurementPdfReport,
        rows: List<MeasurementPdfReportRow>,
        pageNumber: Int,
        totalPages: Int,
    ) {
        canvas.drawText(context.getString(R.string.pdf_report_measurements_title), MARGIN_HORIZONTAL.toFloat(), 72f, sectionPaint)
        canvas.drawText(context.getString(R.string.pdf_report_measurements_subtitle), MARGIN_HORIZONTAL.toFloat(), 92f, bodyPaint)

        val top = 124f
        val left = 40f
        canvas.drawRoundRect(RectF(left, top, 555f, top + 32f), 10f, 10f, tableHeaderBackgroundPaint)
        val headerBaseline = top + 20f
        canvas.drawText(context.getString(R.string.pdf_report_time_column), 54f, headerBaseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.pdf_report_bp_column), 234f, headerBaseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.pdf_report_pulse_column), 304f, headerBaseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.pdf_report_flags_column), 372f, headerBaseline, tableHeaderPaint)
        canvas.drawText(context.getString(R.string.pdf_report_user_column), 500f, headerBaseline, tableHeaderPaint)

        rows.forEachIndexed { index, row ->
            val y = top + 58f + index * 22f
            if (index % 2 == 0) {
                canvas.drawRect(RectF(left, y - 15f, 555f, y + 6f), tableStripePaint)
            }
            canvas.drawText(row.timestamp, 54f, y, bodyPaint)
            canvas.drawText(row.bloodPressure, 234f, y, bodyPaint)
            canvas.drawText(row.pulse, 304f, y, bodyPaint)
            canvas.drawText(row.flags, 372f, y, bodyPaint)
            canvas.drawText(row.user, 500f, y, bodyPaint)
        }
    }

    private fun drawChart(
        canvas: Canvas,
        report: MeasurementPdfReport,
        chartRect: RectF,
    ) {
        canvas.drawRoundRect(chartRect, 16f, 16f, panelPaint)
        if (report.dailyAverages.isEmpty()) {
            canvas.drawText(context.getString(R.string.pdf_report_no_measurements_matched), chartRect.left + 20f, chartRect.centerY(), bodyPaint)
            return
        }

        val left = chartRect.left + 46f
        val right = chartRect.right - 18f
        val top = chartRect.top + 18f
        val bottom = chartRect.bottom - 32f
        val plotWidth = right - left
        val plotHeight = bottom - top

        val points = report.dailyAverages.sortedBy { it.date }
        val minPressure = points.minOf { minOf(it.meanSystolic, it.meanDiastolic) }
        val maxPressure = points.maxOf { maxOf(it.meanSystolic, it.meanDiastolic) }
        val paddedMin = max(40, (minPressure - 10) / 10 * 10)
        val paddedMax = ((maxPressure + 19) / 10) * 10
        val pressureRange = max(10, paddedMax - paddedMin)

        repeat(5) { index ->
            val ratio = index / 4f
            val y = bottom - plotHeight * ratio
            canvas.drawLine(left, y, right, y, gridPaint)
            val value = paddedMin + (pressureRange * ratio).toInt()
            canvas.drawText(value.toString(), chartRect.left + 8f, y + 4f, footerPaint)
        }

        val systolicPath = Path()
        val diastolicPath = Path()
        points.forEachIndexed { index, point ->
            val x = if (points.size == 1) {
                left + plotWidth / 2f
            } else {
                left + (plotWidth * index / (points.lastIndex.toFloat()))
            }
            val systolicY = bottom - ((point.meanSystolic - paddedMin).toFloat() / pressureRange) * plotHeight
            val diastolicY = bottom - ((point.meanDiastolic - paddedMin).toFloat() / pressureRange) * plotHeight
            if (index == 0) {
                systolicPath.moveTo(x, systolicY)
                diastolicPath.moveTo(x, diastolicY)
            } else {
                systolicPath.lineTo(x, systolicY)
                diastolicPath.lineTo(x, diastolicY)
            }
            canvas.drawCircle(x, systolicY, 3.5f, accentRedPaint)
            canvas.drawCircle(x, diastolicY, 3.5f, accentBluePaint)
        }
        canvas.drawPath(systolicPath, chartSystolicPaint)
        canvas.drawPath(diastolicPath, chartDiastolicPaint)

        val labels = buildList {
            add(0)
            if (points.size > 2) {
                add(points.lastIndex / 2)
            }
            if (points.size > 1) {
                add(points.lastIndex)
            }
        }.distinct()
        labels.forEach { index ->
            val x = if (points.size == 1) left + plotWidth / 2f else left + (plotWidth * index / points.lastIndex.toFloat())
            val label = SHORT_DATE_FORMATTER.format(points[index].date)
            canvas.drawText(label, x - 16f, chartRect.bottom - 10f, footerPaint)
        }
    }

    private fun drawLegend(canvas: Canvas, baselineY: Float) {
        canvas.drawCircle(46f, baselineY - 4f, 4f, accentRedPaint)
        canvas.drawText(context.getString(R.string.pdf_report_systolic_legend), 56f, baselineY, bodyPaint)
        canvas.drawCircle(122f, baselineY - 4f, 4f, accentBluePaint)
        canvas.drawText(context.getString(R.string.pdf_report_diastolic_legend), 132f, baselineY, bodyPaint)
    }

    private fun drawMetricCard(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        accentPaint: Paint,
    ) {
        canvas.drawRoundRect(rect, 16f, 16f, panelPaint)
        canvas.drawRoundRect(RectF(rect.left, rect.top, rect.left + 10f, rect.bottom), 16f, 16f, accentPaint)
        canvas.drawText(label, rect.left + 18f, rect.top + 28f, bodyPaint)
        canvas.drawText(value, rect.left + 18f, rect.top + 64f, cardValuePaint)
    }

    private fun drawPanel(canvas: Canvas, rect: RectF, title: String) {
        canvas.drawRoundRect(rect, 16f, 16f, panelPaint)
        canvas.drawText(title, rect.left + 18f, rect.top + 26f, panelTitlePaint)
    }

    private fun drawPanelLines(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        lines: List<String>,
    ) {
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, startX, startY + index * 20f, bodyPaint)
        }
    }

    private fun drawDistributionBars(
        canvas: Canvas,
        distribution: MeasurementPdfPressureDistribution,
        top: Float,
    ) {
        val entries = distribution.categories
        val maxCount = max(1, entries.maxOf { it.count })
        entries.forEachIndexed { index, entry ->
            val y = top + index * 22f
            canvas.drawText(
                BloodPressureClassifier.shortLabel(context, entry.category),
                58f,
                y,
                bodyPaint,
            )
            val barLeft = 138f
            val barWidth = 300f * entry.count / maxCount.toFloat()
            canvas.drawRoundRect(RectF(barLeft, y - 11f, barLeft + barWidth, y + 2f), 6f, 6f, barPaint(index))
            canvas.drawText(entry.count.toString(), 454f, y, bodyPaint)
        }
    }

    private fun paginateDetailRows(report: MeasurementPdfReport): List<List<MeasurementPdfReportRow>> {
        if (report.measurements.isEmpty()) {
            return listOf(emptyList())
        }
        val rows = report.measurements.map { measurement ->
            MeasurementPdfReportRow(
                timestamp = TIMESTAMP_FORMATTER.format(measurement.recordedAt),
                bloodPressure = "${measurement.systolic}/${measurement.diastolic}",
                pulse = measurement.pulse.toString(),
                flags = measurement.flagsLabel(),
                user = measurement.user.toString(),
            )
        }
        return rows.chunked(ROWS_PER_DETAIL_PAGE)
    }

    private fun formatDate(recordedAt: LocalDateTime?): String {
        return recordedAt?.let(TIMESTAMP_FORMATTER::format) ?: "-"
    }

    private fun rangeLabel(range: TrendRange): String {
        return when (range) {
            TrendRange.SEVEN_DAYS -> context.getString(R.string.trends_range_7d_long)
            TrendRange.THIRTY_DAYS -> context.getString(R.string.trends_range_30d_long)
            TrendRange.ALL -> context.getString(R.string.trends_range_all_long)
        }
    }

    private fun userLabel(selectedUser: Int?): String {
        return selectedUser?.let { context.getString(R.string.measurement_user_single, it) }
            ?: context.getString(R.string.measurement_user_all)
    }

    private fun classificationSchemeLabel(scheme: BloodPressureClassificationScheme): String {
        return when (scheme) {
            BloodPressureClassificationScheme.DISABLED ->
                context.getString(R.string.blood_pressure_classification_scheme_disabled)
            BloodPressureClassificationScheme.JNC7 ->
                context.getString(R.string.blood_pressure_classification_scheme_jnc7)
            BloodPressureClassificationScheme.ESC_ESH_2018 ->
                context.getString(R.string.blood_pressure_classification_scheme_esc_esh_2018)
        }
    }

    private fun barPaint(index: Int): Paint {
        return when (index) {
            0 -> accentGreenPaint
            1 -> accentBluePaint
            2 -> accentOrangePaint
            3 -> accentRedPaint
            else -> accentDarkPaint
        }
    }

    private data class MeasurementPdfReportRow(
        val timestamp: String,
        val bloodPressure: String,
        val pulse: String,
        val flags: String,
        val user: String,
    )

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN_HORIZONTAL = 40
        const val MARGIN_BOTTOM = 26
        const val ROWS_PER_DETAIL_PAGE = 26

        val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val panelTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 10f
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 9f
        }
        val panelValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val largeValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val cardValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(244, 246, 250)
        }
        val tableHeaderBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(76, 100, 136)
        }
        val tableStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(248, 249, 252)
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(213, 219, 230)
            strokeWidth = 1f
        }
        val chartSystolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(176, 48, 52)
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        val chartDiastolicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(49, 88, 164)
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        val accentRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(176, 48, 52) }
        val accentBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 88, 164) }
        val accentGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 132, 97) }
        val accentOrangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(201, 119, 41) }
        val accentDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(89, 44, 96) }
    }
}
