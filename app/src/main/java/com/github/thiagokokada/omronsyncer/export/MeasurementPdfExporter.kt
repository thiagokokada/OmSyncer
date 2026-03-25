package com.github.thiagokokada.omronsyncer.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.github.thiagokokada.omronsyncer.TrendRange
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MeasurementPdfExporter {

    fun export(
        outputStream: OutputStream,
        report: MeasurementPdfReport,
    ) {
        val document = PdfDocument()
        try {
            val pages = paginate(report)
            pages.forEachIndexed { index, pageRows ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                drawPage(page.canvas, report, pageRows, index + 1, pages.size)
                document.finishPage(page)
            }
            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }

    fun suggestedFileName(
        now: LocalDateTime = LocalDateTime.now(),
        prefix: String = "omsyncer-report",
        extension: String = "pdf",
    ): String {
        return "$prefix-${FILE_NAME_FORMATTER.format(now)}.$extension"
    }

    private fun paginate(report: MeasurementPdfReport): List<List<MeasurementPdfReportRow>> {
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
        return rows.chunked(ROWS_PER_PAGE)
    }

    private fun drawPage(
        canvas: Canvas,
        report: MeasurementPdfReport,
        rows: List<MeasurementPdfReportRow>,
        pageNumber: Int,
        totalPages: Int,
    ) {
        canvas.drawColor(Color.WHITE)

        var y = MARGIN_TOP.toFloat()
        canvas.drawText("Blood pressure report", MARGIN_HORIZONTAL.toFloat(), y, titlePaint)
        y += 26f
        canvas.drawText(
            "Generated ${TIMESTAMP_FORMATTER.format(report.generatedAt)}",
            MARGIN_HORIZONTAL.toFloat(),
            y,
            bodyPaint,
        )
        y += 18f
        canvas.drawText(
            "Selection ${rangeLabel(report.range)} - ${userLabel(report.selectedUser)}",
            MARGIN_HORIZONTAL.toFloat(),
            y,
            bodyPaint,
        )
        y += 28f

        if (pageNumber == 1) {
            y = drawSummary(canvas, report, y)
            y += 20f
        }

        canvas.drawText("Measurements", MARGIN_HORIZONTAL.toFloat(), y, sectionPaint)
        y += 18f

        drawTableHeader(canvas, y)
        y += 20f

        if (rows.isEmpty()) {
            canvas.drawText("No measurements matched the current filters.", MARGIN_HORIZONTAL.toFloat(), y, bodyPaint)
            y += 18f
        } else {
            rows.forEach { row ->
                drawTableRow(canvas, row, y)
                y += 18f
            }
        }

        canvas.drawText(
            "Page $pageNumber of $totalPages",
            MARGIN_HORIZONTAL.toFloat(),
            (PAGE_HEIGHT - MARGIN_BOTTOM).toFloat(),
            footerPaint,
        )
    }

    private fun drawSummary(
        canvas: Canvas,
        report: MeasurementPdfReport,
        startY: Float,
    ): Float {
        var y = startY
        canvas.drawText("Summary", MARGIN_HORIZONTAL.toFloat(), y, sectionPaint)
        y += 18f
        val summary = report.summary
        val dateSpan = if (summary.firstRecordedAt != null && summary.lastRecordedAt != null) {
            "${TIMESTAMP_FORMATTER.format(summary.firstRecordedAt)} to ${TIMESTAMP_FORMATTER.format(summary.lastRecordedAt)}"
        } else {
            "-"
        }
        val lines = listOf(
            "Measurements: ${summary.measurementCount}",
            "Average blood pressure: ${summary.averageSystolic}/${summary.averageDiastolic}",
            "Average pulse: ${summary.averagePulse}",
            "Date span: $dateSpan",
            "Flagged readings: ${summary.irregularHeartbeatCount} irregular heartbeat, ${summary.movementCount} movement",
        )
        lines.forEach { line ->
            canvas.drawText(line, MARGIN_HORIZONTAL.toFloat(), y, bodyPaint)
            y += 18f
        }
        if (report.dailyAverages.isNotEmpty()) {
            y += 10f
            canvas.drawText("Daily averages", MARGIN_HORIZONTAL.toFloat(), y, sectionPaint)
            y += 18f
            report.dailyAverages.take(5).forEach { average ->
                canvas.drawText(
                    "${DATE_FORMATTER.format(average.date)} - ${average.meanSystolic}/${average.meanDiastolic} - pulse ${average.meanPulse} - ${average.measurementCount} reading(s)",
                    MARGIN_HORIZONTAL.toFloat(),
                    y,
                    bodyPaint,
                )
                y += 18f
            }
        }
        return y
    }

    private fun drawTableHeader(canvas: Canvas, y: Float) {
        canvas.drawText("Time", X_TIME, y, tableHeaderPaint)
        canvas.drawText("BP", X_BP, y, tableHeaderPaint)
        canvas.drawText("Pulse", X_PULSE, y, tableHeaderPaint)
        canvas.drawText("Flags", X_FLAGS, y, tableHeaderPaint)
        canvas.drawText("User", X_USER, y, tableHeaderPaint)
    }

    private fun drawTableRow(canvas: Canvas, row: MeasurementPdfReportRow, y: Float) {
        canvas.drawText(row.timestamp, X_TIME, y, bodyPaint)
        canvas.drawText(row.bloodPressure, X_BP, y, bodyPaint)
        canvas.drawText(row.pulse, X_PULSE, y, bodyPaint)
        canvas.drawText(row.flags, X_FLAGS, y, bodyPaint)
        canvas.drawText(row.user, X_USER, y, bodyPaint)
    }

    private fun rangeLabel(range: TrendRange): String {
        return when (range) {
            TrendRange.SEVEN_DAYS -> "Last 7 days"
            TrendRange.THIRTY_DAYS -> "Last 30 days"
            TrendRange.ALL -> "All data"
        }
    }

    private fun userLabel(selectedUser: Int?): String {
        return selectedUser?.let { "User $it" } ?: "All users"
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
        const val MARGIN_TOP = 48
        const val MARGIN_BOTTOM = 36
        const val ROWS_PER_PAGE = 25

        const val X_TIME = 40f
        const val X_BP = 230f
        const val X_PULSE = 305f
        const val X_FLAGS = 370f
        const val X_USER = 520f

        val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
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
    }
}
